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
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermissions
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
internal class Owner private constructor(private val user: UserPrincipal?, private val check: ((Path) -> Boolean)? = null) {
    fun owns(path: Path): Boolean {
        check?.let { return it(path) }
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
        // テストだけで使う: ほかのユーザーのファイルを作れないテストで、所有者の確認の結果を決める
        internal fun forTests(owns: (Path) -> Boolean) = Owner(null, owns)

        // 既にあるファイル・ディレクトリの所有者（S8.2 Phase 4: 実行の前の確認では、ファイルを書かずに、.createscript と同じ所有者であることを確かめる）
        fun of(path: Path): Owner = try {
            Owner(Files.getOwner(path, LinkOption.NOFOLLOW_LINKS))
        } catch (error: UnsupportedOperationException) {
            Owner(null)
        } catch (error: IOException) {
            throw InstallFailure(InstallFailure.Kind.UNSAFE, "cannot read the owner of $path")
        }

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

// ディレクトリの同一性（S8.2 L-2: ジャーナルを書いた場所の確認に使う）。リンクでないディレクトリの fileKey の文字列で、取れなければ null
// （Windows の NTFS など、Java が fileKey を返さないファイルシステム）。テストは、取れない場合を渡す
internal fun interface DirectoryKeys {
    fun of(path: Path): String?

    companion object {
        val FILE_KEY = DirectoryKeys { path -> SafeIo.attributes(path)?.takeIf { it.isDirectory && !it.isSymbolicLink }?.fileKey()?.toString() }
    }
}

// ファイルシステムの操作のうち、テストで「できない」場合を渡すもの（S8.2 L-6）
internal class InstallFileSystem(
    val createLink: (target: Path, source: Path) -> Unit = { target, source -> Files.createLink(target, source) },
    val atomicMove: (source: Path, target: Path) -> Unit = { source, target -> Files.move(source, target, StandardCopyOption.ATOMIC_MOVE) },
    val addsSidecarFiles: (directory: Path) -> Boolean = SafeIo::addsSidecarFiles,
    // ディレクトリの所有者と権限・今のユーザー（S8.2 L-5 の案 C）
    val permissions: PermissionReader = PermissionReader.DEFAULT,
    val currentUser: () -> String? = DirectoryPermissions::currentUser,
) {
    companion object {
        val DEFAULT = InstallFileSystem()
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

    // root（プロジェクトのルートの実パス）から directory までの各ディレクトリの状態（S8.2 L-5）
    sealed interface Chain {
        // どれもリンクでない実際のディレクトリで、directory の実パスが directory そのもの
        data object Real : Chain

        // 途中か最後が無い（リンクではない）
        data object Missing : Chain

        // リンク・ディレクトリでないもの・ほかの場所に解決されるもの（理由）
        class NotReal(val reason: String) : Chain
    }

    // Java は名前でファイルを扱うので、確かめた直後に、ほかのプログラムが途中のディレクトリをリンクに差し替えることは防げない
    // （JDK 21 の macOS には、ディレクトリを開いたまま扱う SecureDirectoryStream・openat・renameat が無い）。確かめるのは、その時点の状態
    fun chain(root: Path, directory: Path): Chain {
        if (!directory.startsWith(root)) return Chain.NotReal("$directory is not inside $root")
        var current = root
        for (part in root.relativize(directory)) {
            current = current.resolve(part.toString())
            val attributes = attributes(current) ?: return Chain.Missing
            if (attributes.isSymbolicLink) {
                val link = try {
                    " (it points to ${Files.readSymbolicLink(current)})"
                } catch (error: IOException) {
                    ""
                }
                return Chain.NotReal("${root.relativize(current)} is a symbolic link$link")
            }
            if (!attributes.isDirectory) return Chain.NotReal("${root.relativize(current)} is not a directory")
        }
        val real = try {
            directory.toRealPath()
        } catch (error: IOException) {
            return Chain.Missing
        }
        if (real != directory) return Chain.NotReal("${root.relativize(directory)} resolves to $real")
        return Chain.Real
    }

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
                createDirectory(path)
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

    // 作るディレクトリ・ファイルの権限（S8.2 L-5 の案 C: 所有者だけが書き込める。umask はさらに権限を減らすだけ）。POSIX でないファイルシステムでは付けない
    private val DIRECTORY_PERMISSIONS = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x"))
    private val FILE_PERMISSIONS = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-r--r--"))

    // 新しいディレクトリ（既にあれば FileAlreadyExistsException）
    fun createDirectory(path: Path) {
        try {
            Files.createDirectory(path, DIRECTORY_PERMISSIONS)
        } catch (error: UnsupportedOperationException) {
            Files.createDirectory(path)
        }
    }

    // ファイルを作るときの属性（POSIX でなければ無し）
    fun fileAttributes(path: Path): Array<FileAttribute<*>> =
        if ("posix" in path.fileSystem.supportedFileAttributeViews()) arrayOf(FILE_PERMISSIONS) else emptyArray()

    // ---- 書きかけのファイル（S8.2 N-3） ----
    // 作業用のディレクトリのファイルは、一時的な名前で書き終えて（ディスクに書き出して）から、最終の名前を付ける。最終の名前のファイルは、必ず書き終えたもの。
    // 1. .part-<UUID> を作る（空）  2. その inode と、書く大きさを名前に入れて .part-<inode>-<大きさ>-<最終の名前> に変える
    // 3. 中身を書いてディスクに書き出す  4. 最終の名前に変える（置き換えない）
    // 途中で止まったものは、名前の inode が今のファイルと同じで（作ったファイルそのもの。保存のたびに別のファイルを作るエディターで書き換えれば違う）、
    // 大きさが名前の大きさ以下のときだけ、書きかけとして片付けてよい（Leftovers）。空の .part-<UUID> は、中身が無いので片付けてよい
    const val PART_PREFIX = ".part-"
    private val TAGGED_PART = Regex("\\.part-([0-9]+)-([0-9]+)-(.+)")
    private val CREATING_PART = Regex("\\.part-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    // 書きかけの名前（inode・書く大きさ・最終の名前）。違えば null
    class Part(val inode: Long, val size: Long, val finalName: String)

    fun part(name: String): Part? = TAGGED_PART.matchEntire(name)?.let { m ->
        val inode = m.groupValues[1].toLongOrNull() ?: return null
        val size = m.groupValues[2].toLongOrNull() ?: return null
        Part(inode, size, m.groupValues[3])
    }

    fun isCreatingPart(name: String) = CREATING_PART.matches(name)

    // inode（POSIX のファイルシステムだけ）。取れなければ null
    fun inode(path: Path): Long? = try {
        (Files.getAttribute(path, "unix:ino", LinkOption.NOFOLLOW_LINKS) as Number).toLong()
    } catch (error: UnsupportedOperationException) {
        null
    } catch (error: IllegalArgumentException) {
        null
    } catch (error: IOException) {
        null
    }

    // 書いている間の権限（S8.2 N-3 の対策 A）: 所有者が読めるだけ（0400）。ほかの書き込み（追記・上書き・エディターの直接の保存）は権限で拒否される。
    // 書くのは、作ったときに開いたファイル記述子（書き込める）だけ。最終の名前に変えてから 0644 にする
    private val PART_PERMISSIONS = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("r--------"))
    private val FINAL_PERMISSIONS = PosixFilePermissions.fromString("rw-r--r--")

    // 書きかけのファイルが、書いている間の権限（0400）のままか（読めなければ false）
    fun isReadOnlyPart(path: Path): Boolean = try {
        Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS) == PosixFilePermissions.fromString("r--------")
    } catch (error: UnsupportedOperationException) {
        false
    } catch (error: IOException) {
        false
    }

    // 作業用のディレクトリに、書き終えてから最終の名前を付けるファイル（上の手順）。onWrite は、各段階（テストの中断に使う）。
    // 順序: 0400 で作る（書き込める記述子を開いたまま）→ inode を名前に入れる → 記述子で書いてディスクに書き出し、閉じる → 最終の名前に変える → 0644 にする。
    // 最終の名前に変えた直後に止まったものは、0400 のままの書き終えたファイル（最終の名前のファイルは、記録の SHA-256 と照合して扱うので安全。所有者は読める）。
    // 0644 にしてから名前を変える順では、その間に止まると「0400 でない書きかけ」が残り、片付けられなくなるため、この順にする。
    // POSIX でないファイルシステム（inode・権限が無い）では、createFile と同じく最終の名前に直接書く
    fun writeNew(path: Path, bytes: ByteArray, onWrite: (String) -> Unit = {}): Identity {
        val directory = path.parent
        if ("posix" !in directory.fileSystem.supportedFileAttributeViews()) return createFile(path, bytes)
        val creating = directory.resolve("$PART_PREFIX${UUID.randomUUID()}")
        lateinit var tagged: Path
        FileChannel.open(creating, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS), PART_PERMISSIONS).use { channel ->
            onWrite("creating")
            val inode = inode(creating)
            if (inode == null) {
                channel.close()
                Files.delete(creating)
                return createFile(path, bytes)
            }
            tagged = directory.resolve("$PART_PREFIX$inode-${bytes.size}-${path.fileName}")
            // 開いたまま名前を変える（記述子は同じファイルを指したまま）
            moveNew(creating, tagged)
            onWrite("writing:${path.fileName}")
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        onWrite("written:${path.fileName}")
        moveNew(tagged, path)
        onWrite("renamed:${path.fileName}")
        try {
            Files.setPosixFilePermissions(path, FINAL_PERMISSIONS)
        } catch (error: IOException) {
            // 0400 のまま（所有者は読めるので、使うのに支障は無い）
        }
        return Identity(attributes(path)?.fileKey(), bytes.size.toLong(), sha256Hex(bytes))
    }

    // 新しいファイル（既にあれば失敗）に書いて、ディスクに書き出す
    fun createFile(path: Path, bytes: ByteArray): Identity {
        FileChannel.open(path, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS), *fileAttributes(path)).use { channel ->
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

    // 書き終えてディスクに書き出したファイル source を、target の名前で公開する（S8.2 L-6）。target が既にあれば FileAlreadyExistsException。
    // 1. ハードリンク（置き換えない、原子的）。source は残る
    // 2. ハードリンクを作れないファイルシステムでは、target が無いことを確かめてから、原子的な移動（ATOMIC_MOVE。rename）。source は無くなる。
    //    確かめてから移動するまでの間に target を作れるのは、install.lock を使わないほかのプログラムだけ（インストールは同時に 1 つ）
    // 3. 原子的な移動もできなければ AtomicMoveNotSupportedException（何も公開しない）。写す（copy）ことはしない: 写す途中で止まると、
    //    書きかけのファイルが target の名前で残るため
    fun publishNew(source: Path, target: Path, fileSystem: InstallFileSystem) {
        try {
            fileSystem.createLink(target, source)
            return
        } catch (error: FileAlreadyExistsException) {
            throw error
        } catch (error: UnsupportedOperationException) {
            // ハードリンクを扱えないファイルシステム
        } catch (error: IOException) {
            // FAT などでは FileSystemException（Operation not supported）
            if (exists(target)) throw FileAlreadyExistsException(target.toString())
        }
        if (exists(target)) throw FileAlreadyExistsException(target.toString())
        fileSystem.atomicMove(source, target)
    }

    // 新しいファイルの隣に、ファイルシステムが ._<名前> のファイル（AppleDouble）を作るか（S8.2 L-6）。macOS は FAT・exFAT などで、
    // 拡張属性を ._ のファイルに書く。試しのファイルを作って確かめ、試しのファイル（と ._ のファイル）を消す
    fun addsSidecarFiles(directory: Path): Boolean {
        val name = ".probe-${UUID.randomUUID()}"
        val probe = directory.resolve(name)
        val sidecar = directory.resolve("._$name")
        try {
            Files.newByteChannel(probe, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).close()
        } catch (error: IOException) {
            unsafe("cannot write to $directory")
        }
        return try {
            attributes(sidecar) != null
        } finally {
            try {
                Files.deleteIfExists(probe)
                if (attributes(sidecar)?.isRegularFile == true) Files.deleteIfExists(sidecar)
            } catch (error: IOException) {
                // 空のファイルが残るだけ
            }
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
