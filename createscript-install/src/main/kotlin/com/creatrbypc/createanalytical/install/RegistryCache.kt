package com.creatrbypc.createanalytical.install

import com.creatrbypc.createanalytical.project.JsonInteger
import com.creatrbypc.createanalytical.project.JsonObject
import com.creatrbypc.createanalytical.project.JsonParser
import com.creatrbypc.createanalytical.project.JsonString
import com.creatrbypc.createanalytical.project.ProjectRules
import com.creatrbypc.createanalytical.project.registry.RegistryLimits
import com.creatrbypc.createanalytical.project.registry.RegistryRules
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.UUID

// 受け入れたレジストリの index の保存（S8.3 Phase 3。docs/registry.md の「The client」）。置き場所: <キャッシュ>/registry/<持ち主>/<リポジトリ>/（小文字）
// - trusted.json: 信頼の履歴（最後に受け入れた index の serial・生成日時・index と署名のファイルの SHA-256）。巻き戻しの検出に使う
// - index-<SHA-256>.json・signature-<SHA-256>.sig: 受け取ったバイト列そのもの（内容で名前を決める。読むたびに SHA-256 を確かめ、署名も確かめ直す）
// - lock: 読み書きの間に持つロック（ほかのプロセス・スレッドと同時に書き換えない）
// 更新は、index と署名のファイルを書き終えてディスクに書き出してから、trusted.json を一時ファイルからの原子的な移動で置き換える。
// どこで止まっても、trusted.json は前の正しい状態か新しい正しい状態のどちらか（写す・その場で書き換えることはしない）。
// 置き場所の registry・持ち主・リポジトリのディレクトリは、リンクでない、このユーザーのもので、グループ・ほかのユーザーが書き込めないものだけを使う
// （作るときは 0700）。キャッシュの root 自体は、利用者が別のディスクへのリンクにしていてもよい（ArchiveCache と同じ）
class RegistryCache internal constructor(
    root: Path,
    val repository: String,
    private val hooks: (String) -> Unit,
    private val lockWait: LockWait,
    private val permissions: PermissionReader = PermissionReader.DEFAULT,
    private val currentUser: () -> String? = DirectoryPermissions::currentUser,
) {
    constructor(root: Path, repository: String) : this(root, repository, {}, LockWait.INSTALL)

    private val root: Path = root.toAbsolutePath().normalize()

    init {
        require(ProjectRules.isValidSource("github:$repository")) { "invalid repository: $repository" }
    }

    // 信頼の履歴（trusted.json）
    class History(val serial: Long, val generated: Instant, val indexSha256: String, val signatureSha256: String)

    // 読んだ状態。index・signature は、ファイルが履歴の SHA-256 と一致したときだけ（そうでなければ problem に理由）
    sealed interface State {
        data object Empty : State

        class Present(val history: History, val index: ByteArray?, val signature: ByteArray?, val problem: String?) : State
    }

    companion object {
        const val HISTORY = "trusted.json"
        const val LOCK = "lock"
        private const val FORMAT = 1L
        private const val MAX_HISTORY_BYTES = 4096
        private const val TEMPORARY_PREFIX = ".tmp-"
        private val INDEX_FILE = Regex("index-([0-9a-f]{64})\\.json")
        private val SIGNATURE_FILE = Regex("signature-([0-9a-f]{64})\\.sig")
        private const val OTHERS_WRITE = 0b000_010_010

        fun indexFile(sha256: String) = "index-$sha256.json"

        fun signatureFile(sha256: String) = "signature-$sha256.sig"
    }

    private fun unsafe(message: String): Nothing = throw InstallFailure(InstallFailure.Kind.UNSAFE, message)

    // 置き場所のディレクトリ（create なら作る）。無ければ null
    internal fun directory(create: Boolean): Path? {
        if (!Files.exists(root)) {
            if (!create) return null
            try {
                createPrivateDirectories(root)
            } catch (error: IOException) {
                unsafe("cannot create the cache directory $root")
            }
        }
        var current = try {
            root.toRealPath()
        } catch (error: IOException) {
            unsafe("cannot use the cache directory $root")
        }
        if (!Files.isDirectory(current)) unsafe("the cache location $root is not a directory")
        val (owner, name) = repository.lowercase().split('/')
        for (part in listOf("registry", owner, name)) {
            current = current.resolve(part)
            if (SafeIo.attributes(current) == null) {
                if (!create) return null
                try {
                    createPrivateDirectory(current)
                } catch (error: FileAlreadyExistsException) {
                    // 同時に作られた（下で確かめる）
                } catch (error: IOException) {
                    unsafe("cannot create $current")
                }
            }
            checkDirectory(current)
        }
        return current
    }

    // リンクでない、このユーザーのディレクトリで、グループ・ほかのユーザーが書き込めないこと（POSIX の属性を読めないファイルシステムでは、リンクでないことだけ）
    private fun checkDirectory(path: Path) {
        val attributes = SafeIo.attributes(path) ?: unsafe("$path disappeared")
        if (attributes.isSymbolicLink) unsafe("$path is a symbolic link; the registry cache does not follow links. Remove it")
        if (!attributes.isDirectory) unsafe("$path is not a directory. Remove it")
        val posix = permissions.read(path) ?: return
        val user = currentUser()
        if (user != null && posix.owner != user) unsafe("$path belongs to ${posix.owner}, not to $user; the registry cache uses only the user's own directories")
        if (posix.mode and OTHERS_WRITE != 0) {
            unsafe("$path can be written by other users (${DirectoryPermissions.permissions(posix.mode)}); the registry cache is not used. Make it writable only by you (chmod go-w)")
        }
    }

    // body の間、ロックを持つ（ほかのプロセスとスレッドを待つ。上限を超えれば BUSY）
    fun <T> withLock(body: () -> T): T {
        val directory = directory(create = true) ?: unsafe("cannot use the registry cache")
        val path = directory.resolve(LOCK)
        val attributes = SafeIo.attributes(path)
        if (attributes != null && (attributes.isSymbolicLink || !attributes.isRegularFile)) unsafe("$path is not a regular file; remove it")
        val channel = try {
            FileChannel.open(path, setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS), *SafeIo.fileAttributes(path))
        } catch (error: IOException) {
            unsafe("cannot open $path")
        }
        channel.use {
            val deadline = System.nanoTime() + lockWait.timeoutMillis * 1_000_000
            var lock: FileLock? = null
            while (lock == null) {
                lock = try {
                    channel.tryLock()
                } catch (error: OverlappingFileLockException) {
                    null
                } catch (error: IOException) {
                    unsafe("cannot lock $path")
                }
                if (lock == null) {
                    if (System.nanoTime() >= deadline) {
                        throw InstallFailure(InstallFailure.Kind.BUSY, "another createscript is updating the registry cache ($path is locked) and did not finish within ${lockWait.timeoutMillis / 1000} seconds; try again")
                    }
                    Thread.sleep(lockWait.pollMillis)
                }
            }
            try {
                return body()
            } finally {
                try {
                    lock.release()
                } catch (error: IOException) {
                    // チャネルを閉じればロックも外れる
                }
            }
        }
    }

    // 保存した状態を読む（withLock の中で呼ぶ）。履歴のファイルが壊れていれば UNSAFE（履歴を失ったまま続けると、巻き戻しを見逃すため）
    fun read(): State {
        val directory = directory(create = false) ?: return State.Empty
        val historyPath = directory.resolve(HISTORY)
        val file = SafeIo.readRegular(historyPath, MAX_HISTORY_BYTES) ?: return State.Empty
        val history = parseHistory(file.bytes) ?: unsafe(
            "$historyPath is damaged or was changed by another program, so the registry index cannot be compared with the one accepted before. " +
                "If you did not change it, someone may have tampered with the cache. To start again without the earlier history, remove the directory $directory",
        )
        fun content(name: String, sha256: String, maxBytes: Int): Pair<ByteArray?, String?> {
            val path = directory.resolve(name)
            val read = try {
                SafeIo.readRegular(path, maxBytes)
            } catch (failure: InstallFailure) {
                return null to (failure.message ?: "cannot read $path")
            } ?: return null to "$path is missing"
            if (read.identity.sha256 != sha256) return null to "$path does not match its SHA-256 (it was damaged or changed)"
            return read.bytes to null
        }
        val (index, indexProblem) = content(indexFile(history.indexSha256), history.indexSha256, RegistryLimits.MAX_INDEX_BYTES)
        val (signature, signatureProblem) = content(signatureFile(history.signatureSha256), history.signatureSha256, RegistryLimits.MAX_SIGNATURE_BYTES)
        return State.Present(history, index, signature, indexProblem ?: signatureProblem)
    }

    // 受け入れた index を保存する（withLock の中で呼ぶ）。失敗しても、前の状態は壊れない
    fun store(serial: Long, generated: Instant, index: ByteArray, signature: ByteArray) {
        val directory = directory(create = true) ?: unsafe("cannot use the registry cache")
        val before = DirectoryKeys.FILE_KEY.of(directory)
        val indexSha256 = SafeIo.sha256Hex(index)
        val signatureSha256 = SafeIo.sha256Hex(signature)
        writeReplacing(directory, indexFile(indexSha256), index)
        writeReplacing(directory, signatureFile(signatureSha256), signature)
        SafeIo.syncDirectory(directory)
        hooks("files-written")
        val history = writeHistory(History(serial, generated, indexSha256, signatureSha256))
        val temporary = writeTemporary(directory, history)
        hooks("history-written")
        if (DirectoryKeys.FILE_KEY.of(directory) != before) unsafe("$directory was replaced by another directory while the registry index was saved")
        try {
            Files.move(temporary, directory.resolve(HISTORY), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: AtomicMoveNotSupportedException) {
            Files.deleteIfExists(temporary)
            unsafe("the file system of $directory does not support atomic renames, so the registry index cannot be saved safely")
        }
        SafeIo.syncDirectory(directory)
        hooks("history-replaced")
        clean(directory, indexSha256, signatureSha256)
    }

    // 内容で名前の決まるファイル（既にあれば、同じ名前は同じ内容なので、置き換えても変わらない。リンク・壊れた中身もこれで正しいファイルになる）
    private fun writeReplacing(directory: Path, name: String, bytes: ByteArray) {
        val temporary = writeTemporary(directory, bytes)
        try {
            Files.move(temporary, directory.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: IOException) {
            Files.deleteIfExists(temporary)
            if (error is AtomicMoveNotSupportedException) unsafe("the file system of $directory does not support atomic renames, so the registry index cannot be saved safely")
            throw error
        }
    }

    private fun writeTemporary(directory: Path, bytes: ByteArray): Path {
        val temporary = directory.resolve("$TEMPORARY_PREFIX${UUID.randomUUID()}")
        val attributes = if ("posix" in directory.fileSystem.supportedFileAttributeViews()) {
            arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } else {
            emptyArray()
        }
        FileChannel.open(temporary, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS), *attributes).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        return temporary
    }

    // 使わなくなったファイルと、止まった更新の一時ファイルを消す（ロックを持っているので、ほかに書いている者はいない）。リンク・ディレクトリは消さない
    private fun clean(directory: Path, indexSha256: String, signatureSha256: String) {
        val names = try {
            SafeIo.list(directory, 10_000)
        } catch (failure: InstallFailure) {
            return
        }
        for (name in names) {
            val old = name.startsWith(TEMPORARY_PREFIX) ||
                (INDEX_FILE.matches(name) && name != indexFile(indexSha256)) ||
                (SIGNATURE_FILE.matches(name) && name != signatureFile(signatureSha256))
            if (!old) continue
            val path = directory.resolve(name)
            if (SafeIo.attributes(path)?.isRegularFile != true) continue
            try {
                Files.deleteIfExists(path)
            } catch (error: IOException) {
                // 残るだけ（次の更新で消す）
            }
        }
    }

    // {"format":1,"generated":"…","indexSha256":"…","repository":"…","serial":N,"signatureSha256":"…"}（正規形の JSON）
    private fun writeHistory(history: History): ByteArray =
        ("{\"format\":$FORMAT,\"generated\":\"${RegistryRules.formatTimestamp(history.generated)}\",\"indexSha256\":\"${history.indexSha256}\"," +
            "\"repository\":\"${repository.lowercase()}\",\"serial\":${history.serial},\"signatureSha256\":\"${history.signatureSha256}\"}").toByteArray(Charsets.UTF_8)

    private fun parseHistory(bytes: ByteArray): History? {
        val json = try {
            JsonParser.parse(bytes, MAX_HISTORY_BYTES).value as? JsonObject
        } catch (error: Exception) {
            null
        } ?: return null
        if (json.members.keys != setOf("format", "generated", "indexSha256", "repository", "serial", "signatureSha256")) return null
        if ((json.members["format"] as? JsonInteger)?.value != FORMAT) return null
        if ((json.members["repository"] as? JsonString)?.value != repository.lowercase()) return null
        val serial = (json.members["serial"] as? JsonInteger)?.value?.takeIf { it in 1..RegistryRules.MAX_ID } ?: return null
        val generated = (json.members["generated"] as? JsonString)?.value?.let(RegistryRules::parseTimestamp) ?: return null
        val indexSha256 = (json.members["indexSha256"] as? JsonString)?.value?.takeIf(ProjectRules::isValidSha256) ?: return null
        val signatureSha256 = (json.members["signatureSha256"] as? JsonString)?.value?.takeIf(ProjectRules::isValidSha256) ?: return null
        val history = History(serial, generated, indexSha256, signatureSha256)
        // 書く形と 1 バイトも違わないこと
        return history.takeIf { writeHistory(it).contentEquals(bytes) }
    }

    private fun createPrivateDirectories(path: Path) {
        val missing = ArrayList<Path>()
        var current: Path? = path
        while (current != null && !Files.exists(current)) {
            missing.add(current)
            current = current.parent
        }
        for (directory in missing.asReversed()) {
            try {
                createPrivateDirectory(directory)
            } catch (error: FileAlreadyExistsException) {
                // 同時に作られた
            }
        }
    }

    private fun createPrivateDirectory(path: Path) {
        if ("posix" in path.fileSystem.supportedFileAttributeViews()) {
            Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        } else {
            Files.createDirectory(path)
        }
    }
}
