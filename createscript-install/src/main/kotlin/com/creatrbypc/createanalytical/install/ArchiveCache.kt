package com.creatrbypc.createanalytical.install

import com.creatrbypc.createanalytical.project.ProjectRules
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

// パッケージアーカイブのキャッシュ（S8.2 Phase 2）。SHA-256 を名前にした内容で番地を決める置き場所で、信頼しない（読むたびに SHA-256 を確かめる）。
// 置き場所: <root>/archives/sha256/<小文字の 16 進数 64 桁>.zip
// - root 自体は、利用者が別のディスクへのリンクにしていてもよい（たどった先を使う）。その下の archives・sha256 は、リンクでないディレクトリでなければ使わない
// - 項目は、リンクでない通常のファイルで、名前の SHA-256 と中身が一致するものだけを使う。そうでないもの（リンク・壊れた中身）は使わず、消しも上書きもしない
// - 作るディレクトリは所有者だけが使える 0700（POSIX のファイルシステムのとき）。ファイルは一時ファイルに書いてから、置き換えないハードリンクで名前を付ける
class ArchiveCache(root: Path, private val maxBytes: Long = NetworkLimits().maxDownloadBytes) {
    private val root: Path = root.toAbsolutePath().normalize()

    sealed interface Lookup {
        class Hit(val bytes: ByteArray) : Lookup

        data object Miss : Lookup

        // 使えない（理由）。キャッシュを使わずに続ける
        class Unusable(val reason: String) : Lookup
    }

    companion object {
        // 既定の置き場所: 環境変数 CREATESCRIPT_CACHE_DIR、macOS は ~/Library/Caches/CreateScript、それ以外は $XDG_CACHE_HOME/createscript か ~/.cache/createscript。
        // （キャッシュの中身は毎回 SHA-256 で確かめるため、置き場所を変えても安全性は変わらない）
        fun defaultRoot(environment: Map<String, String> = System.getenv(), osName: String = System.getProperty("os.name"), home: String = System.getProperty("user.home")): Path {
            environment["CREATESCRIPT_CACHE_DIR"]?.takeIf { it.isNotBlank() }?.let { return Paths.get(it) }
            if (osName.lowercase().startsWith("mac")) return Paths.get(home, "Library", "Caches", "CreateScript")
            environment["XDG_CACHE_HOME"]?.takeIf { it.isNotBlank() }?.let { return Paths.get(it, "createscript") }
            return Paths.get(home, ".cache", "createscript")
        }
    }

    // アーカイブのディレクトリ（無ければ作る。使えなければ理由を返す）
    private fun directory(create: Boolean): Pair<Path?, String?> {
        val base = try {
            if (!Files.exists(root)) {
                if (!create) return null to null
                createPrivateDirectories(root)
            }
            root.toRealPath()
        } catch (error: IOException) {
            return null to "cannot use the cache directory $root"
        }
        if (!Files.isDirectory(base)) return null to "the cache location $root is not a directory"
        var current = base
        for (part in listOf("archives", "sha256")) {
            current = current.resolve(part)
            if (Files.isSymbolicLink(current)) return null to "$current is a symbolic link"
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (!create) return null to null
                try {
                    createPrivateDirectory(current)
                } catch (error: FileAlreadyExistsException) {
                    // 同時に作られた
                } catch (error: IOException) {
                    return null to "cannot create $current"
                }
            }
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) return null to "$current is not a directory"
        }
        return current to null
    }

    fun read(sha256: String): Lookup {
        require(ProjectRules.isValidSha256(sha256)) { "invalid SHA-256: $sha256" }
        val (folder, problem) = directory(create = false)
        if (problem != null) return Lookup.Unusable(problem)
        val path = folder?.resolve("$sha256.zip") ?: return Lookup.Miss
        val before = attributes(path) ?: return Lookup.Miss
        if (!before.isRegularFile) return Lookup.Unusable("$path is not a regular file")
        if (before.size() > maxBytes) return Lookup.Unusable("$path is too large")
        val bytes = try {
            Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(maxBytes.toInt() + 1) }
        } catch (error: IOException) {
            return Lookup.Unusable("cannot read $path")
        }
        val after = attributes(path)
        if (after == null || after.fileKey() != before.fileKey() || after.size() != before.size()) return Lookup.Unusable("$path changed while it was read")
        if (GitHubReleases.sha256Hex(bytes) != sha256) return Lookup.Unusable("$path does not match its SHA-256")
        return Lookup.Hit(bytes)
    }

    // 書いて null を返す。書けなければ（使えない置き場所・壊れた既存の項目）理由を返す（キャッシュに書けないことは、取得の失敗にしない）
    fun write(sha256: String, bytes: ByteArray): String? {
        require(ProjectRules.isValidSha256(sha256) && GitHubReleases.sha256Hex(bytes) == sha256) { "the bytes do not match $sha256" }
        val (folder, problem) = directory(create = true)
        if (problem != null || folder == null) return problem ?: "cannot use the cache"
        val target = folder.resolve("$sha256.zip")
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return when (val existing = read(sha256)) {
                is Lookup.Hit -> null
                is Lookup.Unusable -> "the cache entry is unusable and was not replaced: ${existing.reason}"
                Lookup.Miss -> null
            }
        }
        val temporary = folder.resolve(".tmp-${UUID.randomUUID()}")
        try {
            FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            try {
                Files.createLink(target, temporary)
            } catch (error: FileAlreadyExistsException) {
                return null
            } catch (error: UnsupportedOperationException) {
                Files.move(temporary, target)
            } catch (error: IOException) {
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return null
                Files.move(temporary, target)
            }
            return null
        } catch (error: IOException) {
            return "cannot write the cache entry $target"
        } finally {
            try {
                Files.deleteIfExists(temporary)
            } catch (error: IOException) {
                // 一時ファイルが残るだけ
            }
        }
    }

    private fun attributes(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (error: NoSuchFileException) {
        null
    } catch (error: IOException) {
        null
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
        if (path.fileSystem.supportedFileAttributeViews().contains("posix")) {
            Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        } else {
            Files.createDirectory(path)
        }
    }
}
