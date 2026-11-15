package com.creatrbypc.createanalytical.project

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

// パッケージアーカイブ createscript-package.zip（S8.2 Phase 1。docs/packages.md の「Package archives」）。バイト列だけを扱い、ファイル・ネットワークには触れない。
// 中身は createscript.json（schemaVersion 2 の library）・modules/名前.cscr・LICENSE などの説明のファイルだけで、どれもソースコード・文書として扱う
// （中身・マニフェストによって、OS・ネットワーク・JVM を使う権限が増えることは無い。インストールでも、コードを実行しない）
@InternalCreateScriptProjectApi
public object PackageArchives {
    public const val FILE_NAME: String = "createscript-package.zip"
    internal const val MAX_ARCHIVE_BYTES = 32 * 1024 * 1024
    public const val MAX_TOTAL_BYTES: Int = 16 * 1024 * 1024
    public const val MAX_ENTRIES: Int = 1024
    public const val MAX_OTHER_FILE_BYTES: Int = 1024 * 1024
    internal const val MAX_COMPRESSION_RATIO = 100

    // 説明のファイル（アーカイブの直下だけ）
    private val DOCUMENTS = listOf("LICENSE", "LICENCE", "NOTICE", "README", "CHANGELOG", "COPYING").flatMap { listOf(it, "$it.md", "$it.txt") }.toSet()

    // アーカイブに入れられるパスか（入れられなければ理由）。createscript.json・modules/名前.cscr・説明のファイルだけ
    public fun pathProblem(path: String): String? {
        if (path == Manifests.FILE_NAME || path in DOCUMENTS) return null
        if (path.startsWith("modules/") && path.endsWith(".cscr")) {
            val module = path.removePrefix("modules/").removeSuffix(".cscr")
            return when {
                ProjectRules.isValidModuleName(module) -> null
                ProjectRules.isWindowsReserved(module) -> ProjectRules.reservedNameReason(module)
                else -> "'$module' is not a valid module name"
            }
        }
        return "only createscript.json, modules/<module>.cscr and LICENSE, NOTICE, README, CHANGELOG or COPYING (.md, .txt) are allowed"
    }

    internal fun isDocument(path: String) = path in DOCUMENTS

    // ---- 書き出し（createscript pack） ----

    // 決まった形の ZIP を作る。項目はパスの順（UTF-16 の順。パスは ASCII だけ）、圧縮しない（STORED: 圧縮の実装・版で中身が変わらない）、
    // 時刻は 1980-01-01 00:00、権限は 0644 の通常のファイル、ディレクトリの項目・コメント・拡張フィールド・データディスクリプターは無し。
    // 同じパスと中身からは、必ず同じバイト列になる。パスと中身の検査は read で行う（ここでは形だけを確かめる）
    public fun write(files: Map<String, ByteArray>): ByteArray {
        val entries = files.entries.sortedBy { it.key }
        require(entries.isNotEmpty()) { "an archive needs files" }
        val out = java.io.ByteArrayOutputStream()
        val central = java.io.ByteArrayOutputStream()
        for ((path, data) in entries) {
            val name = path.toByteArray(Charsets.US_ASCII)
            require(String(name, Charsets.US_ASCII) == path) { "paths must be ASCII: $path" }
            val crc = CRC32().apply { update(data) }.value
            val offset = out.size()
            // ローカルヘッダー
            out.int32(LOCAL_SIGNATURE); out.int16(VERSION_NEEDED); out.int16(0); out.int16(STORED); out.int16(DOS_TIME); out.int16(DOS_DATE)
            out.int32(crc); out.int32(data.size.toLong()); out.int32(data.size.toLong()); out.int16(name.size); out.int16(0)
            out.write(name); out.write(data)
            // 中央ディレクトリの項目
            central.int32(CENTRAL_SIGNATURE); central.int16(VERSION_MADE_BY); central.int16(VERSION_NEEDED); central.int16(0); central.int16(STORED)
            central.int16(DOS_TIME); central.int16(DOS_DATE); central.int32(crc); central.int32(data.size.toLong()); central.int32(data.size.toLong())
            central.int16(name.size); central.int16(0); central.int16(0); central.int16(0); central.int16(0); central.int32(REGULAR_FILE_ATTRIBUTES)
            central.int32(offset.toLong()); central.write(name)
        }
        val centralOffset = out.size()
        out.write(central.toByteArray())
        out.int32(END_SIGNATURE); out.int16(0); out.int16(0); out.int16(entries.size); out.int16(entries.size)
        out.int32(central.size().toLong()); out.int32(centralOffset.toLong()); out.int16(0)
        return out.toByteArray()
    }

    // ---- 正規形（S8.2 M-1） ----
    // 正規形のアーカイブは、write がその中身から作るものと 1 バイトも違わないもの（createscript pack の出力）。
    // 中身から正規形のアーカイブが 1 つに決まるので、インストールしたファイルから作り直したアーカイブの SHA-256 を、ロックファイルの値と比べられる

    // read に通ったアーカイブ archive（bytes を読んだもの）が正規形か
    public fun isCanonical(bytes: ByteArray, archive: Archive): Boolean = write(archive.files).contentEquals(bytes)

    // ファイル（パス → 中身）から正規形のアーカイブを作り直す。アーカイブに入れられないパスがある・ファイルが無いなら null
    public fun rebuild(files: Map<String, ByteArray>): ByteArray? {
        if (files.isEmpty() || files.keys.any { pathProblem(it) != null }) return null
        return write(files)
    }

    private fun java.io.ByteArrayOutputStream.int16(value: Int) {
        write(value and 0xFF); write((value shr 8) and 0xFF)
    }

    private fun java.io.ByteArrayOutputStream.int32(value: Long) {
        for (shift in 0..24 step 8) write(((value shr shift) and 0xFF).toInt())
    }

    // ---- 読み取りと検査 ----

    // 検査に通ったアーカイブ。files はパス → 中身（パスの順。ディレクトリの項目は含まない）、manifest はアーカイブの createscript.json
    public class Archive(public val manifest: Manifest, public val files: Map<String, ByteArray>)

    // diagnostics の path はアーカイブの中のファイル（アーカイブ全体の問題は null）
    public class ArchiveDiagnostic(public val path: String?, public val diagnostic: ProjectDiagnostic)

    public class ArchiveResult(public val archive: Archive?, public val diagnostics: List<ArchiveDiagnostic>)

    private class Invalid(val path: String?, val code: ProjectDiagnosticCode, val arguments: List<String>) : RuntimeException(null, null, false, false)

    private fun invalid(code: ProjectDiagnosticCode, vararg arguments: String, path: String? = null): Nothing = throw Invalid(path, code, arguments.toList())

    // 厳しい形の ZIP として読み、中身を確かめる。expectedName・expectedVersion を渡せば、マニフェストの name・version がそれと同じことも確かめる。
    // ZIP の形（S8.2 の設計の 9）:
    // - 末尾の中央ディレクトリの終わり（コメントは可）、その直前の中央ディレクトリ、先頭から隙間・重なり無く並ぶローカルヘッダーと中身。それ以外のバイト列（前置き・隙間）は不可
    // - ZIP64・複数のディスク・暗号化・データディスクリプター・STORED と DEFLATED 以外の圧縮は不可。中央ディレクトリとローカルヘッダーの名前・大きさ・CRC・方式は同じ
    // - 名前は ASCII の印字できる文字。通常のファイルとディレクトリだけ（Unix の種類が記号リンクなどのものは不可）。同じパス・大文字と小文字だけが違うパスは不可
    // - 展開は宣言された大きさの上限まで実際のバイト数で数え、合計・1 ファイル・圧縮率の上限を超えたら止める（ZIP 爆弾）。CRC32 を確かめる
    public fun read(bytes: ByteArray, expectedName: String? = null, expectedVersion: String? = null): ArchiveResult {
        return try {
            val files = unzip(bytes)
            ArchiveResult(checkContents(files, expectedName, expectedVersion), emptyList())
        } catch (problem: Invalid) {
            ArchiveResult(null, listOf(ArchiveDiagnostic(problem.path, ProjectDiagnostic(problem.code, 1, 1, problem.arguments))))
        } catch (problem: ContentProblems) {
            ArchiveResult(null, problem.diagnostics)
        }
    }

    private class ContentProblems(val diagnostics: List<ArchiveDiagnostic>) : RuntimeException(null, null, false, false)

    private class Entry(val name: String, val method: Int, val crc: Long, val compressedSize: Long, val size: Long, val localOffset: Long, val directory: Boolean)

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        if (bytes.size > MAX_ARCHIVE_BYTES) invalid(ProjectDiagnosticCode.ARCHIVE_TOO_LARGE, MAX_ARCHIVE_BYTES.toString())
        val data = Reader(bytes)
        // 中央ディレクトリの終わり（22 バイト + コメント）を末尾から探す。コメントはファイルの終わりまで
        val end = (bytes.size - 22 downTo maxOf(0, bytes.size - 22 - 0xFFFF)).firstOrNull { data.int32(it) == END_SIGNATURE && it + 22 + data.int16(it + 20) == bytes.size }
            ?: invalid(ProjectDiagnosticCode.ARCHIVE_MALFORMED, "no end of central directory record")
        if (end >= 20 && data.int32(end - 20) == ZIP64_LOCATOR_SIGNATURE) invalid(ProjectDiagnosticCode.ARCHIVE_UNSUPPORTED, "ZIP64")
        val disk = data.int16(end + 4)
        val centralDisk = data.int16(end + 6)
        val entriesHere = data.int16(end + 8)
        val entriesTotal = data.int16(end + 10)
        val centralSize = data.int32(end + 12)
        val centralOffset = data.int32(end + 16)
        if (entriesTotal == 0xFFFF || centralSize == 0xFFFFFFFFL || centralOffset == 0xFFFFFFFFL) invalid(ProjectDiagnosticCode.ARCHIVE_UNSUPPORTED, "ZIP64")
        if (disk != 0 || centralDisk != 0 || entriesHere != entriesTotal) invalid(ProjectDiagnosticCode.ARCHIVE_UNSUPPORTED, "multiple disks")
        if (entriesTotal > MAX_ENTRIES) invalid(ProjectDiagnosticCode.ARCHIVE_TOO_MANY_FILES, MAX_ENTRIES.toString())
        if (centralOffset + centralSize != end.toLong()) invalid(ProjectDiagnosticCode.ARCHIVE_MALFORMED, "the central directory does not end where the end record starts")
        // 中央ディレクトリ
        val entries = ArrayList<Entry>()
        val seen = HashSet<String>()
        val seenFolded = HashSet<String>()
        var position = centralOffset.toInt()
        repeat(entriesTotal) {
            if (data.int32(position) != CENTRAL_SIGNATURE) invalid(ProjectDiagnosticCode.ARCHIVE_MALFORMED, "bad central directory entry")
            val madeBy = data.int16(position + 4)
            val flags = data.int16(position + 8)
            val method = data.int16(position + 10)
            val crc = data.int32(position + 16)
            val compressedSize = data.int32(position + 20)
            val size = data.int32(position + 24)
            val nameLength = data.int16(position + 28)
            val extraLength = data.int16(position + 30)
            val commentLength = data.int16(position + 32)
            val entryDisk = data.int16(position + 34)
            val external = data.int32(position + 38)
            val localOffset = data.int32(position + 42)
            val name = data.ascii(position + 46, nameLength) ?: invalid(ProjectDiagnosticCode.ARCHIVE_INVALID_PATH, "?", "names must be printable ASCII")
            position += 46 + nameLength + extraLength + commentLength
            if (position > end) invalid(ProjectDiagnosticCode.ARCHIVE_MALFORMED, "the central directory is too long")
            if (compressedSize == 0xFFFFFFFFL || size == 0xFFFFFFFFL || localOffset == 0xFFFFFFFFL) invalid(ProjectDiagnosticCode.ARCHIVE_UNSUPPORTED, "ZIP64", path = name)
            if (entryDisk != 0) invalid(ProjectDiagnosticCode.ARCHIVE_UNSUPPORTED, "multiple disks", path = name)
            checkFlags(flags, name)
            if (method != STORED && method != DEFLATED) invalid(ProjectDiagnosticCode.ARCHIVE_UNSUPPORTED, "compression method $method", path = name)
            val directory = name.endsWith("/")
            checkType(madeBy, external, directory, name)
            if (directory) {
                if (name != "modules/") invalid(ProjectDiagnosticCode.ARCHIVE_INVALID_PATH, name, "the only directory is modules/", path = name)
                if (size != 0L || compressedSize > 2) invalid(ProjectDiagnosticCode.ARCHIVE_MALFORMED, "a directory entry has contents", path = name)
            } else {
                pathProblem(name)?.let { invalid(ProjectDiagnosticCode.ARCHIVE_INVALID_PATH, name, it, path = name) }
            }
            if (!seen.add(name)) invalid(ProjectDiagnosticCode.ARCHIVE_DUPLICATE_PATH, name, path = name)
            if (!seenFolded.add(name.lowercase())) invalid(ProjectDiagnosticCode.ARCHIVE_DUPLICATE_PATH, name, path = name)
            entries.add(Entry(name, method, crc, compressedSize, size, localOffset, directory))
        }
        if (position.toLong() != centralOffset + centralSize) invalid(ProjectDiagnosticCode.ARCHIVE_MALFORMED, "the central directory size does not match its entries")
        // 宣言された大きさの合計を、展開の前に確かめる（宣言とずれた中身は、展開のときに止める）
        var declared = 0L
        for (entry in entries) {
            val limit = if (entry.name.endsWith(".cscr")) ProjectRules.MAX_SOURCE_BYTES.toLong() else if (entry.directory) 0L else MAX_OTHER_FILE_BYTES.toLong()
            if (entry.size > limit) invalid(ProjectDiagnosticCode.ARCHIVE_FILE_TOO_LARGE, entry.name, limit.toString(), path = entry.name)
            if (entry.method == DEFLATED && entry.size > MAX_COMPRESSION_RATIO * maxOf(1L, entry.compressedSize)) {
                invalid(ProjectDiagnosticCode.ARCHIVE_COMPRESSION_RATIO, entry.name, MAX_COMPRESSION_RATIO.toString(), path = entry.name)
            }
            declared += entry.size
            if (declared > MAX_TOTAL_BYTES) invalid(ProjectDiagnosticCode.ARCHIVE_TOTAL_TOO_LARGE, MAX_TOTAL_BYTES.toString())
        }
        // ローカルヘッダーと中身: 先頭から、中央ディレクトリの順に関係なくオフセットの順に、隙間・重なり無く並ぶ
        var next = 0L
        val files = java.util.TreeMap<String, ByteArray>()
        for (entry in entries.sortedBy { it.localOffset }) {
            if (entry.localOffset != next) invalid(ProjectDiagnosticCode.ARCHIVE_MALFORMED, "entries must follow each other without gaps or overlaps", path = entry.name)
            val at = entry.localOffset.toInt()
            if (at + 30 > centralOffset || data.int32(at) != LOCAL_SIGNATURE) invalid(ProjectDiagnosticCode.ARCHIVE_MALFORMED, "bad local header", path = entry.name)
            val flags = data.int16(at + 6)
            val method = data.int16(at + 8)
            val crc = data.int32(at + 14)
            val compressedSize = data.int32(at + 18)
            val size = data.int32(at + 22)
            val nameLength = data.int16(at + 26)
            val extraLength = data.int16(at + 28)
            val name = data.ascii(at + 30, nameLength)
            checkFlags(flags, entry.name)
            if (name != entry.name || method != entry.method || crc != entry.crc || compressedSize != entry.compressedSize || size != entry.size) {
                invalid(ProjectDiagnosticCode.ARCHIVE_MALFORMED, "the local header does not match the central directory", path = entry.name)
            }
            val start = at.toLong() + 30 + nameLength + extraLength
            next = start + compressedSize
            if (next > centralOffset) invalid(ProjectDiagnosticCode.ARCHIVE_MALFORMED, "an entry runs into the central directory", path = entry.name)
            if (entry.directory) continue
            val contents = expand(bytes, start.toInt(), compressedSize.toInt(), entry)
            val checksum = CRC32().apply { update(contents) }.value
            if (checksum != entry.crc) invalid(ProjectDiagnosticCode.ARCHIVE_CORRUPT_ENTRY, entry.name, path = entry.name)
            files[entry.name] = contents
        }
        if (next != centralOffset) invalid(ProjectDiagnosticCode.ARCHIVE_MALFORMED, "there are bytes between the last entry and the central directory")
        return files
    }

    // 暗号化（ビット 0・6）・データディスクリプター（ビット 3）・パッチ（5）・予約などは不可。名前が UTF-8（ビット 11）と、DEFLATE の圧縮の程度（1・2）は可
    private fun checkFlags(flags: Int, name: String) {
        if ((flags and 0x0001) != 0 || (flags and 0x0040) != 0) invalid(ProjectDiagnosticCode.ARCHIVE_UNSUPPORTED, "encryption", path = name)
        if ((flags and 0x0008) != 0) invalid(ProjectDiagnosticCode.ARCHIVE_UNSUPPORTED, "data descriptors", path = name)
        if ((flags and 0x0806.inv() and 0xFFFF) != 0) invalid(ProjectDiagnosticCode.ARCHIVE_UNSUPPORTED, "general purpose flags 0x%04x".format(flags), path = name)
    }

    // Unix で作られた項目（作成した OS の番号 3）は、外部属性の上位 16 ビットの種類が通常のファイル・ディレクトリ（種類なしも可）だけ。
    // それ以外の OS の項目は、MS-DOS の属性のディレクトリのビットが、名前（/ で終わるか）と合うこと
    private fun checkType(madeBy: Int, external: Long, directory: Boolean, name: String) {
        if (madeBy shr 8 == 3) {
            val type = ((external shr 16) and 0xF000L).toInt()
            val expected = if (directory) 0x4000 else 0x8000
            if (type != 0 && type != expected) invalid(ProjectDiagnosticCode.ARCHIVE_SPECIAL_FILE, name, path = name)
        }
        if (!directory && (external and 0x10L) != 0L) invalid(ProjectDiagnosticCode.ARCHIVE_SPECIAL_FILE, name, path = name)
    }

    // 中身を展開する。STORED は大きさが同じこと。DEFLATED は宣言された大きさ + 1 バイトまでしか出力しない（それを超えたら、宣言と違う）
    private fun expand(bytes: ByteArray, start: Int, length: Int, entry: Entry): ByteArray {
        if (entry.method == STORED) {
            if (entry.compressedSize != entry.size) invalid(ProjectDiagnosticCode.ARCHIVE_MALFORMED, "a stored entry has two different sizes", path = entry.name)
            return bytes.copyOfRange(start, start + length)
        }
        val inflater = Inflater(true)
        try {
            inflater.setInput(bytes, start, length)
            val output = ByteArray(entry.size.toInt() + 1)
            var produced = 0
            while (!inflater.finished()) {
                if (produced == output.size) invalid(ProjectDiagnosticCode.ARCHIVE_CORRUPT_ENTRY, entry.name, path = entry.name)
                val count = try {
                    inflater.inflate(output, produced, output.size - produced)
                } catch (error: DataFormatException) {
                    invalid(ProjectDiagnosticCode.ARCHIVE_CORRUPT_ENTRY, entry.name, path = entry.name)
                }
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) invalid(ProjectDiagnosticCode.ARCHIVE_CORRUPT_ENTRY, entry.name, path = entry.name)
                produced += count
            }
            if (produced.toLong() != entry.size || inflater.remaining != 0) invalid(ProjectDiagnosticCode.ARCHIVE_CORRUPT_ENTRY, entry.name, path = entry.name)
            return output.copyOf(produced)
        } finally {
            inflater.end()
        }
    }

    // 中身の検査: createscript.json が schemaVersion 2 の library で、名前・版が期待どおり、modules のファイルがそろい、.cscr が使えるソースであること
    private fun checkContents(files: Map<String, ByteArray>, expectedName: String?, expectedVersion: String?): Archive {
        val manifestBytes = files[Manifests.FILE_NAME] ?: invalid(ProjectDiagnosticCode.ARCHIVE_MISSING_MANIFEST)
        val parsed = Manifests.parse(manifestBytes)
        val manifest = parsed.manifest ?: throw ContentProblems(parsed.diagnostics.map { ArchiveDiagnostic(Manifests.FILE_NAME, it) })
        if (manifest.kind != PackageKind.LIBRARY) invalid(ProjectDiagnosticCode.ARCHIVE_NOT_A_LIBRARY, path = Manifests.FILE_NAME)
        if (expectedName != null && manifest.name != expectedName) invalid(ProjectDiagnosticCode.ARCHIVE_NAME_MISMATCH, expectedName, manifest.name, path = Manifests.FILE_NAME)
        if (expectedVersion != null && manifest.version != expectedVersion) invalid(ProjectDiagnosticCode.ARCHIVE_VERSION_MISMATCH, expectedVersion, manifest.version, path = Manifests.FILE_NAME)
        for (module in manifest.modules) {
            if ("modules/$module.cscr" !in files) invalid(ProjectDiagnosticCode.ARCHIVE_MISSING_MODULE, module, path = Manifests.FILE_NAME)
        }
        for ((path, contents) in files) {
            if (!path.endsWith(".cscr")) continue
            sourceProblem(contents)?.let { invalid(ProjectDiagnosticCode.ARCHIVE_INVALID_SOURCE, path, it, path = path) }
        }
        return Archive(manifest, files)
    }

    // .cscr のソースとして使えなければ理由（UTF-8 として正しく、BOM で始まらず、MAX_SOURCE_LENGTH 単位以下）
    public fun sourceProblem(contents: ByteArray): String? {
        if (contents.size > ProjectRules.MAX_SOURCE_BYTES) return "larger than ${ProjectRules.MAX_SOURCE_BYTES} bytes"
        if (contents.size >= 3 && contents[0] == 0xEF.toByte() && contents[1] == 0xBB.toByte() && contents[2] == 0xBF.toByte()) return "starts with a byte order mark"
        val text = try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(contents))
        } catch (error: CharacterCodingException) {
            return "not valid UTF-8"
        }
        if (text.length > ProjectRules.MAX_SOURCE_LENGTH) return "longer than ${ProjectRules.MAX_SOURCE_LENGTH} characters"
        return null
    }

    // リトルエンディアンの読み取り（範囲外は ZIP として正しくないものとする）
    private class Reader(private val bytes: ByteArray) {
        private fun byte(index: Int): Int {
            if (index < 0 || index >= bytes.size) invalid(ProjectDiagnosticCode.ARCHIVE_MALFORMED, "a record runs past the end of the archive")
            return bytes[index].toInt() and 0xFF
        }

        fun int16(index: Int): Int = byte(index) or (byte(index + 1) shl 8)

        fun int32(index: Int): Long = int16(index).toLong() or (int16(index + 2).toLong() shl 16)

        // 印字できる ASCII（0x21〜0x7E。空白も不可）の名前。そうでなければ null
        fun ascii(index: Int, length: Int): String? {
            if (length == 0) return null
            val text = StringBuilder(length)
            for (i in 0 until length) {
                val c = byte(index + i)
                if (c < 0x21 || c > 0x7E) return null
                text.append(c.toChar())
            }
            return text.toString()
        }
    }

    private const val LOCAL_SIGNATURE = 0x04034b50L
    private const val CENTRAL_SIGNATURE = 0x02014b50L
    private const val END_SIGNATURE = 0x06054b50L
    private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50L
    private const val STORED = 0
    private const val DEFLATED = 8
    private const val VERSION_NEEDED = 10
    private const val VERSION_MADE_BY = (3 shl 8) or 20
    private const val DOS_TIME = 0
    private const val DOS_DATE = (1 shl 5) or 1
    private const val REGULAR_FILE_ATTRIBUTES = 0x81A4L shl 16
}
