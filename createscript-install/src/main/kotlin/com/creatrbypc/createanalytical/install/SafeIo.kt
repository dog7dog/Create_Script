package com.creatrbypc.createanalytical.install

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
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

    // 利用者に示す名前（S8.2 N-4）: プロジェクトの中はルートからの相対パス。ルートそのものと外は、そのままのパス
    fun shown(path: Path): String = if (path != root && path.startsWith(root)) root.relativize(path).toString() else path.toString()
}

// 名前（CLI の manifest check・lock check も、ジャーナルの有無を確かめるために使う）
object InstallLayout {
    const val DIRECTORY = ".createscript"
    const val INSTALL_LOCK = "install.lock"
    const val JOURNAL = "transaction.json"
    const val PACKAGES = "packages"
    const val TRANSACTION_PREFIX = "txn-"
    const val MARKER = ".createscript-package.json"

    // install.lock の 2 つの範囲（S8.2 L-8: install を優先する読み書きのロック）。
    // - gate（0 バイト目）: install は排他で取ってから data を待つ（待っている間、新しい run は入れない）。run は共有で取り、data を取ったらすぐ外す
    // - data（1 バイト目から最後まで）: install は排他、run は共有で、確かめる間だけ持つ
    // どちらも gate → data の順に取るので、互いに待ち合って止まることは無い
    const val LOCK_GATE_POSITION = 0L
    const val LOCK_GATE_SIZE = 1L
    const val LOCK_DATA_POSITION = 1L
    const val LOCK_DATA_SIZE = Long.MAX_VALUE - 1

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
            SafeIo.anchorFor(probe)?.let { fs ->
                val id = try {
                    fs.write(probe, ByteArray(0), replace = false)
                } catch (error: IOException) {
                    throw InstallFailure(InstallFailure.Kind.UNSAFE, "cannot write to $directory")
                }
                return try {
                    Owner(Files.getOwner(probe, LinkOption.NOFOLLOW_LINKS))
                } catch (error: UnsupportedOperationException) {
                    Owner(null)
                } catch (error: IOException) {
                    throw InstallFailure(InstallFailure.Kind.UNSAFE, "cannot read the owner of $probe")
                } finally {
                    try {
                        fs.remove(probe, id)
                    } catch (error: IOException) {
                        // 空のファイルが残るだけ
                    }
                }
            }
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

    // ---- S8.3 L-5: 作業ディレクトリを固定した子プロセス（AnchoredFs） ----
    // インストールの間（Installer が withAnchor で設定する）、プロジェクトの中のファイルを変える操作は、すべて AnchoredFs で行う。
    // 設定されていない（Windows・テストの直接の呼び出し）ときは、これまでの処理
    private val anchor = ThreadLocal<AnchoredFs?>()

    fun <T> withAnchor(fs: AnchoredFs?, body: () -> T): T {
        val previous = anchor.get()
        anchor.set(fs)
        try {
            return body()
        } finally {
            anchor.set(previous)
        }
    }

    // path を変える操作に使う AnchoredFs（プロジェクトの中のときだけ）
    fun anchorFor(path: Path): AnchoredFs? = anchor.get()?.takeIf { it.owns(path) }

    fun anchored(path: Path): Boolean = anchorFor(path) != null

    // unix:dev・unix:ino など（リンクをたどらない）。取れなければ null
    fun unixLong(path: Path, attribute: String): Long? = try {
        (Files.getAttribute(path, "unix:$attribute", LinkOption.NOFOLLOW_LINKS) as Number).toLong()
    } catch (error: UnsupportedOperationException) {
        null
    } catch (error: IllegalArgumentException) {
        null
    } catch (error: IOException) {
        null
    }

    // ファイル・ディレクトリの識別子（デバイス番号:i-node。AnchoredFs の識別子と同じ形）。取れなければ null
    fun fileId(path: Path): String? {
        val device = unixLong(path, "dev") ?: return null
        val inode = unixLong(path, "ino") ?: return null
        return AnchoredFs.idOf(device, inode)
    }

    // 今の識別子（固定したディレクトリから見たもの。AnchoredFs が無ければパスで読む）
    fun currentId(path: Path): String? = anchorFor(path)?.stat(path)?.id ?: if (anchored(path)) null else fileId(path)

    // 書き終えたファイルをディスクに書き出す（パスで読み取りのために開いて force。差し替えられていても、ほかのファイルを変えない）
    private fun force(path: Path) {
        try {
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.force(true) }
        } catch (error: IOException) {
            // 書き出せなくても、中身は書いてある（電源が切れたときの保証が弱くなるだけ）
        }
    }

    // 内容を、名前 target で新しく公開する（既にあれば FileAlreadyExistsException）。AnchoredFs のときだけ使う
    fun publishContent(target: Path, bytes: ByteArray, mode: Int = 0b110_100_100): String {
        val fs = anchorFor(target) ?: unsafe("internal error: $target is not handled by the file helper")
        return fs.write(target, bytes, replace = false, mode = mode, durable = ::force)
    }

    // 内容で target を置き換える（同じディレクトリの中で書いてから付け替える。target のリンクの先へは入らない）。AnchoredFs のときだけ使う
    fun replaceContent(target: Path, bytes: ByteArray, mode: Int): String {
        val fs = anchorFor(target) ?: unsafe("internal error: $target is not handled by the file helper")
        return fs.write(target, bytes, replace = true, mode = mode, durable = ::force)
    }

    // POSIX の権限（数値）。読めなければ 0644
    fun modeOf(path: Path): Int = try {
        Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).sumOf { permission ->
            when (permission) {
                java.nio.file.attribute.PosixFilePermission.OWNER_READ -> 256
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE -> 128
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE -> 64
                java.nio.file.attribute.PosixFilePermission.GROUP_READ -> 32
                java.nio.file.attribute.PosixFilePermission.GROUP_WRITE -> 16
                java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE -> 8
                java.nio.file.attribute.PosixFilePermission.OTHERS_READ -> 4
                java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE -> 2
                java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE -> 1
                null -> 0
            }.toInt()
        }
    } catch (error: Exception) {
        0b110_100_100
    }

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
    fun directory(path: Path, create: Boolean, shown: String = path.toString()): Boolean {
        val attributes = attributes(path)
        if (attributes == null) {
            if (!create) return false
            try {
                createDirectory(path)
            } catch (error: FileAlreadyExistsException) {
                return directory(path, false, shown)
            } catch (error: IOException) {
                unsafe("cannot create the directory $shown")
            }
            return true
        }
        if (attributes.isSymbolicLink) unsafe("$shown is a symbolic link; createscript install does not write through symbolic links. Remove the link (it is not followed) and run createscript install again")
        if (!attributes.isDirectory) unsafe("$shown is not a directory; remove it and run createscript install again")
        return true
    }

    // 作るディレクトリ・ファイルの権限（S8.2 L-5 の案 C: 所有者だけが書き込める。umask はさらに権限を減らすだけ）。POSIX でないファイルシステムでは付けない
    private val DIRECTORY_PERMISSIONS = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x"))
    private val FILE_PERMISSIONS = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-r--r--"))

    // 新しいディレクトリ（既にあれば FileAlreadyExistsException）
    fun createDirectory(path: Path) {
        anchorFor(path)?.let {
            it.mkdir(path)
            return
        }
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
    // S8.3 L-5（Java 21 でできる対策）: owned には、このインストールが作ったファイル・ディレクトリの fileKey を加える（作った直後に、一意の名前 .part-<UUID> で読む。
    // その名前のものは、どこにあっても自分が作ったもの）。中身は、作ったファイルが意図した場所にあること（isExactly）を確かめてからだけ書く
    // （途中のディレクトリが差し替えられて外に作られたなら、空のまま止まる。外に残りうるのは、空の 0400 の .part-<UUID> だけ）。
    // 名前の付け替えは、置き換えないハードリンク（renameNoReplace）で行う
    fun writeNew(path: Path, bytes: ByteArray, onWrite: (String) -> Unit = {}, owned: MutableSet<String>? = null): Identity {
        anchorFor(path)?.let { fs ->
            val id = fs.write(path, bytes, replace = false, onStep = onWrite, durable = ::force)
            owned?.add(id)
            return Identity(attributes(path)?.fileKey(), bytes.size.toLong(), sha256Hex(bytes))
        }
        val directory = path.parent
        if ("posix" !in directory.fileSystem.supportedFileAttributeViews()) return createFile(path, bytes, owned)
        val creating = directory.resolve("$PART_PREFIX${UUID.randomUUID()}")
        lateinit var tagged: Path
        val key: Any?
        FileChannel.open(creating, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS), PART_PERMISSIONS).use { channel ->
            onWrite("creating")
            // 作った直後の実際のパス（途中のリンクを解決したもの）。一意の名前なので、そこにあるものは自分が作ったもの（どこにあっても）
            val real = try {
                creating.toRealPath()
            } catch (error: IOException) {
                null
            }
            key = attributes(real ?: creating)?.fileKey()
            val id = fileId(real ?: creating)
            // 意図した場所に無ければ、中身を書かずに止まる。外に作った空のファイルは、解決した実際のパスで、自分のもの（fileKey が同じ）と確かめてから片付ける
            if (real != creating) {
                channel.close()
                removeEscaped(real ?: creating, key)
                escapedBeforeWriting(creating)
            }
            val inode = inode(creating)
            if (inode == null) {
                channel.close()
                Files.delete(creating)
                return createFile(path, bytes, owned)
            }
            id?.let { owned?.add(it) }
            tagged = directory.resolve("$PART_PREFIX$inode-${bytes.size}-${path.fileName}")
            // 開いたまま名前を変える（記述子は同じファイルを指したまま）
            renameNoReplace(creating, tagged)
            onWrite("writing:${path.fileName}")
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        onWrite("written:${path.fileName}")
        renameNoReplace(tagged, path)
        onWrite("renamed:${path.fileName}")
        try {
            Files.setPosixFilePermissions(path, FINAL_PERMISSIONS)
        } catch (error: IOException) {
            // 0400 のまま（所有者は読めるので、使うのに支障は無い）
        }
        return Identity(key ?: attributes(path)?.fileKey(), bytes.size.toLong(), sha256Hex(bytes))
    }

    // path が、リンクを含まず、そのとおりの場所にあるか（S8.3 L-5）。path はプロジェクトの実パスから決まった名前で組み立てたもの。
    // 作った直後に確かめると: 外に作られたなら、今もリンクを通る（実パスが違う）か、リンクが戻されて元の場所には無い（一意の名前なので）。どちらも false
    fun isExactly(path: Path): Boolean = try {
        path.toRealPath() == path
    } catch (error: IOException) {
        false
    }

    // 外に作った一意の名前の空のファイルを片付ける（作った直後に読んだ fileKey と同じときだけ。違う・無ければ何もしない）
    private fun removeEscaped(creating: Path, key: Any?) {
        if (key == null) return
        try {
            deleteVerified(creating) { aside -> attributes(aside)?.let { it.fileKey() == key && it.isRegularFile && it.size() == 0L } == true }
        } catch (error: Exception) {
            // 残るのは空の読み取り専用のファイルだけ
        }
    }

    private fun escapedBeforeWriting(path: Path): Nothing = unsafe(
        "${path.parent} was replaced by another program while createscript install created a file in it (L-5); nothing was written to the new file, and createscript install stopped. " +
            "If it could not be removed, an empty, read-only file named ${path.fileName} remains where the replaced directory pointed",
    )

    // 置き換えない名前の付け替え（S8.3 L-5）。target にハードリンクを作り（link は、既にある名前を置き換えずに失敗する）、source の名前を消す。
    // source と target は同じディレクトリなので、途中のディレクトリが差し替えられても、差し替え先に source が無ければ失敗する（何も置き換えない）。
    // ハードリンクを扱えないファイルシステム（FAT など。シンボリックリンクも作れない）だけ、これまでの移動にする
    fun renameNoReplace(source: Path, target: Path) {
        anchorFor(source)?.let { fs ->
            val id = fs.stat(source)?.id ?: throw NoSuchFileException(source.toString())
            fs.link(source, target, id)
            fs.remove(source, id)
            return
        }
        try {
            Files.createLink(target, source)
        } catch (error: UnsupportedOperationException) {
            moveNew(source, target)
            return
        } catch (error: FileSystemException) {
            if (error is FileAlreadyExistsException || error is NoSuchFileException || error is AccessDeniedException) throw error
            moveNew(source, target)
            return
        }
        Files.delete(source)
    }

    // このインストールが作ったディレクトリ（S8.3 L-5）。一意の名前 .dir-<UUID> で作って fileKey を owned に加え、意図した場所にあることを確かめてから、
    // 最終の名前に付け替える（同じディレクトリの中なので、差し替え先には一意の名前が無く、付け替えは失敗する）。target が既にあれば FileAlreadyExistsException
    fun createOwnedDirectory(target: Path, owned: MutableSet<String>?) {
        anchorFor(target)?.let { fs ->
            val id = fs.mkdir(target)
            owned?.add(id)
            return
        }
        if (owned == null) {
            createDirectory(target)
            return
        }
        if (exists(target)) throw FileAlreadyExistsException(target.toString())
        val creating = target.resolveSibling(".dir-${UUID.randomUUID()}")
        createDirectory(creating)
        val key = fileId(creating)
        if (!isExactly(creating)) unsafe("${target.parent} was replaced by another program while createscript install created a directory in it (L-5); createscript install stopped")
        key?.let(owned::add)
        moveNew(creating, target)
        if (!isExactly(target) || (key != null && fileId(target) != key)) unsafe("${target.parent} was replaced by another program while createscript install created a directory in it (L-5); createscript install stopped")
    }

    // 新しいファイル（既にあれば失敗）に書いて、ディスクに書き出す
    // S8.3 L-5: 作ったファイルが意図した場所にあることを確かめてから中身を書く（外に作られたなら空のまま止まる）
    fun createFile(path: Path, bytes: ByteArray, owned: MutableSet<String>? = null): Identity {
        if (anchored(path)) return writeNew(path, bytes, owned = owned)
        val key: Any?
        FileChannel.open(path, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS), *fileAttributes(path)).use { channel ->
            key = attributes(path)?.fileKey()
            if (!isExactly(path)) escapedBeforeWriting(path)
            // createFile の名前は一意でない（journal.tmp など）ため、外に作られたものは消さない（空のまま残る）
            fileId(path)?.let { owned?.add(it) }
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        return Identity(key, bytes.size.toLong(), sha256Hex(bytes))
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
        anchorFor(probe)?.let { fs ->
            val id = try {
                fs.write(probe, ByteArray(0), replace = false)
            } catch (error: IOException) {
                unsafe("cannot write to $directory")
            }
            return try {
                attributes(sidecar) != null
            } finally {
                try {
                    fs.remove(probe, id)
                    fs.stat(sidecar)?.takeIf { it.kind == 'f' }?.let { fs.remove(sidecar, it.id) }
                } catch (error: IOException) {
                    // 空のファイルが残るだけ
                }
            }
        }
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
        anchorFor(source)?.let { fs ->
            val id = fs.stat(source)?.id ?: throw NoSuchFileException(source.toString())
            fs.rename(source, target, id, replace = true)
            return
        }
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
    }

    // 置き換えない移動（ディレクトリ・ファイル）。target が既にあれば FileAlreadyExistsException
    fun moveNew(source: Path, target: Path) {
        anchorFor(source)?.let { fs ->
            val id = fs.stat(source)?.id ?: throw NoSuchFileException(source.toString())
            fs.rename(source, target, id, replace = false)
            return
        }
        Files.move(source, target)
    }

    // POSIX の権限を写す（新しいマニフェスト・ロックファイルを、前のファイルと同じ権限にする）
    fun copyPermissions(from: Path, to: Path) {
        // 固定したディレクトリでは、権限は書くときに決める（chmod でリンクの先を変えない）
        if (anchored(to)) return
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
        return deleteVerified(path) { candidate ->
            val again = try {
                readRegular(candidate, maxBytes)
            } catch (failure: InstallFailure) {
                null
            }
            again != null && again.identity.sha256 == sha256 && (file.identity.fileKey == null || again.identity.fileKey == file.identity.fileKey)
        }
    }

    // 確かめたものを消す（S8.3 L-5）。確かめた後に途中のディレクトリが差し替えられると、名前で消すと別の場所の同じ名前のものを消す。そこで、まず同じディレクトリの
    // 一意の名前 .delete-<UUID> に付け替え（置き換えない）、付け替えたものが確かめたもの（isOurs）であるときだけ消す。違えば元の名前に戻す（消さない）。
    // 戻せなかったときは、その名前を返す（利用者のものを消さず、名前を変えただけ）。消したか、無ければ null
    fun deleteVerified(path: Path, isOurs: (Path) -> Boolean): String? {
        anchorFor(path)?.let { fs ->
            // 固定したディレクトリの中の名前だけを消す（外のものには届かない）。確かめたものと、固定したディレクトリから見たものが同じときだけ
            val entry = fs.stat(path) ?: return null
            if (fileId(path) != entry.id || !isOurs(path)) return "another program changed it while it was deleted (L-5); it was not deleted"
            return try {
                fs.remove(path, entry.id)
                null
            } catch (error: DirectoryNotEmptyException) {
                "it is not empty"
            } catch (error: IOException) {
                "cannot delete it"
            }
        }
        val aside = path.resolveSibling(".delete-${UUID.randomUUID()}")
        try {
            moveNew(path, aside)
        } catch (error: NoSuchFileException) {
            return null
        } catch (error: IOException) {
            return "cannot delete it"
        }
        if (isOurs(aside)) {
            return try {
                Files.delete(aside)
                null
            } catch (error: DirectoryNotEmptyException) {
                restore(aside, path, "it is not empty")
            } catch (error: IOException) {
                restore(aside, path, "cannot delete it")
            }
        }
        return restore(aside, path, "another program replaced its directory while it was deleted (L-5); it was not deleted")
    }

    private fun restore(aside: Path, path: Path, reason: String): String = try {
        moveNew(aside, path)
        reason
    } catch (error: IOException) {
        "$reason, and it was left as ${aside.fileName} next to it"
    }

    // 空のディレクトリ（リンクでないこと）だけを消す。消さなかった理由を返す
    fun deleteEmptyDirectory(path: Path): String? {
        anchorFor(path)?.let { fs ->
            val entry = fs.stat(path) ?: return null
            if (entry.kind != 'd') return "it is not a directory"
            return try {
                fs.remove(path, entry.id)
                null
            } catch (error: DirectoryNotEmptyException) {
                "it is not empty"
            } catch (error: IOException) {
                "cannot delete it"
            }
        }
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

    // 今回作ったばかりの作業用のディレクトリを消す（ジャーナルを書く前の失敗のとき）。S8.3 L-5: 消すのは、このインストールが作ったもの（fileKey が owned にあるもの）だけで、
    // それぞれ deleteVerified で付け替えて確かめてから消す（途中のディレクトリが差し替えられても、差し替え先の利用者のファイルは消さない）。リンクはたどらない。
    // 消さなかったものを返す（残ったものは、次のインストールに影響しない: txn-* はジャーナルからだけ参照する）
    fun deleteOwnTree(path: Path, owned: Set<String>): List<String> {
        val kept = ArrayList<String>()
        val attributes = attributes(path) ?: return kept
        val key = currentId(path)
        if (key == null || key !in owned) {
            kept += "$path (it was not created by this installation)"
            return kept
        }
        if (attributes.isDirectory) {
            val children = try {
                Files.newDirectoryStream(path).use { stream -> stream.toList() }
            } catch (error: IOException) {
                return kept + "$path (cannot list it)"
            }
            for (child in children) kept += deleteOwnTree(child, owned)
        }
        deleteVerified(path) { aside -> fileId(aside) == key }?.let { kept += "$path ($it)" }
        return kept
    }
}
