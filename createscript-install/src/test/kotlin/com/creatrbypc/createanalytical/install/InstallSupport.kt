package com.creatrbypc.createanalytical.install

import com.creatrbypc.createanalytical.project.PackageArchives
import org.junit.jupiter.api.Assertions.assertEquals
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

// テスト用のメモリーの中の GitHub Releases（照合は本物の PackageFetcher が行う）。network が false なら、使われた時点でテストを失敗させる
internal class MemoryReleases : ReleaseStore {
    val assets = HashMap<String, ByteArray>()
    val downloads = ArrayList<String>()
    var network = true
    var immutable: Boolean? = true

    fun publish(source: String, version: String, bytes: ByteArray) {
        assets["$source@$version"] = bytes
    }

    override fun download(source: String, version: String): ByteArray {
        check(network) { "the network was used: $source $version" }
        downloads += "$source@$version"
        return assets["$source@$version"] ?: throw FetchFailure.NotFound(URI("https://github.com/${source.removePrefix("github:")}/releases/download/v$version/createscript-package.zip"))
    }

    override fun releaseInfo(source: String, version: String): GitHubReleases.ReleaseInfo {
        check(network) { "the network was used: $source $version" }
        return GitHubReleases.ReleaseInfo(assets["$source@$version"]?.let(GitHubReleases::sha256Hex), immutable)
    }
}

// 中断（プロセスの停止）の代わり。Exception ではないので、インストールの途中の取り消し（catch Exception）は動かない
internal class SimulatedCrash(step: String) : Error("simulated crash at $step")

internal object Packages {
    // ライブラリのパッケージアーカイブ。dependencies は name → (version, source)
    fun library(name: String, version: String, dependencies: Map<String, Pair<String, String>> = emptyMap(), module: String = name.replace('-', '_'), body: String = "fun one(): Int {\n    return 1\n}\n"): ByteArray {
        val deps = dependencies.entries.joinToString(", ") { (n, vs) -> "\"$n\": {\"version\": \"${vs.first}\", \"source\": \"${vs.second}\"}" }
        val manifest = """{"schemaVersion": 2, "kind": "library", "name": "$name", "version": "$version", "license": "MIT", "modules": ["$module"], "dependencies": {$deps}}"""
        return PackageArchives.write(
            mapOf(
                "createscript.json" to manifest.toByteArray(),
                "modules/$module.cscr" to body.toByteArray(),
                "LICENSE" to "MIT License\n".toByteArray(),
            ),
        )
    }

    fun source(name: String) = "github:owner/$name"
}

// プロジェクトのディレクトリ（createscript.json と src/main.cscr）
internal fun createProject(root: Path, manifest: String = """{"schemaVersion": 1, "name": "app", "version": "0.1.0", "entry": "src/main.cscr"}"""): Path {
    Files.createDirectories(root.resolve("src"))
    Files.writeString(root.resolve("createscript.json"), manifest)
    Files.writeString(root.resolve("src/main.cscr"), "println(\"hi\")\n")
    return root
}

// ディレクトリの中身（リンクをたどらない）: パス → 種類・SHA-256・fileKey。既存のファイルが書き換えられていないこと（内容も、ファイルそのものも）を比べる
internal fun snapshot(root: Path): Map<String, String> {
    val result = java.util.TreeMap<String, String>()
    Files.walk(root).use { stream ->
        for (path in stream) {
            if (path == root) continue
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            val relative = root.relativize(path).toString()
            result[relative] = when {
                attributes.isSymbolicLink -> "link:${Files.readSymbolicLink(path)}"
                attributes.isDirectory -> "dir"
                else -> "file:${GitHubReleases.sha256Hex(Files.readAllBytes(path))}:${attributes.fileKey()}"
            }
        }
    }
    return result
}

// 失敗の後: .createscript と、空の install.lock（ロックのためのファイル）のほかは、何も変わっていないこと
internal fun assertUnchanged(before: Map<String, String>, root: Path) {
    val allowed = setOf(".createscript", ".createscript/install.lock")
    val after = snapshot(root).filterKeys { key -> key in before || key !in allowed }
    assertEquals(before, after)
    root.resolve(".createscript/install.lock").takeIf { Files.exists(it) }?.let { assertEquals(0, Files.size(it)) }
}

// パッケージの中身（印の transaction を除く）と、プロジェクトのファイル。中断の後の結果を、中断しなかった結果と比べる
internal fun contents(root: Path): Map<String, String> {
    val result = java.util.TreeMap<String, String>()
    Files.walk(root).use { stream ->
        for (path in stream) {
            val relative = root.relativize(path).toString()
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || relative == ".createscript/install.lock") continue
            val bytes = Files.readAllBytes(path)
            result[relative] = if (path.fileName.toString() == InstallLayout.MARKER) {
                Marker.parse(bytes)!!.copy(transaction = "00000000-0000-0000-0000-000000000000").toString()
            } else {
                GitHubReleases.sha256Hex(bytes)
            }
        }
    }
    return result
}

// 別のプロセスで install.lock のロックを取り、標準入力が閉じるまで持ち続ける（同時インストールのテスト）
object LockHolder {
    @JvmStatic
    fun main(args: Array<String>) {
        FileChannel.open(Paths.get(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            val lock = channel.tryLock()
            if (lock == null) {
                println("busy")
                return
            }
            println("locked")
            System.out.flush()
            while (System.`in`.read() != -1) {
                // 閉じるまで待つ
            }
            lock.release()
        }
    }
}
