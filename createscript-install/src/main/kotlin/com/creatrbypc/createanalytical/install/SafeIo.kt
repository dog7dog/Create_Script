package com.creatrbypc.createanalytical.install

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.UserPrincipal
import java.util.UUID

// インストールの失敗（S8.2 Phase 3）。kind はコマンドの終了コードを決める（CLI が対応づける）。メッセージにはトークンを入れない
class InstallFailure(val kind: Kind, message: String) : Exception(message) {
    enum class Kind {
        // 通信できない・オフラインでキャッシュに無い（69）
        NETWORK,

        // パッケージ・依存・プロジェクトのファイルの内容の問題（65）
        INVALID,

        // プロジェクトが無い（66）
        NO_PROJECT,

        // ファイルの状態が想定と違い、安全に続けられない・書けない（73）
        UNSAFE,

        // ほかのインストールが動いている（75）
        BUSY,
    }
}

// ファイルの同一性（S8.1 の FileIdentity と同じ考え方）。fileKey が両方とも取れるときは、それも比べる
internal class Identity(val fileKey: Any?, val size: Long, val sha256: String) {
    fun sameFileAs(other: Identity) = sha256 == other.sha256 && size == other.size && (fileKey == null || other.fileKey == null || fileKey == other.fileKey)
}

internal class ReadFile(val bytes: ByteArray, val identity: Identity)

// プロジェクトの中の置き場所（S8.2 Phase 3。docs/packages.md の「Installing」）
internal class Layout(val root: Path) {
    val manifest: Path = root.resolve(com.creatrbypc.createanalytical.project.Manifests.FILE_NAME)
    val lockfile: Path = root.resolve(com.creatrbypc.createanalytical.project.Lockfiles.FILE_NAME)
    val state: Path = root.resolve(InstallLayout.DIRECTORY)
    val installLock: Path = state.resolve(InstallLayout.INSTALL_LOCK)
    val journal: Path = state.resolve(InstallLayout.JOURNAL)
    val packages: Path = state.resolve(InstallLayout.PACKAGES)

    fun packageDirectory(name: String, version: String): Path = packages.resolve(name).resolve(version)

    fun transaction(id: String): Path = state.resolve(InstallLayout.TRANSACTION_PREFIX + id)
}

// 名前（CLI の manifest check・lock check も、ジャーナルの有無を確かめるために使う）
object InstallLayout {
    const val DIRECTORY = ".createscript"
    const val INSTALL_LOCK = "install.lock"
    const val JOURNAL = "transaction.json"
    const val PACKAGES = "packages"
    const val TRANSACTION_PREFIX = "txn-"
    const val MARKER = ".createscript-package.json"

    // ジャーナルのパス（プロジェクトのルートから）
    fun journal(root: Path): Path = root.resolve(DIRECTORY).resolve(JOURNAL)
}

// 所有者の確認（ジャーナル・印・作業用のディレクトリを、このユーザーが作ったものに限る）。所有者を扱えないファイルシステムでは確かめない
internal class Owner private constructor(private val user: UserPrincipal?) {
    fun owns(path: Path): Boolean {
        if (user == null) return true
        return try {
            Files.getOwner(path, LinkOption.NOFOLLOW_LINKS) == user
        } catch (error: UnsupportedOperationException) {
            true
        } catch (error: IOException) {
            false
        }
    }

    companion object {
        // 今のユーザー: ディレクトリに新しいファイルを作り、その所有者を読む（ユーザー名の環境変数・プロパティを信用しない）
        fun probe(directory: Path): Owner {
            val probe = directory.resolve(".owner-${UUID.randomUUID()}")
            return try {
                Files.newByteChannel(probe, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).close()
                Owner(Files.getOwner(probe, LinkOption.NOFOLLOW_LINKS))
            } catch (error: UnsupportedOperationException) {
                Owner(null)
            } catch (error: IOException) {
                throw InstallFailure(InstallFailure.Kind.UNSAFE, "cannot write to $directory")
            } finally {
                try {
                    Files.deleteIfExists(probe)
                } catch (error: IOException) {
                    // 空のファイルが残るだけ
                }
            }
        }
    }
}

// インストールのファイルの操作（S8.2 Phase 3）。どれもシンボリックリンクをたどらない
internal object SafeIo {
    fun sha256Hex(bytes: ByteArray): String = GitHubReleases.sha256Hex(bytes)

    fun attributes(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (error: NoSuchFileException) {
        null
    } catch (error: IOException) {
        throw InstallFailure(InstallFailure.Kind.UNSAFE, "cannot read the attributes of $path")
    }

    fun exists(path: Path) = attributes(path) != null

    private fun unsafe(message: String): Nothing = throw InstallFailure(InstallFailure.Kind.UNSAFE, message)

    // リンクでない通常のファイルを最大 maxBytes まで読む。無ければ null。リンク・通常のファイルでない・大きすぎる・読む間に変わったものは UNSAFE
    fun readRegular(path: Path, maxBytes: Int): ReadFile? {
        val before = attributes(path) ?: return null
        if (before.isSymbolicLink) unsafe("$path is a symbolic link")
        if (!before.isRegularFile) unsafe("$path is not a regular file")
        if (before.size() > maxBytes) unsafe("$path is larger than $maxBytes bytes")
        val bytes = try {
            Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(maxBytes + 1) }
        } catch (error: IOException) {
            unsafe("cannot read $path")
        }
        if (bytes.size > maxBytes) unsafe("$path is larger than $maxBytes bytes")
        val after = attributes(path)
        if (after == null || !after.isRegularFile || after.fileKey() != before.fileKey() || after.size() != bytes.size.toLong() || after.lastModifiedTime() != before.lastModifiedTime()) {
            unsafe("$path changed while it was read")
        }
        return ReadFile(bytes, Identity(before.fileKey(), bytes.size.toLong(), sha256Hex(bytes)))
    }

    // ディレクトリ（リンクでないこと）。無ければ create のときだけ作る。作った・あったなら true
    fun directory(path: Path, create: Boolean): Boolean {
        val attributes = attributes(path)
        if (attributes == null) {
            if (!create) return false
            try {
                Files.createDirectory(path)
            } catch (error: FileAlreadyExistsException) {
                return directory(path, false)
            } catch (error: IOException) {
                unsafe("cannot create the directory $path")
            }
            return true
        }
        if (attributes.isSymbolicLink) unsafe("$path is a symbolic link; install does not write through symbolic links")
        if (!attributes.isDirectory) unsafe("$path is not a directory")
        return true
    }

    // 新しいファイル（既にあれば失敗）に書いて、ディスクに書き出す
    fun createFile(path: Path, bytes: ByteArray): Identity {
        FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        return Identity(attributes(path)?.fileKey(), bytes.size.toLong(), sha256Hex(bytes))
    }

    // ディレクトリの変更（名前の追加・削除）をディスクに書き出す（できない環境では何もしない）
    fun syncDirectory(directory: Path) {
        try {
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        } catch (error: Exception) {
            // macOS・Linux 以外ではディレクトリを開けないことがある
        }
    }

    // source の名前を target に付ける（target が既にあれば失敗する: ハードリンク。作れなければ置き換えない移動）。source は残る
    fun linkNew(source: Path, target: Path) {
        try {
            Files.createLink(target, source)
        } catch (error: FileAlreadyExistsException) {
            throw error
        } catch (error: UnsupportedOperationException) {
            Files.copy(source, target)
        } catch (error: IOException) {
            if (exists(target)) throw FileAlreadyExistsException(target.toString())
            Files.copy(source, target)
        }
    }

    // 置き換える移動（同じファイルシステムの中の rename）
    fun replace(source: Path, target: Path) {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    }

    // 置き換えない移動（ディレクトリ・ファイル）。target が既にあれば FileAlreadyExistsException
    fun moveNew(source: Path, target: Path) {
        Files.move(source, target)
    }

    // POSIX の権限を写す（新しいマニフェスト・ロックファイルを、前のファイルと同じ権限にする）
    fun copyPermissions(from: Path, to: Path) {
        try {
            Files.setPosixFilePermissions(to, Files.getPosixFilePermissions(from, LinkOption.NOFOLLOW_LINKS))
        } catch (error: UnsupportedOperationException) {
            // POSIX でないファイルシステム
        } catch (error: IOException) {
            // 既定の権限のまま
        }
    }

    // 確かめてから消す: リンクでない通常のファイルで、SHA-256（と、渡されたなら fileKey）が同じときだけ。消さなかった理由を返す（消したか、無ければ null）
    fun deleteFileIfSame(path: Path, sha256: String, maxBytes: Int, fileKey: Any? = null): String? {
        val file = try {
            readRegular(path, maxBytes) ?: return null
        } catch (failure: InstallFailure) {
            return failure.message
        }
        if (file.identity.sha256 != sha256) return "its contents changed"
        if (fileKey != null && file.identity.fileKey != null && file.identity.fileKey != fileKey) return "it was replaced by another file"
        return try {
            Files.delete(path)
            null
        } catch (error: NoSuchFileException) {
            null
        } catch (error: IOException) {
            "cannot delete it"
        }
    }

    // 空のディレクトリ（リンクでないこと）だけを消す。消さなかった理由を返す
    fun deleteEmptyDirectory(path: Path): String? {
        val attributes = attributes(path) ?: return null
        if (attributes.isSymbolicLink || !attributes.isDirectory) return "it is not a directory"
        return try {
            Files.delete(path)
            null
        } catch (error: DirectoryNotEmptyException) {
            "it is not empty"
        } catch (error: NoSuchFileException) {
            null
        } catch (error: IOException) {
            "cannot delete it"
        }
    }

    // ディレクトリの中の名前（最大 limit + 1 個まで読む）
    fun list(directory: Path, limit: Int): List<String> = try {
        Files.newDirectoryStream(directory).use { stream ->
            val names = ArrayList<String>()
            for (entry in stream) {
                names.add(entry.fileName.toString())
                if (names.size > limit) break
            }
            names.sorted()
        }
    } catch (error: IOException) {
        unsafe("cannot list $directory")
    }

    // 今回作ったばかりの作業用のディレクトリを消す（ジャーナルを書く前の失敗のとき。リンクはたどらずに、リンクそのものを消す）
    fun deleteOwnTree(path: Path) {
        val attributes = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (error: IOException) {
            return
        }
        try {
            if (attributes.isDirectory) {
                Files.newDirectoryStream(path).use { stream -> stream.toList() }.forEach { deleteOwnTree(it) }
            }
            Files.deleteIfExists(path)
        } catch (error: IOException) {
            // 残ったものは、次のインストールに影響しない（txn-* はジャーナルからだけ参照する）
        }
    }
}
