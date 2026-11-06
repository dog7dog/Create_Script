package com.creatrbypc.createanalytical.project

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.random.Random

// S8.2 Phase 1: パッケージアーカイブ（createscript-package.zip）の書き出しと、厳しい形の読み取り・検査
class PackageArchiveTest {
    private fun code(bytes: ByteArray, name: String? = null, version: String? = null): ProjectDiagnosticCode {
        val result = PackageArchives.read(bytes, name, version)
        assertNull(result.archive)
        return result.diagnostics.first().diagnostic.code
    }

    private fun files() = linkedMapOf(
        "modules/vector_math.cscr" to ZipBuilder.MODULE,
        "createscript.json" to ZipBuilder.MANIFEST,
        "LICENSE" to "MIT License\n".toByteArray(),
        "modules/helpers.cscr" to "fun two(): Int {\n    return 2\n}\n".toByteArray(),
    )

    // ---- 書き出し ----

    @Test
    fun writesDeterministicArchives() {
        val first = PackageArchives.write(files())
        val second = PackageArchives.write(LinkedHashMap(files().entries.reversed().associate { it.key to it.value }))
        assertArrayEquals(first, second)
        // 読み直すと同じ中身（パスの順）
        val archive = PackageArchives.read(first, "vector-math", "1.2.0").archive!!
        assertEquals(listOf("LICENSE", "createscript.json", "modules/helpers.cscr", "modules/vector_math.cscr"), archive.files.keys.toList())
        assertEquals("vector-math", archive.manifest.name)
        assertEquals(listOf("vector_math"), archive.manifest.modules)
        assertArrayEquals(ZipBuilder.MODULE, archive.files["modules/vector_math.cscr"])
        // 標準の ZIP の読み取り（java.util.zip）でも読める。時刻は 1980-01-01、圧縮しない
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(first)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names.add(entry.name)
                assertEquals(0, entry.method)
                assertEquals(1980, java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(entry.time), java.time.ZoneId.systemDefault()).year)
                assertArrayEquals(files()[entry.name], zip.readAllBytes())
            }
        }
        assertEquals(archive.files.keys.toList(), names)
        // 中身が 1 バイト違えば、ハッシュも違う
        val changed = PackageArchives.write(files().also { it["LICENSE"] = "MIT License!\n".toByteArray() })
        assertTrue(!MessageDigest.getInstance("SHA-256").digest(first).contentEquals(MessageDigest.getInstance("SHA-256").digest(changed)))
        assertThrows(IllegalArgumentException::class.java) { PackageArchives.write(mapOf("modules/é.cscr" to ByteArray(0))) }
    }

    // ほかのツールで作った正しい形（DEFLATED・modules/ のディレクトリの項目）も読める
    @Test
    fun readsDeflatedArchives() {
        val zip = ZipBuilder().add("modules/", ByteArray(0)) { external = 0x41EDL shl 16 }
            .add("createscript.json", ZipBuilder.MANIFEST) { method = 8 }
            .add("modules/vector_math.cscr", ZipBuilder.MODULE) { method = 8 }
            .build()
        val archive = PackageArchives.read(zip).archive
        assertNotNull(archive)
        assertEquals(setOf("createscript.json", "modules/vector_math.cscr"), archive!!.files.keys)
    }

    // ---- 正規形（S8.2 M-1） ----

    // write の出力は正規形で、読んだ中身から 1 バイトも違わずに作り直せる
    @Test
    fun rebuildsCanonicalArchivesByteForByte() {
        val written = PackageArchives.write(files())
        val archive = PackageArchives.read(written).archive!!
        assertTrue(PackageArchives.isCanonical(written, archive))
        assertArrayEquals(written, PackageArchives.rebuild(archive.files))
        assertArrayEquals(written, PackageArchives.rebuild(LinkedHashMap(archive.files.entries.reversed().associate { it.key to it.value })))
        // 1 ファイルの 1 バイト・ファイルの追加・削除で、作り直したものも変わる
        val changed = HashMap(archive.files).also { it["LICENSE"] = "MIT License!\n".toByteArray() }
        assertTrue(!written.contentEquals(PackageArchives.rebuild(changed)))
        assertTrue(!written.contentEquals(PackageArchives.rebuild(archive.files + ("NOTICE" to ByteArray(0)))))
        assertTrue(!written.contentEquals(PackageArchives.rebuild(archive.files - "LICENSE")))
        // アーカイブに入れられないパス（ASCII でないものも）・空は作り直さない（例外にしない）
        assertNull(PackageArchives.rebuild(archive.files + ("evil.txt" to ByteArray(0))))
        assertNull(PackageArchives.rebuild(archive.files + ("modules/é.cscr" to ByteArray(0))))
        assertNull(PackageArchives.rebuild(archive.files + ("modules/" to ByteArray(0))))
        assertNull(PackageArchives.rebuild(emptyMap()))
    }

    // 読める（read に通る）が正規形でない ZIP: ほかのツールの形・項目の順・コメント・属性・フラグ
    @Test
    fun recognisesArchivesThatAreNotCanonical() {
        fun base(configure: ZipBuilder.Entry.() -> Unit = {}) = ZipBuilder().add("createscript.json", ZipBuilder.MANIFEST, configure).add("modules/vector_math.cscr", ZipBuilder.MODULE, configure)
        val cases = mapOf(
            "deflated" to base { method = 8 }.build(),
            "directory entry" to ZipBuilder().add("createscript.json", ZipBuilder.MANIFEST).add("modules/", ByteArray(0)) { external = 0x41EDL shl 16 }.add("modules/vector_math.cscr", ZipBuilder.MODULE).build(),
            "unsorted" to ZipBuilder().add("modules/vector_math.cscr", ZipBuilder.MODULE).add("createscript.json", ZipBuilder.MANIFEST).build(),
            "comment" to base().apply { comment = "hi".toByteArray() }.build(),
            "made by MS-DOS" to base { madeBy = 20; external = 0 }.build(),
            "UTF-8 flag" to base { flags = 0x0800 }.build(),
            "java.util.zip" to java.io.ByteArrayOutputStream().also { bytes ->
                java.util.zip.ZipOutputStream(bytes).use { zip ->
                    for ((name, data) in sortedMapOf("createscript.json" to ZipBuilder.MANIFEST, "modules/vector_math.cscr" to ZipBuilder.MODULE)) {
                        zip.putNextEntry(java.util.zip.ZipEntry(name).apply { method = java.util.zip.ZipEntry.STORED; size = data.size.toLong(); crc = java.util.zip.CRC32().apply { update(data) }.value })
                        zip.write(data)
                        zip.closeEntry()
                    }
                }
            }.toByteArray(),
        )
        for ((label, bytes) in cases) {
            val archive = PackageArchives.read(bytes).archive
            assertNotNull(archive, label)
            assertTrue(!PackageArchives.isCanonical(bytes, archive!!), label)
            // 作り直した正規形は、同じ中身の write の出力
            assertArrayEquals(PackageArchives.write(archive.files), PackageArchives.rebuild(archive.files), label)
        }
    }

    // ---- パスと種類 ----

    @Test
    fun rejectsPaths() {
        val paths = listOf(
            "../evil.cscr", "modules/../../x.cscr", "/etc/passwd", "modules\\x.cscr", "C:/x.cscr", "modules/sub/x.cscr", "modules/x.txt", "modules/1x.cscr",
            "modules/fun.cscr", "modules/println.cscr", "modules/_.cscr", "modules/x-y.cscr", "build.sh", "createscript.lock", "modules/x.zip", "src/main.cscr",
            "license", "LICENSE.html", ".hidden",
        )
        for (path in paths) {
            val zip = ZipBuilder.valid().add(path, "x".toByteArray()).build()
            val diagnostic = PackageArchives.read(zip).diagnostics.first().diagnostic
            assertTrue(diagnostic.code == ProjectDiagnosticCode.ARCHIVE_INVALID_PATH, "$path: ${diagnostic.code}")
        }
        // 空白・制御文字・ASCII 以外の名前
        for (name in listOf(byteArrayOf('a'.code.toByte(), ' '.code.toByte()), byteArrayOf(0x00), "modules/é.cscr".toByteArray(), ByteArray(0))) {
            val zip = ZipBuilder.valid().add("x", "x".toByteArray()) { nameBytes = name }.build()
            assertEquals(ProjectDiagnosticCode.ARCHIVE_INVALID_PATH, code(zip))
        }
        // 重複（同じパス・大文字と小文字だけの違い）
        assertEquals(ProjectDiagnosticCode.ARCHIVE_DUPLICATE_PATH, code(ZipBuilder.valid().add("modules/vector_math.cscr", ZipBuilder.MODULE).build()))
        assertEquals(ProjectDiagnosticCode.ARCHIVE_DUPLICATE_PATH, code(ZipBuilder.valid().add("modules/Vector_math.cscr", ZipBuilder.MODULE).build()))
        assertEquals(ProjectDiagnosticCode.ARCHIVE_DUPLICATE_PATH, code(ZipBuilder.valid().add("README", "a".toByteArray()).add("README", "b".toByteArray()).build()))
    }

    @Test
    fun rejectsLinksAndSpecialFiles() {
        // Unix の種類: シンボリックリンク（0xA000）・ブロックデバイス・FIFO・ソケット。ディレクトリの属性の付いたファイル
        for (mode in listOf(0xA1FFL, 0x61A4L, 0x11A4L, 0xC1A4L, 0x41EDL)) {
            val zip = ZipBuilder.valid().add("LICENSE", "../../etc/passwd".toByteArray()) { external = mode shl 16 }.build()
            assertEquals(ProjectDiagnosticCode.ARCHIVE_SPECIAL_FILE, code(zip), "mode %x".format(mode))
        }
        val dosDirectory = ZipBuilder.valid().add("LICENSE", "x".toByteArray()) { madeBy = 20; external = 0x10 }.build()
        assertEquals(ProjectDiagnosticCode.ARCHIVE_SPECIAL_FILE, code(dosDirectory))
        // modules/ 以外のディレクトリ・中身のあるディレクトリ
        assertEquals(ProjectDiagnosticCode.ARCHIVE_INVALID_PATH, code(ZipBuilder.valid().add("lib/", ByteArray(0)) { external = 0x41EDL shl 16 }.build()))
        assertEquals(ProjectDiagnosticCode.ARCHIVE_MALFORMED, code(ZipBuilder.valid().add("modules/", "data".toByteArray()) { external = 0x41EDL shl 16 }.build()))
    }

    // ---- ZIP の形 ----

    @Test
    fun rejectsUnsupportedFeatures() {
        assertEquals(ProjectDiagnosticCode.ARCHIVE_UNSUPPORTED, code(ZipBuilder.valid().add("LICENSE", "x".toByteArray()) { flags = 0x0001 }.build()))
        assertEquals(ProjectDiagnosticCode.ARCHIVE_UNSUPPORTED, code(ZipBuilder.valid().add("LICENSE", "x".toByteArray()) { flags = 0x0040 }.build()))
        assertEquals(ProjectDiagnosticCode.ARCHIVE_UNSUPPORTED, code(ZipBuilder.valid().add("LICENSE", "x".toByteArray()) { flags = 0x0008 }.build()))
        assertEquals(ProjectDiagnosticCode.ARCHIVE_UNSUPPORTED, code(ZipBuilder.valid().add("LICENSE", "x".toByteArray()) { flags = 0x0020 }.build()))
        assertEquals(ProjectDiagnosticCode.ARCHIVE_UNSUPPORTED, code(ZipBuilder.valid().add("LICENSE", "x".toByteArray()) { method = 12 }.build()))
        assertEquals(ProjectDiagnosticCode.ARCHIVE_UNSUPPORTED, code(ZipBuilder.valid().add("LICENSE", "x".toByteArray()) { size = 0xFFFFFFFFL }.build()))
        assertEquals(ProjectDiagnosticCode.ARCHIVE_UNSUPPORTED, code(ZipBuilder.valid().also { it.endDisk = 1 }.build()))
        // 名前が UTF-8 の印（ビット 11）は可
        assertNotNull(PackageArchives.read(ZipBuilder.valid().add("LICENSE", "x".toByteArray()) { flags = 0x0800 }.build()).archive)
    }

    @Test
    fun rejectsMalformedLayouts() {
        val valid = ZipBuilder.valid().build()
        assertNotNull(PackageArchives.read(valid).archive)
        val cases = mapOf(
            "empty" to ByteArray(0),
            "not a zip" to "hello".toByteArray(),
            "truncated" to valid.copyOf(valid.size - 5),
            "prepended data (self-extracting)" to ZipBuilder.valid().also { it.prefix = "#!/bin/sh\n".toByteArray() }.build(),
            "gap after an entry" to ZipBuilder.valid().also { it.gapAfterFirst = ByteArray(16) }.build(),
            "data before the central directory" to ZipBuilder.valid().also { it.beforeCentral = ByteArray(8) }.build(),
            "data after the end record" to valid + ByteArray(4),
            "overlapping entries" to ZipBuilder.valid().also { it.overlapSecondWithFirst = true }.build(),
            "local name differs" to ZipBuilder.valid().add("LICENSE", "x".toByteArray()) { localName = "../LICENSE" }.build(),
            "local size differs" to ZipBuilder.valid().add("LICENSE", "x".toByteArray()) { localSize = 5 }.build(),
            "fewer entries in the end record" to ZipBuilder.valid().also { it.endEntries = 1 }.build(),
        )
        for ((label, bytes) in cases) {
            val result = PackageArchives.read(bytes)
            assertNull(result.archive, label)
            val diagnostic = result.diagnostics.single().diagnostic.code
            assertTrue(diagnostic == ProjectDiagnosticCode.ARCHIVE_MALFORMED, "$label: $diagnostic")
        }
        // コメントはファイルの終わりまでなら可
        assertNotNull(PackageArchives.read(ZipBuilder.valid().also { it.comment = "made by a tool".toByteArray() }.build()).archive)
    }

    // ---- 大きさ・ZIP 爆弾 ----

    @Test
    fun limitsSizes() {
        assertEquals(ProjectDiagnosticCode.ARCHIVE_TOO_LARGE, code(ByteArray(PackageArchives.MAX_ARCHIVE_BYTES + 1)))
        // 項目の数
        val many = ZipBuilder.valid()
        repeat(PackageArchives.MAX_ENTRIES - 1) { many.add("modules/m$it.cscr", ByteArray(0)) }
        assertEquals(ProjectDiagnosticCode.ARCHIVE_TOO_MANY_FILES, code(many.build()))
        // 宣言された大きさ（.cscr は 3,000,000 バイト、そのほかは 1 MiB まで）。展開の前に止める
        assertEquals(ProjectDiagnosticCode.ARCHIVE_FILE_TOO_LARGE, code(ZipBuilder.valid().add("modules/big.cscr", ByteArray(10)) { method = 8; size = ProjectRules.MAX_SOURCE_BYTES + 1L }.build()))
        assertEquals(ProjectDiagnosticCode.ARCHIVE_FILE_TOO_LARGE, code(ZipBuilder.valid().add("README", ByteArray(PackageArchives.MAX_OTHER_FILE_BYTES + 1)).build()))
        // 展開後の合計（16 MiB）
        val total = ZipBuilder.valid()
        repeat(6) { total.add("modules/part$it.cscr", ByteArray(3_000_000) { 'a'.code.toByte() }) }
        assertEquals(ProjectDiagnosticCode.ARCHIVE_TOTAL_TOO_LARGE, code(total.build()))
        // 圧縮率（100 倍を超える: 2,000,000 個の 0 は数 KB になる）
        assertEquals(ProjectDiagnosticCode.ARCHIVE_COMPRESSION_RATIO, code(ZipBuilder.valid().add("modules/zeros.cscr", ByteArray(2_000_000)) { method = 8 }.build()))
        // 宣言より大きく展開される中身（大きさを偽った爆弾）・壊れた DEFLATE・CRC の不一致
        // （乱数のバイト列は圧縮されにくいため、圧縮率の上限には当たらない）
        assertEquals(ProjectDiagnosticCode.ARCHIVE_CORRUPT_ENTRY, code(ZipBuilder.valid().add("modules/lie.cscr", Random(1).nextBytes(5000)) { method = 8; size = 100 }.build()))
        assertEquals(ProjectDiagnosticCode.ARCHIVE_CORRUPT_ENTRY, code(ZipBuilder.valid().add("modules/short.cscr", Random(2).nextBytes(500)) { method = 8; size = 900 }.build()))
        assertEquals(ProjectDiagnosticCode.ARCHIVE_CORRUPT_ENTRY, code(ZipBuilder.valid().add("LICENSE", "x".toByteArray()) { crc = 1 }.build()))
        assertEquals(ProjectDiagnosticCode.ARCHIVE_MALFORMED, code(ZipBuilder.valid().add("LICENSE", "abc".toByteArray()) { size = 2 }.build()))
    }

    // ---- 中身 ----

    @Test
    fun checksContents() {
        assertEquals(ProjectDiagnosticCode.ARCHIVE_MISSING_MANIFEST, code(ZipBuilder().add("modules/vector_math.cscr", ZipBuilder.MODULE).build()))
        val application = """{"schemaVersion": 2, "kind": "application", "name": "app", "version": "1.0.0", "entry": "modules/vector_math.cscr"}""".toByteArray()
        assertEquals(ProjectDiagnosticCode.ARCHIVE_NOT_A_LIBRARY, code(ZipBuilder().add("createscript.json", application).add("modules/vector_math.cscr", ZipBuilder.MODULE).build()))
        val schemaOne = """{"schemaVersion": 1, "name": "app", "version": "1.0.0", "entry": "modules/vector_math.cscr"}""".toByteArray()
        assertEquals(ProjectDiagnosticCode.ARCHIVE_NOT_A_LIBRARY, code(ZipBuilder().add("createscript.json", schemaOne).add("modules/vector_math.cscr", ZipBuilder.MODULE).build()))
        // 名前・版が、インストールを指示したものと違う
        val valid = ZipBuilder.valid().build()
        assertEquals(ProjectDiagnosticCode.ARCHIVE_NAME_MISMATCH, code(valid, name = "vector-maths"))
        assertEquals(ProjectDiagnosticCode.ARCHIVE_VERSION_MISMATCH, code(valid, name = "vector-math", version = "1.2.1"))
        val mismatch = PackageArchives.read(valid, "vector-math", "1.3.0").diagnostics.single()
        assertEquals(listOf("1.3.0", "1.2.0"), mismatch.diagnostic.arguments)
        assertEquals("createscript.json", mismatch.path)
        // modules のファイルが無い
        assertEquals(ProjectDiagnosticCode.ARCHIVE_MISSING_MODULE, code(ZipBuilder().add("createscript.json", ZipBuilder.MANIFEST).build()))
        // 正しくないマニフェストは、その診断（ファイルは createscript.json）
        val broken = PackageArchives.read(ZipBuilder().add("createscript.json", "{".toByteArray()).build()).diagnostics.single()
        assertEquals(ProjectDiagnosticCode.JSON_SYNTAX, broken.diagnostic.code)
        assertEquals("createscript.json", broken.path)
        // .cscr: UTF-8 でない・BOM・長すぎる
        for ((contents, reason) in listOf(byteArrayOf(0xC3.toByte(), 0x28) to "UTF-8", byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 'x'.code.toByte()) to "byte order mark")) {
            val result = PackageArchives.read(ZipBuilder.valid().add("modules/bad.cscr", contents).build()).diagnostics.single()
            assertEquals(ProjectDiagnosticCode.ARCHIVE_INVALID_SOURCE, result.diagnostic.code)
            assertTrue(result.diagnostic.arguments[1].contains(reason), "${result.diagnostic.arguments}")
        }
        val longest = ByteArray(ProjectRules.MAX_SOURCE_LENGTH) { 'a'.code.toByte() }
        assertNotNull(PackageArchives.read(ZipBuilder.valid().add("modules/long.cscr", longest).build()).archive)
        // 1,000,001 文字（3 バイトの文字で 3,000,000 バイト以内でも、文字数で止める）
        val wide = "あ".repeat(999_999).toByteArray() + "aa".toByteArray()
        assertTrue(wide.size <= ProjectRules.MAX_SOURCE_BYTES)
        assertEquals(ProjectDiagnosticCode.ARCHIVE_INVALID_SOURCE, PackageArchives.read(ZipBuilder.valid().add("modules/wide.cscr", wide).build()).diagnostics.single().diagnostic.code)
        // 説明のファイルは、中身を問わない（テキストとして扱わない）
        assertNotNull(PackageArchives.read(ZipBuilder.valid().add("NOTICE.txt", byteArrayOf(0xFF.toByte(), 0x00)).build()).archive)
    }

    // 正しいアーカイブを壊したもの（1〜4 バイトの書き換え・切り詰め）。例外を漏らさず、検査に通るか診断を返す
    @Test
    fun corruptedArchivesNeverThrow() {
        val valid = PackageArchives.write(files())
        val random = Random(82)
        var rejected = 0
        repeat(20_000) { iteration ->
            val input = if (iteration % 5 == 0) {
                valid.copyOf(random.nextInt(valid.size))
            } else {
                valid.copyOf().also { copy -> repeat(1 + random.nextInt(4)) { copy[random.nextInt(copy.size)] = random.nextInt(256).toByte() } }
            }
            val result = PackageArchives.read(input)
            assertTrue(result.archive != null || result.diagnostics.isNotEmpty())
            if (result.archive == null) rejected++
        }
        assertTrue(rejected > 10_000, "$rejected")
    }
}
