package com.creatrbypc.createanalytical.install

import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributes

// ディレクトリの所有者と権限（POSIX のモード。unix:mode は sticky ビットも含む）。リンクはたどらずに読む
internal class DirectoryAttributes(val owner: String, val mode: Int, val symbolicLink: Boolean, val directory: Boolean)

// 所有者と権限を読む（読めなければ null: 確かめられない）。テストでは、読めない場合を渡す
internal fun interface PermissionReader {
    fun read(path: Path): DirectoryAttributes?

    companion object {
        val DEFAULT = PermissionReader { path ->
            try {
                val attributes = Files.readAttributes(path, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                val mode = Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS) as Int
                DirectoryAttributes(attributes.owner().name, mode, attributes.isSymbolicLink, attributes.isDirectory)
            } catch (error: UnsupportedOperationException) {
                null
            } catch (error: IllegalArgumentException) {
                null
            } catch (error: ClassCastException) {
                null
            } catch (error: IOException) {
                null
            }
        }
    }
}

// ほかのユーザーが、インストールの置き場所を差し替えられないことの確認（S8.2 L-5 の案 C）。
// 名前で行う操作は、途中のディレクトリをリンクに差し替えられると、プロジェクトの外へ向かう（L-5。Java 21 では防げない）。
// ほかのユーザーが途中のディレクトリを書き換えられなければ、その差し替えはできない。所有者と権限を変えられるのは、所有者と root だけなので、
// 始めに一度確かめれば、ほかのユーザーについては、確かめた後に変えられることも無い（同じユーザーのプログラムと root は防げない）。
// 規則（どれも、リンクをたどらずに読む）:
// - プロジェクトのルートと、その上のすべてのディレクトリ（/ まで）: このユーザーか root のもので、グループ・ほかのユーザーが書き込めない。
//   書き込めても、sticky ビットがあり（/tmp など）、次のディレクトリがこのユーザーか root のものなら、ほかのユーザーはそれを差し替えられないのでよい
// - .createscript と、その下のすべてのディレクトリ: このユーザーか root のもので、グループ・ほかのユーザーが書き込めない（リンクは、たどらずに飛ばす。
//   リンクは、ほかの確認が使わない・拒否する）
// - グループ・ほかのユーザーに x（中に入る権限）が無いディレクトリの下は、ほかのユーザーには届かないので、見ない
// - グループに書き込みの権限があるものは、グループにほかのユーザーがいるかを確かめられないので、書き込めるものとみなす
// - 所有者・権限を読めない（POSIX の属性が無いファイルシステム・Windows など）・今のユーザーが分からない・数が多すぎる: 確かめられないものとして扱う（呼ぶ側が拒否する）
// 限界: ACL（macOS の chmod +a など）は、Java 21 の標準 API では読めないので確かめない（docs/packages.md）。権限を直すことはしない（利用者が判断する）
internal class DirectoryPermissions(private val reader: PermissionReader, private val user: String?) {
    companion object {
        const val MAX_DIRECTORIES = 4096
        private const val OTHERS_WRITE = 0b000_010_010
        private const val OTHERS_SEARCH = 0b000_001_001
        private const val STICKY = 0b001_000_000_000

        // 今のユーザー（プロセスの持ち主。環境変数・プロパティは使わない）。分からなければ null
        fun currentUser(): String? = ProcessHandle.current().info().user().orElse(null)

        fun permissions(mode: Int): String {
            val bits = "rwxrwxrwx"
            val text = StringBuilder()
            for (i in 0 until 9) text.append(if (mode and (1 shl (8 - i)) != 0) bits[i] else '-')
            if (mode and STICKY != 0) text.setCharAt(8, if (mode and 1 != 0) 't' else 'T')
            return text.toString()
        }
    }

    private fun trusted(owner: String) = owner == user || owner == "root"

    private fun unconfirmed(path: Path) =
        "the owner and permissions of $path cannot be read, so createscript cannot confirm that no other user can change it (file systems without POSIX permissions, such as on Windows, are not supported for packages yet)"

    private fun writable(path: Path, mode: Int) =
        "$path can be changed by other users (permissions ${permissions(mode)}); another user could replace directories in it while packages are installed or checked. " +
            "If no other user should change it, remove their write permission yourself (for example: chmod go-w '$path'), or move the project to a directory that only you can change"

    private fun foreign(path: Path, owner: String) =
        "$path belongs to the user $owner, who could replace directories in it; move the project to a directory of your own"

    // プロジェクトのルート（実パス）と、その上のディレクトリ。問題があれば理由
    fun ancestorsProblem(root: Path): String? {
        if (user == null) return "the current user cannot be determined, so createscript cannot confirm that no other user can change the project"
        val chain = ArrayList<Path>()
        var current: Path? = root
        while (current != null) {
            chain.add(0, current)
            current = current.parent
        }
        for ((index, directory) in chain.withIndex()) {
            val attributes = reader.read(directory) ?: return unconfirmed(directory)
            if (attributes.symbolicLink || !attributes.directory) return "$directory is not a directory (createscript checks the real path of the project)"
            if (!trusted(attributes.owner)) return foreign(directory, attributes.owner)
            if (attributes.mode and OTHERS_WRITE == 0) continue
            // 書き込めるディレクトリでも、sticky ビットがあり、次（ルートなら .createscript）がこのユーザーか root のものなら、ほかのユーザーは差し替えられない
            val next = if (index + 1 < chain.size) chain[index + 1] else root.resolve(InstallLayout.DIRECTORY)
            val nextAttributes = reader.read(next)
            val protected = attributes.mode and STICKY != 0 && nextAttributes != null && !nextAttributes.symbolicLink && trusted(nextAttributes.owner)
            if (!protected) return writable(directory, attributes.mode)
        }
        return null
    }

    // top（.createscript）と、その下のすべてのディレクトリ。無ければ問題なし
    fun treeProblem(top: Path): String? {
        if (user == null) return "the current user cannot be determined, so createscript cannot confirm that no other user can change ${InstallLayout.DIRECTORY}"
        val pending = ArrayDeque<Path>()
        pending.add(top)
        var count = 0
        while (pending.isNotEmpty()) {
            val directory = pending.removeFirst()
            if (directory == top && !Files.exists(top, LinkOption.NOFOLLOW_LINKS)) return null
            val attributes = reader.read(directory) ?: return unconfirmed(directory)
            // リンクはたどらない（.createscript そのもののリンクは、ほかの確認が拒否する）
            if (attributes.symbolicLink || !attributes.directory) continue
            if (++count > MAX_DIRECTORIES) return "${InstallLayout.DIRECTORY} holds more than $MAX_DIRECTORIES directories, so createscript cannot confirm that no other user can change them"
            if (!trusted(attributes.owner)) return foreign(directory, attributes.owner)
            if (attributes.mode and OTHERS_WRITE != 0) return writable(directory, attributes.mode)
            // グループ・ほかのユーザーが中に入れない（x が無い）なら、その下は、ほかのユーザーには書き換えられない（読めなくても確かめ終わる）
            if (attributes.mode and OTHERS_SEARCH == 0) continue
            try {
                Files.newDirectoryStream(directory).use { stream ->
                    for (entry in stream) {
                        val child = reader.read(entry) ?: return unconfirmed(entry)
                        if (child.directory && !child.symbolicLink) pending.add(entry)
                    }
                }
            } catch (error: IOException) {
                return unconfirmed(directory)
            } catch (error: DirectoryIteratorException) {
                return unconfirmed(directory)
            }
        }
        return null
    }
}
