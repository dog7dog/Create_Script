package com.creatrbypc.createanalytical.cli

import com.creatrbypc.createanalytical.project.Lockfiles
import com.creatrbypc.createanalytical.project.Manifest
import com.creatrbypc.createanalytical.project.ManifestResult
import com.creatrbypc.createanalytical.project.Manifests
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
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.UUID

// ファイルを壊さずに書く手順（S8.1）
internal object SafeFiles {
    // 新しいファイルとして書く。同じ名前のファイルがあれば（書き始めた後に作られたものも）上書きせずに FileAlreadyExistsException。
    // 同じディレクトリの一時ファイルに書いて内容をディスクに書き出し（force）、ハードリンクで名前を付ける（リンクの作成は、名前が既にあれば失敗する。
    // 途中で止まっても、目的の名前に書きかけのファイルは残らない）。ハードリンクを作れないファイルシステムでは、置き換えない移動を使う。
    // 一時ファイルは CREATE_NEW で作る（Files.createTempFile は所有者だけが読める 0600 で作るため使わない。権限は通常のファイルと同じく umask に従う）
    fun writeNew(target: Path, text: String) {
        val directory = target.toAbsolutePath().parent
        val temporary = directory.resolve(".createscript-${UUID.randomUUID()}.tmp")
        try {
            FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
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
        } finally {
            // 名前を付けた後の一時ファイルの削除の失敗は、書き込みの失敗にしない（このメソッドが例外を投げたら、目的のファイルは作っていない）
            try {
                Files.deleteIfExists(temporary)
            } catch (error: IOException) {
                // 一時ファイル（.createscript-*.tmp）が残るだけ
            }
        }
    }

    // 最大 maxBytes + 1 バイトまで読む（大きすぎるファイルを読み切らない。大きすぎることは呼び出し側の検査で分かる）
    fun readLimited(path: Path, maxBytes: Int): ByteArray = Files.newInputStream(path).use { it.readNBytes(maxBytes + 1) }
}

// createscript init・manifest check・lock check（S8.1）。マニフェスト・ロックファイルの検査は createscript-project が行い、ここではファイルの読み書きと、
// エントリーのファイルがプロジェクトのルートの中にあることを確かめる。write はファイルを書く手順（テストで失敗を起こすために差し替える）
internal class ProjectCommands(
    private val out: PrintStream,
    private val err: PrintStream,
    private val write: (Path, String) -> Unit = SafeFiles::writeNew,
) {
    class Failure(val code: Int, message: String) : Exception(message)

    companion object {
        const val ENTRY = "src/main.cscr"
        const val INITIAL_VERSION = "0.1.0"
        const val HELLO = "println(\"Hello, CreateScript!\")\n"
    }

    // ---- init ----

    // createscript init [ディレクトリ] [--name 名前]。createscript.json と src/main.cscr を作る（ロックファイルは作らない）。
    // どちらかが既にあれば何も作らない。途中で失敗したら、今回作ったファイル・ディレクトリだけを消す（前からあったものには触れない）
    fun init(directoryArgument: String?, nameOption: String?): Int {
        val directory = path(directoryArgument ?: ".")
        val absolute = directory.toAbsolutePath().normalize()
        val name = nameOption ?: absolute.fileName?.toString()
            ?: throw Failure(ExitCode.USAGE, "cannot use the directory name as a package name; use --name <name>")
        if (!ProjectRules.isValidName(name)) {
            val hint = if (nameOption == null) " (from the directory name); use --name <name>" else ""
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
        val created = ArrayList<Path>()
        try {
            createDirectories(entryPath.parent, created)
            write(manifestPath, Manifests.write(Manifest(name, INITIAL_VERSION, ENTRY)))
            created.add(manifestPath)
            write(entryPath, HELLO)
            created.add(entryPath)
        } catch (error: Exception) {
            val kept = rollBack(created)
            val reason = if (error is FileAlreadyExistsException) "${error.file} already exists" else (error.message ?: error.javaClass.simpleName)
            throw Failure(ExitCode.CANNOT_CREATE, "cannot create the project: $reason; " + if (kept.isEmpty()) "nothing was left behind" else "could not remove: ${kept.joinToString()}")
        }
        out.println("Created $manifestPath")
        out.println("Created $entryPath")
        out.println("Run it with: createscript run $entryPath")
        return ExitCode.OK
    }

    // 無いディレクトリを外側から作り、作ったものを記録する
    private fun createDirectories(directory: Path, created: MutableList<Path>) {
        val missing = ArrayList<Path>()
        var current: Path? = directory.toAbsolutePath().normalize()
        while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            missing.add(current)
            current = current.parent
        }
        for (path in missing.asReversed()) {
            Files.createDirectory(path)
            created.add(path)
        }
    }

    // 作った順の逆に消す（ディレクトリは空のときだけ）。消せなかったものを返す
    private fun rollBack(created: List<Path>): List<Path> = created.asReversed().filter { path ->
        try {
            Files.deleteIfExists(path)
            false
        } catch (error: IOException) {
            true
        }
    }

    // ---- manifest check / lock check ----

    // createscript manifest check [ディレクトリ]
    fun checkManifest(directoryArgument: String?): Int {
        val root = projectRoot(directoryArgument)
        val loaded = loadManifest(root) ?: return ExitCode.INVALID_INPUT
        val manifest = loaded.manifest!!
        out.println("${shown(root, Manifests.FILE_NAME)}: ok (${manifest.name} ${manifest.version}, entry ${manifest.entry}, ${manifest.dependencies.size} dependencies)")
        return ExitCode.OK
    }

    // createscript lock check [ディレクトリ]。依存が無くロックファイルも無いなら問題なし。依存があるのにロックファイルが無ければエラー
    fun checkLock(directoryArgument: String?): Int {
        val root = projectRoot(directoryArgument)
        val loaded = loadManifest(root) ?: return ExitCode.INVALID_INPUT
        val manifest = loaded.manifest!!
        val lockPath = root.resolve(Lockfiles.FILE_NAME)
        val shownLock = shown(root, Lockfiles.FILE_NAME)
        if (!Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            if (manifest.dependencies.isEmpty()) {
                out.println("$shownLock: not needed (no dependencies)")
                return ExitCode.OK
            }
            report(shownLock, listOf(ProjectDiagnostic(ProjectDiagnosticCode.LOCK_MISSING, 1, 1, listOf(Lockfiles.FILE_NAME))))
            return ExitCode.INVALID_INPUT
        }
        val bytes = readFile(lockPath, shownLock, Lockfiles.MAX_BYTES)
        val parsed = Lockfiles.parse(bytes)
        val diagnostics = Lockfiles.check(bytes, parsed, manifest)
        if (diagnostics.isNotEmpty()) {
            report(shownLock, diagnostics, parsed.truncated)
            return ExitCode.INVALID_INPUT
        }
        out.println("$shownLock: ok (${parsed.lockfile!!.packages.size} packages, consistent with ${Manifests.FILE_NAME})")
        return ExitCode.OK
    }

    // マニフェストを読んで確かめ、エントリーのファイルがルートの中の通常のファイルであることも確かめる。問題があれば報告して null
    private fun loadManifest(root: Path): ManifestResult? {
        val manifestPath = root.resolve(Manifests.FILE_NAME)
        val shownManifest = shown(root, Manifests.FILE_NAME)
        if (!Files.exists(manifestPath)) throw Failure(ExitCode.NO_INPUT, "$shownManifest not found (create one with createscript init)")
        val result = Manifests.parse(readFile(manifestPath, shownManifest, Manifests.MAX_BYTES))
        val manifest = result.manifest
        if (manifest == null) {
            report(shownManifest, result.diagnostics, result.truncated)
            return null
        }
        entryProblem(root, manifest.entry)?.let { code ->
            report(shownManifest, listOf(ProjectDiagnostic(code, result.entryPosition.first, result.entryPosition.second, listOf(manifest.entry))))
            return null
        }
        return result
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

    private fun projectRoot(directoryArgument: String?): Path {
        val root = path(directoryArgument ?: ".")
        if (!Files.isDirectory(root)) throw Failure(ExitCode.NO_INPUT, "not a directory: $root")
        return root
    }

    private fun readFile(path: Path, shown: String, maxBytes: Int): ByteArray {
        if (!Files.isRegularFile(path)) throw Failure(ExitCode.NO_INPUT, "not a regular file: $shown")
        return try {
            SafeFiles.readLimited(path, maxBytes)
        } catch (error: IOException) {
            throw Failure(ExitCode.NO_INPUT, "cannot read file: $shown")
        }
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
