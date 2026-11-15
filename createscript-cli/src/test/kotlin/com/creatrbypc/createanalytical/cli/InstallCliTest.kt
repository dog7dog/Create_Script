package com.creatrbypc.createanalytical.cli

import com.creatrbypc.createanalytical.install.ArchiveCache
import com.creatrbypc.createanalytical.install.FetchFailure
import com.creatrbypc.createanalytical.install.GitHubReleases
import com.creatrbypc.createanalytical.install.InstallFailure
import com.creatrbypc.createanalytical.install.PackageFetcher
import com.creatrbypc.createanalytical.install.ReleaseStore
import com.creatrbypc.createanalytical.project.PackageArchives
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

// S8.2 Phase 3: createscript install（引数・表示・終了コード）と、中断したインストールがあるときの manifest check・lock check・pack
class InstallCliTest {
    @TempDir
    lateinit var directory: Path

    private class Result(val code: Int, val out: String, val err: String)

    // メモリーの中の Release（照合は本物の PackageFetcher が行う）
    private class Releases : ReleaseStore {
        val assets = HashMap<String, ByteArray>()
        var immutable: Boolean? = false

        override fun download(source: String, version: String) = assets["$source@$version"] ?: throw FetchFailure.NotFound(URI("https://github.com/x"))

        override fun releaseInfo(source: String, version: String) = GitHubReleases.ReleaseInfo(assets["$source@$version"]?.let(GitHubReleases::sha256Hex), immutable)
    }

    private val releases = Releases()
    private lateinit var project: Path

    @BeforeEach
    fun setUp() {
        project = Files.createDirectories(directory.resolve("app"))
        Files.writeString(project.resolve("createscript.json"), """{"schemaVersion": 1, "name": "app", "version": "0.1.0", "entry": "src/main.cscr"}""")
        Files.createDirectories(project.resolve("src"))
        Files.writeString(project.resolve("src/main.cscr"), "println(1)\n")
        val manifest = """{"schemaVersion": 2, "kind": "library", "name": "vector-math", "version": "1.2.0", "modules": ["vector_math"]}"""
        releases.assets["github:owner/vector-math@1.2.0"] = PackageArchives.write(
            mapOf("createscript.json" to manifest.toByteArray(), "modules/vector_math.cscr" to "fun one(): Int {\n    return 1\n}\n".toByteArray()),
        )
    }

    private fun capture(block: (PrintStream, PrintStream) -> Int): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = PrintStream(out, true, Charsets.UTF_8).use { o -> PrintStream(err, true, Charsets.UTF_8).use { e -> block(o, e) } }
        return Result(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    private fun install(vararg args: String): Result = capture { o, e ->
        InstallCommands(o, e) { PackageFetcher(releases, ArchiveCache(directory.resolve("cache"))) to null }.install(args.toList())
    }

    private fun cli(vararg args: String): Result = capture { o, e -> CreateScriptCli(o, e).execute(args.toList()) }

    @Test
    fun installsAndReports() {
        val result = install("github:owner/vector-math@v1.2.0", project.toString())
        assertEquals(ExitCode.OK, result.code, result.err)
        val sha = GitHubReleases.sha256Hex(releases.assets.values.single())
        assertEquals(
            listOf(
                "Installed vector-math 1.2.0 from github:owner/vector-math -> .createscript/packages/vector-math/1.2.0",
                "  sha256 $sha",
                "  downloaded; recorded on first use (trust on first use); GitHub digest matched; the release is not immutable",
                "Updated createscript.json (dependency vector-math 1.2.0 from github:owner/vector-math)",
                "Wrote createscript.lock (1 packages)",
                "Note: add /.createscript/ to .gitignore; it holds installed packages and should not be committed",
            ),
            result.out.lines().dropLast(1),
        )
        // S8.3 Phase 3: 公式のレジストリはまだ無い（本番の鍵が無い）ので、審査の状態は「確かめられない」（未審査とは言わない）
        assertEquals(
            "warning: vector-math 1.2.0: the release is not immutable; its owner can still replace its assets\n" +
                "warning: the review status of github:owner/vector-math@v1.2.0 could not be checked: the official CreateScript-Registry has not been published yet: " +
                "no registry signing key is built into this createscript\n",
            result.err,
        )
        // 再実行（キャッシュから。.gitignore があれば案内しない）
        Files.writeString(project.resolve(".gitignore"), "build/\n/.createscript/\n")
        val again = install(project.toString())
        assertEquals(ExitCode.OK, again.code, again.err)
        assertTrue(again.out.contains("Already installed vector-math 1.2.0 from github:owner/vector-math\n  sha256 $sha\n  from the cache; matches createscript.lock\n"), again.out)
        assertTrue(again.out.endsWith("Up to date (1 packages)\n"), again.out)
        // オフラインでも同じ
        assertEquals(ExitCode.OK, install(project.toString(), "--offline").code)
        // 依存の無いプロジェクト
        val empty = Files.createDirectories(directory.resolve("empty"))
        Files.writeString(empty.resolve("createscript.json"), """{"schemaVersion": 2, "kind": "library", "name": "empty", "version": "1.0.0", "modules": ["empty"]}""")
        assertEquals("Nothing to install (no dependencies)\n", install(empty.toString()).out)
        assertFalse(Files.exists(empty.resolve("createscript.lock")))
    }

    @Test
    fun exitCodes() {
        // 66: プロジェクトが無い
        assertEquals(ExitCode.NO_INPUT, install(directory.resolve("missing").toString()).code)
        // 69: オフラインでキャッシュに無い
        val offline = install("github:owner/vector-math@v1.2.0", project.toString(), "--offline")
        assertEquals(ExitCode.UNAVAILABLE, offline.code)
        assertTrue(offline.err.startsWith("createscript: "), offline.err)
        // 65: 見つからない・検査の失敗
        assertEquals(ExitCode.INVALID_INPUT, install("github:owner/absent@v1.0.0", project.toString()).code)
        // 73: シンボリックリンク
        val linked = Files.createDirectories(directory.resolve("linked"))
        Files.copy(project.resolve("createscript.json"), linked.resolve("createscript.json"))
        Files.createSymbolicLink(linked.resolve(".createscript"), Files.createDirectories(directory.resolve("elsewhere")))
        assertEquals(ExitCode.CANNOT_CREATE, install(linked.toString()).code)
        // 75: ほかのインストール（対応だけを確かめる。動作は createscript-install の InstallTest）
        assertEquals(ExitCode.TEMPORARY_FAILURE, InstallCommands.exitCode(InstallFailure.Kind.BUSY))
        assertEquals(listOf(69, 65, 66, 73, 75), InstallFailure.Kind.entries.map(InstallCommands::exitCode))
        // 何も変えていない
        assertFalse(Files.exists(project.resolve("createscript.lock")))
        assertEquals("""{"schemaVersion": 1, "name": "app", "version": "0.1.0", "entry": "src/main.cscr"}""", Files.readString(project.resolve("createscript.json")))
    }

    @Test
    fun usageErrors() {
        for ((args, message) in listOf(
            listOf("install", "github:owner/repo") to "invalid package: github:owner/repo",
            listOf("install", "github:owner/repo@^1.0.0") to "invalid package",
            listOf("install", "github:a/b@v1.0.0", "github:c/d@v1.0.0") to "only one package can be given",
            listOf("install", "--update") to "unknown option: --update",
            listOf("install", "--offline", "--offline") to "duplicate option: --offline",
            listOf("install", "a", "b") to "unexpected argument: b",
        )) {
            val result = cli(*args.toTypedArray())
            assertEquals(ExitCode.USAGE, result.code, "$args")
            assertTrue(result.err.contains(message), "$args: ${result.err}")
            assertEquals("", result.out)
        }
        assertTrue(cli("--help").out.contains("createscript install [github:<owner>/<repo>@v<version>] [directory] [--offline]"))
        assertTrue(cli("--help").out.contains("69 network unavailable (install)"))
    }

    // 中断したインストールのジャーナルがあれば、manifest check・lock check・pack は確かめずに止まる
    @Test
    fun checksStopWhileAnInstallationIsInterrupted() {
        Files.createDirectories(project.resolve(".createscript"))
        Files.writeString(project.resolve(".createscript/transaction.json"), "{}")
        for (command in listOf(listOf("manifest", "check"), listOf("lock", "check"), listOf("pack"))) {
            val result = cli(*(command + project.toString()).toTypedArray())
            assertEquals(ExitCode.INVALID_INPUT, result.code, "$command")
            assertTrue(result.err.contains("an installation was interrupted") && result.err.contains("run createscript install to finish or undo it"), result.err)
        }
        // 壊れたジャーナルは install も使わない（手で確かめる手順を示す）
        val result = install(project.toString())
        assertEquals(ExitCode.CANNOT_CREATE, result.code)
        assertTrue(result.err.contains("cannot be finished or undone safely") && result.err.contains("by hand"), result.err)
        assertEquals("{}", Files.readString(project.resolve(".createscript/transaction.json")))
    }
}
