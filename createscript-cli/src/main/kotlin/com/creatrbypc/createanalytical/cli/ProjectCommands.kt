package com.creatrbypc.createanalytical.cli

import com.creatrbypc.createanalytical.project.Lockfile
import com.creatrbypc.createanalytical.project.Lockfiles
import com.creatrbypc.createanalytical.project.Manifest
import com.creatrbypc.createanalytical.project.ManifestResult
import com.creatrbypc.createanalytical.project.Manifests
import com.creatrbypc.createanalytical.project.PackageArchives
import com.creatrbypc.createanalytical.project.PackageKind
import com.creatrbypc.createanalytical.project.ProjectDiagnostic
import com.creatrbypc.createanalytical.project.ProjectDiagnosticCode
import com.creatrbypc.createanalytical.project.ProjectRules
import java.io.IOException
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

// 作ったファイルの同一性（S8.1 の追加検証）。fileKey はファイルシステムの識別子（macOS・Linux ではデバイス番号と i ノード番号。取得できない環境では null）、
// size と sha256 は書いた内容。ロールバックで、別のプロセスが差し替えたファイルを消さないために使う。
// holder は、作ったファイルを開いたままにしている記述子（fileKey があるときだけ）。開いている間は i ノードが解放されないので、
// 別のプロセスが消して作り直したファイルは、内容が同じでも必ず別の i ノード番号になる（Linux は解放された番号をすぐ使い回すため、
// 閉じていると見分けられない）。照合と削除を終えたら close で閉じる
internal class FileIdentity(val fileKey: Any?, val size: Long, val sha256: ByteArray, private val holder: AutoCloseable? = null) : AutoCloseable {
    override fun close() {
        try {
            holder?.close()
        } catch (error: Exception) {
            // 閉じられなくても、書いたファイルには影響しない
        }
    }
}

// ファイルを壊さずに書く・読む手順（S8.1）
internal object SafeFiles {
    // 新しいファイルとして書き、作ったファイルの同一性を返す。同じ名前のファイルがあれば（書き始めた後に作られたものも）上書きせずに FileAlreadyExistsException。
    // 同じディレクトリの一時ファイルに書いて内容をディスクに書き出し（force）、ハードリンクで名前を付ける（リンクの作成は、名前が既にあれば失敗する。
    // 途中で止まっても、目的の名前に書きかけのファイルは残らない）。ハードリンクを作れないファイルシステムでは、置き換えない移動を使う。
    // 一時ファイルは CREATE_NEW で作る（Files.createTempFile は所有者だけが読める 0600 で作るため使わない。権限は通常のファイルと同じく umask に従う）。
    // fileKey は名前を付ける前の一時ファイルのもの（ハードリンク・同じファイルシステムの中の移動では、目的のファイルと同じ）。
    // fileKey があるときは、書いた記述子を閉じずに返す（FileIdentity の holder。使い終えたら close する）
    fun writeNew(target: Path, text: String): FileIdentity = writeNew(target, text.toByteArray(Charsets.UTF_8))

    fun writeNew(target: Path, bytes: ByteArray): FileIdentity {
        val directory = target.toAbsolutePath().parent
        val temporary = directory.resolve(".createscript-${UUID.randomUUID()}.tmp")
        var channel: FileChannel? = null
        try {
            channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
            val fileKey = Files.readAttributes(temporary, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
            // fileKey が無い環境（Windows など）では、これまでどおり名前を付ける前に閉じる
            if (fileKey == null) {
                channel.close()
                channel = null
            }
            try {
                Files.createLink(target, temporary)
            } catch (error: FileAlreadyExistsException) {
                throw error
            } catch (error: UnsupportedOperationException) {
                Files.move(temporary, target)
            } catch (error: IOException) {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw FileAlreadyExistsException(target.toString())
                Files.move(temporary, target)
            }
            val holder = channel
            channel = null
            return FileIdentity(fileKey, bytes.size.toLong(), sha256(bytes), holder)
        } finally {
            try {
                channel?.close()
            } catch (error: IOException) {
                // 書いた内容は force 済み
            }
            // 名前を付けた後の一時ファイルの削除の失敗は、書き込みの失敗にしない（このメソッドが例外を投げたら、目的のファイルは作っていない）
            try {
                Files.deleteIfExists(temporary)
            } catch (error: IOException) {
                // 一時ファイル（.createscript-*.tmp）が残るだけ
            }
        }
    }

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    // ファイルを消してよいかを確かめてから消す。消さなかった理由を返す（消したか、既に無ければ null）。
    // リンクをたどらずに属性を読み、通常のファイルで、大きさ・内容（SHA-256）が同じで、fileKey が両方とも取得できるなら同じときだけ消す。
    // fileKey が取得できない環境（どちらかが null）では、大きさと内容だけで判断する（内容の同じ別のファイルへの差し替えは見分けられない）。
    // 確かめてから消すまでの間に差し替えられる競合（TOCTOU）は、Java のファイルの API（名前で消す）では完全には無くせない
    fun deleteIfSame(path: Path, identity: FileIdentity): String? {
        val attributes = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (error: NoSuchFileException) {
            return null
        } catch (error: IOException) {
            return "cannot read its attributes"
        }
        if (!attributes.isRegularFile) return "it was replaced by something that is not a regular file"
        val key = attributes.fileKey()
        if (identity.fileKey != null && key != null && key != identity.fileKey) return "it was replaced by another file"
        if (attributes.size() != identity.size) return "its contents changed"
        val contents = try {
            readLimited(path, identity.size.toInt())
        } catch (error: IOException) {
            return "cannot read it"
        }
        if (!sha256(contents).contentEquals(identity.sha256)) return "its contents changed"
        return try {
            Files.delete(path)
            null
        } catch (error: NoSuchFileException) {
            null
        } catch (error: IOException) {
            "cannot delete it"
        }
    }

    // 作ったディレクトリを、同じディレクトリ（fileKey が両方とも取得できるなら同じ）で空のときだけ消す。消さなかった理由を返す
    fun deleteDirectoryIfSame(path: Path, fileKey: Any?): String? {
        val attributes = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (error: NoSuchFileException) {
            return null
        } catch (error: IOException) {
            return "cannot read its attributes"
        }
        if (!attributes.isDirectory) return "it was replaced by something that is not a directory"
        val key = attributes.fileKey()
        if (fileKey != null && key != null && key != fileKey) return "it was replaced by another directory"
        return try {
            Files.delete(path)
            null
        } catch (error: NoSuchFileException) {
            null
        } catch (error: java.nio.file.DirectoryNotEmptyException) {
            "it is not empty"
        } catch (error: IOException) {
            "cannot delete it"
        }
    }

    // 最大 maxBytes + 1 バイトまで読む（大きすぎるファイルを読み切らない。大きすぎることは呼び出し側の検査で分かる）。
    // リンクをたどらずに開く（開く直前にシンボリックリンクに差し替えられたら失敗する）
    fun readLimited(path: Path, maxBytes: Int): ByteArray =
        Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(maxBytes + 1) }
}

// createscript init・manifest check・lock check（S8.1）。マニフェスト・ロックファイルの検査は createscript-project が行い、ここではファイルの読み書きと、
// ファイルがプロジェクトのルートの中にあることを確かめる。write はファイルを書く手順（テストで失敗・差し替えを起こすために差し替える）
internal class ProjectCommands(
    private val out: PrintStream,
    private val err: PrintStream,
    private val write: (Path, String) -> FileIdentity = SafeFiles::writeNew,
) {
    class Failure(val code: Int, message: String) : Exception(message)

    // 今回作ったもの（ロールバックで消す候補）
    private sealed interface Created {
        val path: Path

        // 照合と削除を終えたら閉じる（作ったものを開いたままにしている記述子。FileIdentity の holder と同じ理由）
        fun release()

        class File(override val path: Path, val identity: FileIdentity) : Created {
            override fun release() = identity.close()
        }

        class Directory(override val path: Path, val fileKey: Any?, private val holder: AutoCloseable? = null) : Created {
            override fun release() {
                try {
                    holder?.close()
                } catch (error: Exception) {
                    // 閉じられなくても、作ったディレクトリには影響しない
                }
            }
        }
    }

    companion object {
        const val ENTRY = "src/main.cscr"
        const val INITIAL_VERSION = "0.1.0"
        const val HELLO = "println(\"Hello, CreateScript!\")\n"

        // library のモジュールのディレクトリ（プロジェクト・パッケージアーカイブで同じ。S8.2 Phase 1）
        const val MODULES_DIRECTORY = "modules"

        // init が、.gitignore が無いときだけ作る内容（S8.2 Phase 4）。インストールしたパッケージ（.createscript/）をコミットしないため
        const val GITIGNORE = ".gitignore"
        const val GITIGNORE_CONTENTS = "/.createscript/\n"
    }

    // createscript run のプロジェクトモードに使うもの（S8.2 Phase 4）。root はプロジェクトのルート、shownEntry はエントリーのファイルの表示用のパス
    // （作業ディレクトリからのパスとしても使える）、lock は依存が無くロックファイルも無ければ null
    class RunnableProject(val root: Path, val shownRoot: String?, val manifest: Manifest, val lock: Lockfile?, val shownEntry: String)

    // ---- init ----

    // createscript init [ディレクトリ] [--name 名前]。createscript.json と src/main.cscr を作る（ロックファイルは作らない）。
    // どちらかが既にあれば何も作らない。途中で失敗したら、今回作ったファイル・ディレクトリだけを、作ったときと同じものであることを確かめてから消す
    // （前からあったもの・別のプロセスが差し替えたものには触れない）
    fun init(directoryArgument: String?, nameOption: String?): Int {
        val directory = path(directoryArgument ?: ".")
        val absolute = directory.toAbsolutePath().normalize()
        val name = nameOption ?: absolute.fileName?.toString()
            ?: throw Failure(ExitCode.USAGE, "cannot use the directory name as a package name; use --name <name>")
        if (!ProjectRules.isValidName(name)) {
            val hint = if (nameOption == null) " (from the directory name); use --name <name>" else ""
            if (ProjectRules.isWindowsReserved(name)) throw Failure(ExitCode.USAGE, "invalid package name: $name$hint; ${ProjectRules.reservedNameReason(name)}")
            throw Failure(ExitCode.USAGE, "invalid package name: $name$hint (lower-case letters, digits and single '-', starting with a letter, at most ${ProjectRules.MAX_NAME_LENGTH} characters)")
        }
        val manifestPath = directory.resolve(Manifests.FILE_NAME)
        val entryPath = directory.resolve(ENTRY)
        for (existing in listOf(manifestPath, entryPath)) {
            if (Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) throw Failure(ExitCode.CANNOT_CREATE, "$existing already exists; nothing was created")
        }
        if (Files.exists(directory) && !Files.isDirectory(directory)) throw Failure(ExitCode.CANNOT_CREATE, "$directory is not a directory; nothing was created")
        // src がシンボリックリンク（プロジェクトの外を指しうる）か、ディレクトリでなければ、何も作らない（リンクの先に書かない）
        val sourceDirectory = entryPath.parent
        if (Files.isSymbolicLink(sourceDirectory)) throw Failure(ExitCode.CANNOT_CREATE, "$sourceDirectory is a symbolic link; nothing was created")
        if (Files.exists(sourceDirectory, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(sourceDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw Failure(ExitCode.CANNOT_CREATE, "$sourceDirectory is not a directory; nothing was created")
        }
        val created = ArrayList<Created>()
        val gitignorePath = directory.resolve(GITIGNORE)
        var gitignoreCreated = false
        try {
            createDirectories(entryPath.parent, created)
            created.add(Created.File(manifestPath, write(manifestPath, Manifests.write(Manifest(name, INITIAL_VERSION, ENTRY)))))
            created.add(Created.File(entryPath, write(entryPath, HELLO)))
            // .gitignore は無いときだけ作る（S8.2 Phase 4）。既にあるもの（シンボリックリンク・ディレクトリを含む）には触れない。
            // 確かめた後に作られていたら（置き換えない書き込みが失敗する）、それも残して作らない
            if (!Files.exists(gitignorePath, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    created.add(Created.File(gitignorePath, write(gitignorePath, GITIGNORE_CONTENTS)))
                    gitignoreCreated = true
                } catch (error: FileAlreadyExistsException) {
                    // 別のプロセスが作った .gitignore を使う
                }
            }
        } catch (error: Exception) {
            val kept = rollBack(created)
            val reason = if (error is FileAlreadyExistsException) "${error.file} already exists" else (error.message ?: error.javaClass.simpleName)
            throw Failure(ExitCode.CANNOT_CREATE, "cannot create the project: $reason; " + if (kept.isEmpty()) "nothing was left behind" else "kept: ${kept.joinToString()}")
        } finally {
            // ロールバックの照合と削除を終えた後（成功したときは作り終えた後）に、開いたままにしていた記述子を閉じる
            for (item in created) item.release()
        }
        out.println("Created $manifestPath")
        out.println("Created $entryPath")
        if (gitignoreCreated) out.println("Created $gitignorePath")
        out.println("Run it with: createscript run $entryPath")
        return ExitCode.OK
    }

    // 無いディレクトリを外側から作り、作ったもの（と、その fileKey）を記録する。
    // fileKey があるときは、作ったディレクトリを開いたままにする（i ノードを解放させず、消して作り直されたものを別の番号にする）。
    // 開く前と後で fileKey が違えば（その間に差し替えられた）、どの fileKey とも一致しない印を記録する（ロールバックで消さない）
    private fun createDirectories(directory: Path, created: MutableList<Created>) {
        val missing = ArrayList<Path>()
        var current: Path? = directory.toAbsolutePath().normalize()
        while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            missing.add(current)
            current = current.parent
        }
        for (path in missing.asReversed()) {
            Files.createDirectory(path)
            val fileKey = directoryKey(path)
            if (fileKey == null) {
                created.add(Created.Directory(path, null))
                continue
            }
            val holder = try {
                Files.newDirectoryStream(path)
            } catch (error: IOException) {
                null
            }
            val same = holder != null && directoryKey(path) == fileKey
            created.add(Created.Directory(path, if (same) fileKey else Any(), holder))
        }
    }

    private fun directoryKey(path: Path): Any? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
    } catch (error: IOException) {
        null
    }

    // 作った順の逆に、作ったときと同じものであることを確かめてから消す。消さなかったものを、理由とともに返す
    private fun rollBack(created: List<Created>): List<String> = created.asReversed().mapNotNull { item ->
        val reason = when (item) {
            is Created.File -> SafeFiles.deleteIfSame(item.path, item.identity)
            is Created.Directory -> SafeFiles.deleteDirectoryIfSame(item.path, item.fileKey)
        }
        reason?.let { "${item.path} ($it)" }
    }

    // ---- pack（S8.2 Phase 1） ----

    // createscript pack [ディレクトリ] [--output ファイル]。library のプロジェクトから、パッケージアーカイブ（既定はプロジェクトの直下の createscript-package.zip）を作る。
    // 入れるのは createscript.json・modules/*.cscr・直下の説明のファイル（LICENSE など）だけで、どれもシンボリックリンクでない通常のファイルでなければならない
    // （リンクの先・ほかのファイルを入れない）。作ったアーカイブは、インストールと同じ検査（PackageArchives.read）で確かめてから、既存のファイルを上書きせずに書く。
    // コードは実行しない（コンパイルもしない）
    fun pack(directoryArgument: String?, outputOption: String?): Int {
        val root = projectRoot(directoryArgument)
        val loaded = loadManifest(root) ?: return ExitCode.INVALID_INPUT
        val manifest = loaded.manifest!!
        val shownManifest = shown(root, Manifests.FILE_NAME)
        if (manifest.kind != PackageKind.LIBRARY) {
            throw Failure(ExitCode.INVALID_INPUT, "$shownManifest is not a library; createscript pack needs \"schemaVersion\": 2 and \"kind\": \"library\"")
        }
        val files = java.util.TreeMap<String, ByteArray>()
        files[Manifests.FILE_NAME] = readProjectFile(root, Manifests.FILE_NAME, shownManifest, Manifests.MAX_BYTES) ?: return ExitCode.INVALID_INPUT
        val problems = ArrayList<String>()
        // modules/: シンボリックリンクでないディレクトリで、中は modules/名前.cscr の通常のファイルだけ
        val modulesDirectory = root.resolve(MODULES_DIRECTORY)
        if (Files.isSymbolicLink(modulesDirectory)) throw Failure(ExitCode.INVALID_INPUT, "${shown(root, MODULES_DIRECTORY)} is a symbolic link; it is not packed")
        val entries = Files.list(modulesDirectory).use { stream -> stream.map { it.fileName.toString() }.sorted().toList() }
        for (fileName in entries) {
            val relative = "$MODULES_DIRECTORY/$fileName"
            val path = root.resolve(relative)
            val reason = when {
                Files.isSymbolicLink(path) -> "symbolic links are not packed"
                !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> "only regular files are packed"
                else -> PackageArchives.pathProblem(relative)
            }
            if (reason != null) {
                problems += ProjectDiagnostic(ProjectDiagnosticCode.ARCHIVE_INVALID_PATH, 1, 1, listOf(relative, reason)).format(shown(root, relative))
                continue
            }
            val bytes = readProjectFile(root, relative, shown(root, relative), PackageArchives.MAX_OTHER_FILE_BYTES.coerceAtLeast(ProjectRules.MAX_SOURCE_BYTES)) ?: return ExitCode.INVALID_INPUT
            PackageArchives.sourceProblem(bytes)?.let { problems += ProjectDiagnostic(ProjectDiagnosticCode.ARCHIVE_INVALID_SOURCE, 1, 1, listOf(relative, it)).format(shown(root, relative)) }
            files[relative] = bytes
        }
        // 直下の説明のファイル（あるものだけ）
        for (document in listOf("LICENSE", "LICENCE", "NOTICE", "README", "CHANGELOG", "COPYING").flatMap { listOf(it, "$it.md", "$it.txt") }) {
            val path = root.resolve(document)
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) continue
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                problems += ProjectDiagnostic(ProjectDiagnosticCode.ARCHIVE_INVALID_PATH, 1, 1, listOf(document, "only regular files that are not symbolic links are packed")).format(shown(root, document))
                continue
            }
            val bytes = readProjectFile(root, document, shown(root, document), PackageArchives.MAX_OTHER_FILE_BYTES) ?: return ExitCode.INVALID_INPUT
            if (bytes.size > PackageArchives.MAX_OTHER_FILE_BYTES) {
                problems += ProjectDiagnostic(ProjectDiagnosticCode.ARCHIVE_FILE_TOO_LARGE, 1, 1, listOf(document, PackageArchives.MAX_OTHER_FILE_BYTES.toString())).format(shown(root, document))
                continue
            }
            files[document] = bytes
        }
        if (problems.isNotEmpty()) {
            problems.forEach(err::println)
            return ExitCode.INVALID_INPUT
        }
        val archive = PackageArchives.write(files)
        // インストールと同じ検査（大きさ・数・パス・マニフェスト・ソース）
        val checked = PackageArchives.read(archive, manifest.name, manifest.version)
        if (checked.archive == null) {
            checked.diagnostics.forEach { err.println(it.diagnostic.format(it.path ?: PackageArchives.FILE_NAME)) }
            return ExitCode.INVALID_INPUT
        }
        val output = outputOption?.let(::path) ?: root.resolve(PackageArchives.FILE_NAME)
        try {
            SafeFiles.writeNew(output, archive).close()
        } catch (error: FileAlreadyExistsException) {
            throw Failure(ExitCode.CANNOT_CREATE, "$output already exists; remove it or choose another file with --output")
        } catch (error: IOException) {
            throw Failure(ExitCode.CANNOT_CREATE, "cannot write $output: ${error.message ?: error.javaClass.simpleName}")
        }
        val hex = SafeFiles.sha256(archive).joinToString("") { "%02x".format(it) }
        out.println("Packed ${manifest.name} ${manifest.version}: $output (${files.size} files, ${archive.size} bytes)")
        out.println("sha256 $hex")
        return ExitCode.OK
    }

    // ---- manifest check / lock check ----

    // createscript manifest check [ディレクトリ]
    fun checkManifest(directoryArgument: String?): Int {
        val root = projectRoot(directoryArgument)
        val loaded = loadManifest(root) ?: return ExitCode.INVALID_INPUT
        val manifest = loaded.manifest!!
        val contents = manifest.entry?.let { "entry $it" } ?: "library"
        val modules = if (manifest.modules.isEmpty()) "" else ", modules ${manifest.modules.joinToString(" ")}"
        out.println("${shown(root, Manifests.FILE_NAME)}: ok (${manifest.name} ${manifest.version}, $contents$modules, ${manifest.dependencies.size} dependencies)")
        return ExitCode.OK
    }

    // createscript lock check [ディレクトリ]。依存が無くロックファイルも無いなら問題なし。依存があるのにロックファイルが無ければエラー
    fun checkLock(directoryArgument: String?): Int {
        val root = projectRoot(directoryArgument)
        val loaded = loadManifest(root) ?: return ExitCode.INVALID_INPUT
        val manifest = loaded.manifest!!
        val shownLock = shown(root, Lockfiles.FILE_NAME)
        val (ok, lock) = loadLock(root, manifest)
        if (!ok) return ExitCode.INVALID_INPUT
        if (lock == null) {
            out.println("$shownLock: not needed (no dependencies)")
            return ExitCode.OK
        }
        out.println("$shownLock: ok (${lock.packages.size} packages, consistent with ${Manifests.FILE_NAME})")
        return ExitCode.OK
    }

    // ロックファイルを読み、正規形・マニフェストとの整合を確かめる（lock check と run のプロジェクトモードで同じ）。
    // 依存が無くロックファイルも無ければ (true, null)。問題があれば報告して (false, null)
    private fun loadLock(root: Path, manifest: Manifest): Pair<Boolean, Lockfile?> {
        val lockPath = root.resolve(Lockfiles.FILE_NAME)
        val shownLock = shown(root, Lockfiles.FILE_NAME)
        if (!Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            if (manifest.dependencies.isEmpty()) return true to null
            report(shownLock, listOf(ProjectDiagnostic(ProjectDiagnosticCode.LOCK_MISSING, 1, 1, listOf(Lockfiles.FILE_NAME))))
            return false to null
        }
        val bytes = readProjectFile(root, Lockfiles.FILE_NAME, shownLock, Lockfiles.MAX_BYTES) ?: return false to null
        val parsed = Lockfiles.parse(bytes)
        val diagnostics = Lockfiles.check(bytes, parsed, manifest)
        if (diagnostics.isNotEmpty()) {
            report(shownLock, diagnostics, parsed.truncated)
            return false to null
        }
        return true to parsed.lockfile!!
    }

    // ---- run のプロジェクトモード（S8.2 Phase 4） ----

    // createscript run [ディレクトリ] の準備: manifest check と lock check と同じ検査をし、マニフェストの entry を返す。
    // 中断したインストールがあれば止まる（loadManifest）。問題があれば報告して null（終了コード 65）
    fun projectForRun(directoryArgument: String?): RunnableProject? {
        val root = projectRoot(directoryArgument)
        val loaded = loadManifest(root) ?: return null
        val manifest = loaded.manifest!!
        val entry = manifest.entry ?: throw Failure(
            ExitCode.INVALID_INPUT,
            "${shown(root, Manifests.FILE_NAME)} has no entry; a library cannot be run as a project (run a file with createscript run <file>)",
        )
        val (ok, lock) = loadLock(root, manifest)
        if (!ok) return null
        val shownRoot = root.toString().takeUnless { it.isEmpty() || it == "." }
        return RunnableProject(root, shownRoot, manifest, lock, shown(root, entry))
    }

    // マニフェストを読んで確かめ、エントリーのファイルがルートの中の通常のファイルであることも確かめる。問題があれば報告して null
    private fun loadManifest(root: Path): ManifestResult? {
        // 中断したインストール（S8.2 Phase 3）: マニフェスト・ロックファイルが更新の途中かもしれないので、確かめずに止める
        if (Files.exists(com.creatrbypc.createanalytical.install.InstallLayout.journal(root), LinkOption.NOFOLLOW_LINKS)) {
            throw Failure(
                ExitCode.INVALID_INPUT,
                "an installation was interrupted (${shown(root, ".createscript/transaction.json")} exists); run createscript install to finish or undo it",
            )
        }
        val manifestPath = root.resolve(Manifests.FILE_NAME)
        val shownManifest = shown(root, Manifests.FILE_NAME)
        if (!Files.exists(manifestPath, LinkOption.NOFOLLOW_LINKS)) throw Failure(ExitCode.NO_INPUT, "$shownManifest not found (create one with createscript init)")
        val bytes = readProjectFile(root, Manifests.FILE_NAME, shownManifest, Manifests.MAX_BYTES) ?: return null
        val result = Manifests.parse(bytes)
        val manifest = result.manifest
        if (manifest == null) {
            report(shownManifest, result.diagnostics, result.truncated)
            return null
        }
        manifest.entry?.let { entry ->
            entryProblem(root, entry)?.let { code ->
                report(shownManifest, listOf(ProjectDiagnostic(code, result.entryPosition.first, result.entryPosition.second, listOf(entry))))
                return null
            }
        }
        // library の modules（S8.2 Phase 1）: modules/名前.cscr が、ルートの中の通常のファイルであること（エントリーと同じ規則）
        val missing = manifest.modules.mapNotNull { module ->
            entryProblem(root, "$MODULES_DIRECTORY/$module.cscr")?.let { _ ->
                val position = result.modulePositions[module] ?: (1 to 1)
                ProjectDiagnostic(ProjectDiagnosticCode.MODULE_FILE_NOT_FOUND, position.first, position.second, listOf(module))
            }
        }
        if (missing.isNotEmpty()) {
            report(shownManifest, missing)
            return null
        }
        return result
    }

    // プロジェクトのファイル（createscript.json・createscript.lock）を読む（S8.1 の追加検証）。問題があれば報告して null。
    // - シンボリックリンクは、たどった先がルートの中にあるときだけ使う（外を指せば CS_PKG_FILE_OUTSIDE_PROJECT）。たどれないリンクは、ファイルが無いものとする
    // - たどった先が通常のファイルでなければ（ディレクトリ・FIFO・デバイスなど）CS_PKG_NOT_A_REGULAR_FILE。FIFO は開くと書き手を待って止まるため、開く前に確かめる
    // - たどった先の実際のパスを、リンクをたどらずに開いて最大 上限 + 1 バイトを読み、読む前と後で fileKey・大きさ・更新時刻が同じことを確かめる
    //   （違えば CS_PKG_FILE_CHANGED_WHILE_READING）。確かめてから開くまでの間に FIFO などに差し替えられる競合は、これでも完全には無くせない
    private fun readProjectFile(root: Path, name: String, shown: String, maxBytes: Int): ByteArray? {
        fun problem(code: ProjectDiagnosticCode): ByteArray? {
            report(shown, listOf(ProjectDiagnostic(code, 1, 1, listOf(name))))
            return null
        }
        val path = root.resolve(name)
        val real = try {
            path.toRealPath()
        } catch (error: NoSuchFileException) {
            throw Failure(ExitCode.NO_INPUT, "$shown is a broken symbolic link")
        } catch (error: IOException) {
            throw Failure(ExitCode.NO_INPUT, "cannot read file: $shown")
        }
        val realRoot = try {
            root.toRealPath()
        } catch (error: IOException) {
            throw Failure(ExitCode.NO_INPUT, "cannot read the directory: $root")
        }
        if (!real.startsWith(realRoot)) return problem(ProjectDiagnosticCode.FILE_OUTSIDE_PROJECT)
        val before = attributes(real) ?: throw Failure(ExitCode.NO_INPUT, "cannot read file: $shown")
        if (!before.isRegularFile) return problem(ProjectDiagnosticCode.NOT_A_REGULAR_FILE)
        val bytes = try {
            SafeFiles.readLimited(real, maxBytes)
        } catch (error: IOException) {
            // リンクに差し替えられたもの（NOFOLLOW_LINKS で開けない）を含む
            return problem(ProjectDiagnosticCode.FILE_CHANGED_WHILE_READING)
        }
        val after = attributes(real)
        val same = after != null && after.isRegularFile && after.fileKey() == before.fileKey() && after.size() == before.size() &&
            after.lastModifiedTime() == before.lastModifiedTime()
        if (!same) return problem(ProjectDiagnosticCode.FILE_CHANGED_WHILE_READING)
        return bytes
    }

    private fun attributes(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (error: IOException) {
        null
    }

    // エントリーのファイル: シンボリックリンクをたどった先がルートの中の通常のファイルであること（パスの字面は createscript-project が確かめ済み）
    private fun entryProblem(root: Path, entry: String): ProjectDiagnosticCode? {
        val path = root.resolve(entry)
        if (!Files.exists(path)) return ProjectDiagnosticCode.ENTRY_NOT_FOUND
        val real = try {
            path.toRealPath()
        } catch (error: IOException) {
            return ProjectDiagnosticCode.ENTRY_NOT_FOUND
        }
        if (!real.startsWith(root.toRealPath())) return ProjectDiagnosticCode.ENTRY_OUTSIDE_PROJECT
        if (!Files.isRegularFile(real)) return ProjectDiagnosticCode.ENTRY_NOT_A_FILE
        return null
    }

    fun projectRoot(directoryArgument: String?): Path {
        val root = path(directoryArgument ?: ".")
        if (!Files.isDirectory(root)) throw Failure(ExitCode.NO_INPUT, "not a directory: $root")
        return root
    }

    private fun report(file: String, diagnostics: List<ProjectDiagnostic>, truncated: Boolean = false) {
        diagnostics.forEach { err.println(it.format(file)) }
        if (truncated) err.println("$file: more diagnostics were omitted")
    }

    // 診断に表示するファイルの名前（指定したディレクトリからの形。指定が無ければファイル名だけ）
    private fun shown(root: Path, file: String): String = if (root.toString().isEmpty() || root.toString() == ".") file else root.resolve(file).toString()

    private fun path(text: String): Path = try {
        Paths.get(text)
    } catch (error: InvalidPathException) {
        throw Failure(ExitCode.USAGE, "invalid path: $text")
    }
}
