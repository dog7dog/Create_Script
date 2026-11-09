package com.creatrbypc.createanalytical.cli

import com.creatrbypc.createanalytical.install.InstallLayout
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

// createscript run（プロジェクトモード）が、マニフェスト・ロックファイル・インストール済みのパッケージを確かめる間、
// .createscript/install.lock の共有ロックを持つ（S8.2 L-8）。createscript install は、インストールの間ずっと同じファイルの排他ロックを持つ。
// - 共有ロックを取れた（HELD）: インストールは動いていない。ジャーナルがあれば、中断したインストール
// - ほかのプロセス（tryLock が null）か、このプロセス（OverlappingFileLockException）がロックを持つ: インストールが動いている（Busy、終了コード 75）
// - install.lock が無い（NO_LOCK_FILE）: install は、ジャーナルを書く前に必ず install.lock を作るので、インストールは動いていない
//   （確かめた後に始まったインストールは、確かめた結果が合わなかったときに looksBusy で見分ける）
// - 開けない・ロックを扱えないファイルシステム（NOT_CHECKED）: インストールが動いているか分からない。安全とはみなさない:
//   ジャーナルがあれば「動いているか中断したか分からない」として止まる。無ければ、これまでどおり確かめる（確かめたバイト列だけを実行する）
// ファイルは作らず・書かない（読むために開くだけ）。ロックは、確かめ終えたら（コンパイル・実行の前に）外す
internal class RunLock private constructor(private val channel: FileChannel?, private val lock: FileLock?, val state: State, val reason: String?) : AutoCloseable {
    enum class State { HELD, NO_LOCK_FILE, NOT_CHECKED }

    class Busy(message: String) : Exception(message)

    // 共有ロックを試す（テストでは、ロックを扱えない場合を渡す）
    fun interface Locker {
        fun tryShared(channel: FileChannel): FileLock?
    }

    override fun close() {
        try {
            lock?.release()
        } catch (error: IOException) {
            // チャネルを閉じればロックも外れる
        }
        try {
            channel?.close()
        } catch (error: IOException) {
            // 読むために開いただけ
        }
    }

    companion object {
        val DEFAULT = Locker { channel -> channel.tryLock(0, Long.MAX_VALUE, true) }

        private const val SHOWN = "${InstallLayout.DIRECTORY}/${InstallLayout.INSTALL_LOCK}"

        fun busyMessage() = "createscript install is running in this project ($SHOWN is locked); try again when it has finished"

        fun acquire(root: Path, locker: Locker = DEFAULT): RunLock {
            val path = root.resolve(InstallLayout.DIRECTORY).resolve(InstallLayout.INSTALL_LOCK)
            val attributes = try {
                Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (error: NoSuchFileException) {
                return RunLock(null, null, State.NO_LOCK_FILE, null)
            } catch (error: IOException) {
                return RunLock(null, null, State.NOT_CHECKED, "cannot read $SHOWN")
            }
            if (attributes.isSymbolicLink || !attributes.isRegularFile) {
                throw ProjectCommands.Failure(ExitCode.INVALID_INPUT, "$SHOWN is not a regular file; createscript install uses it to show that it is running. Remove it if no installation is running")
            }
            val channel = try {
                FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
            } catch (error: NoSuchFileException) {
                return RunLock(null, null, State.NO_LOCK_FILE, null)
            } catch (error: IOException) {
                return RunLock(null, null, State.NOT_CHECKED, "cannot open $SHOWN for reading (${error.javaClass.simpleName})")
            }
            val lock = try {
                locker.tryShared(channel)
            } catch (error: OverlappingFileLockException) {
                channel.close()
                throw Busy(busyMessage())
            } catch (error: IOException) {
                channel.close()
                return RunLock(null, null, State.NOT_CHECKED, "the file system did not lock $SHOWN (${error.message ?: error.javaClass.simpleName})")
            } catch (error: UnsupportedOperationException) {
                channel.close()
                return RunLock(null, null, State.NOT_CHECKED, "the file system does not support locks")
            }
            if (lock == null) {
                channel.close()
                throw Busy(busyMessage())
            }
            return RunLock(channel, lock, State.HELD, null)
        }

        // 今、インストールが動いているか（ロックを試してすぐに外す。分からなければ false）
        fun looksBusy(root: Path, locker: Locker = DEFAULT): Boolean = try {
            acquire(root, locker).close()
            false
        } catch (busy: Busy) {
            true
        } catch (failure: ProjectCommands.Failure) {
            false
        }
    }
}
