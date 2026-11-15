package com.creatrbypc.createanalytical.cli

import com.creatrbypc.createanalytical.install.ArchiveCache
import com.creatrbypc.createanalytical.install.FetchFailure
import com.creatrbypc.createanalytical.install.GitHubReleases
import com.creatrbypc.createanalytical.install.InstallFailure
import com.creatrbypc.createanalytical.install.Installer
import com.creatrbypc.createanalytical.install.PackageFetcher
import com.creatrbypc.createanalytical.install.PackageSpec
import com.creatrbypc.createanalytical.install.ReleaseStore
import com.creatrbypc.createanalytical.project.PackageArchives
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

// S8.2 L-7: Windows の予約名を、init・pack・install・run（ローカルの import とパッケージ）で受け付けない
class WindowsReservedNameCliTest {
    @TempDir
    lateinit var directory: Path

    private class Result(val code: Int, val out: String, val err: String)

    private class Releases : ReleaseStore {
        val assets = HashMap<String, ByteArray>()

        override fun download(source: String, version: String) = assets["$source@$version"] ?: throw FetchFailure.NotFound(URI("https://github.com/x"))

        override fun releaseInfo(source: String, version: String) = GitHubReleases.ReleaseInfo(assets["$source@$version"]?.let(GitHubReleases::sha256Hex), true)
    }

    private fun cli(vararg args: String): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = PrintStream(out, true, Charsets.UTF_8).use { o -> PrintStream(err, true, Charsets.UTF_8).use { e -> CreateScriptCli(o, e).execute(args.toList()) } }
        return Result(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    private fun application(name: String, main: String): Path {
        val root = Files.createDirectories(directory.resolve(name))
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("createscript.json"), """{"schemaVersion": 1, "name": "app", "version": "0.1.0", "entry": "src/main.cscr"}""")
        Files.writeString(root.resolve("src/main.cscr"), main)
        return root
    }

    // ---- init ----

    @Test
    fun initRefusesReservedNames() {
        for (name in listOf("con", "aux", "com0", "lpt9")) {
            val result = cli("init", directory.resolve("named-$name").toString(), "--name", name)
            assertEquals(ExitCode.USAGE, result.code, name)
            assertTrue(result.err.contains("invalid package name: $name; '$name' is a reserved name on Windows"), result.err)
        }
        // ディレクトリの名前から付ける名前（macOS・Linux では、その名前のディレクトリを作れる）
        val dir = Files.createDirectories(directory.resolve("nul"))
        val fromDirectory = cli("init", dir.toString())
        assertEquals(ExitCode.USAGE, fromDirectory.code)
        assertTrue(fromDirectory.err.contains("invalid package name: nul (from the directory name); use --name <name>; 'nul' is a reserved name on Windows"), fromDirectory.err)
        assertFalse(Files.exists(dir.resolve("createscript.json")))
        // 予約名で始まるだけの名前は使える
        assertEquals(ExitCode.OK, cli("init", directory.resolve("console").toString()).code)
    }

    // ---- pack ----

    private fun library(name: String, modules: List<String>, files: Map<String, String>): Path {
        val root = Files.createDirectories(directory.resolve(name))
        Files.writeString(root.resolve("createscript.json"), """{"schemaVersion": 2, "kind": "library", "name": "tools", "version": "1.0.0", "modules": [${modules.joinToString(", ") { "\"$it\"" }}]}""")
        Files.createDirectories(root.resolve("modules"))
        for ((file, text) in files) Files.writeString(root.resolve("modules/$file"), text)
        return root
    }

    @Test
    fun packRefusesReservedModuleNames() {
        val one = "fun one(): Int {\n    return 1\n}\n"
        // 公開するモジュール（マニフェストの modules）
        val listed = cli("pack", library("listed", listOf("Con"), mapOf("Con.cscr" to one)).toString())
        assertEquals(ExitCode.INVALID_INPUT, listed.code, listed.err)
        assertTrue(listed.err.contains("error CS_PKG_RESERVED_NAME (\"Con\")"), listed.err)
        // 公開しないモジュール（modules/ の中のファイル）
        val hidden = library("hidden", listOf("tools"), mapOf("tools.cscr" to one, "aux.cscr" to one))
        val result = cli("pack", hidden.toString())
        assertEquals(ExitCode.INVALID_INPUT, result.code, result.err)
        assertTrue(result.err.contains("CS_PKG_ARCHIVE_INVALID_PATH") && result.err.contains("'aux' is a reserved name on Windows"), result.err)
        assertFalse(Files.exists(hidden.resolve(PackageArchives.FILE_NAME)))
        // 予約名でないモジュールは、これまでどおり
        assertEquals(ExitCode.OK, cli("pack", library("fine", listOf("tools"), mapOf("tools.cscr" to one, "console.cscr" to one)).toString()).code)
    }

    // ---- install ----

    @Test
    fun installRefusesPackagesWithReservedNames() {
        val releases = Releases()
        val one = "fun one(): Int {\n    return 1\n}\n".toByteArray()
        fun archive(name: String, module: String) = PackageArchives.write(
            mapOf(
                "createscript.json" to """{"schemaVersion": 2, "kind": "library", "name": "$name", "version": "1.0.0", "modules": ["$module"]}""".toByteArray(),
                "modules/$module.cscr" to one,
            ),
        )
        releases.assets["github:owner/printer@1.0.0"] = archive("prn", "printer")
        releases.assets["github:owner/ports@1.0.0"] = archive("ports", "COM3")
        releases.assets["github:owner/fine@1.0.0"] = archive("fine", "console")
        val root = application("install-app", "println(1)\n")
        val installer = Installer(root, PackageFetcher(releases, ArchiveCache(directory.resolve("cache"))))
        for ((source, expected) in mapOf("github:owner/printer" to "CS_PKG_RESERVED_NAME (\"prn\")", "github:owner/ports" to "'COM3' is a reserved name on Windows")) {
            val failure = assertThrows(InstallFailure::class.java) { installer.install(PackageSpec(source, "1.0.0"), false) }
            assertEquals(InstallFailure.Kind.INVALID, failure.kind, source)
            assertTrue(failure.message!!.contains("is not a valid package") && failure.message!!.contains(expected), failure.message)
            assertFalse(Files.exists(root.resolve("createscript.lock")), source)
        }
        assertEquals(1, installer.install(PackageSpec("github:owner/fine", "1.0.0"), false).packages.size)
    }

    // ---- run ----

    // プロジェクトモード: ローカルのモジュールの import（macOS・Linux では、その名前のファイルを作れる）
    @Test
    fun runRefusesLocalModulesWithReservedNames() {
        for (name in listOf("con", "NUL", "Aux", "com0", "LPT1")) {
            val root = application("local-$name", "import $name\nprintln($name.one())\n")
            Files.writeString(root.resolve("src/$name.cscr"), "fun one(): Int {\n    return 1\n}\n")
            val result = cli("run", root.toString())
            assertEquals(ExitCode.COMPILE_ERROR, result.code, "$name: ${result.err}")
            assertTrue(result.err.contains("CS_MODULE_UNREADABLE") && result.err.contains("'$name' is a reserved file name on Windows"), result.err)
            assertEquals("", result.out)
            // ファイルを指定した実行（第8.0段階のローカルの import）も同じ
            val file = cli("run", root.resolve("src/main.cscr").toString())
            assertEquals(ExitCode.COMPILE_ERROR, file.code, file.err)
            assertTrue(file.err.contains("'$name' is a reserved file name on Windows"), file.err)
        }
        // 予約名で始まるだけの名前は使える
        val root = application("local-fine", "import console\nprintln(console.one())\n")
        Files.writeString(root.resolve("src/console.cscr"), "fun one(): Int {\n    return 1\n}\n")
        assertEquals("1\n", cli("run", root.toString()).out)
    }

    // ロックファイル・マニフェストに予約名（手で書いたもの）: 確かめる段階で止まり、何も実行しない
    @Test
    fun runRefusesReservedNamesInTheProjectFiles() {
        val root = application("files", "println(1)\n")
        Files.writeString(root.resolve("createscript.json"), """{"schemaVersion": 1, "name": "aux", "version": "0.1.0", "entry": "src/main.cscr"}""")
        val manifest = cli("run", root.toString())
        assertEquals(ExitCode.INVALID_INPUT, manifest.code)
        assertTrue(manifest.err.contains("error CS_PKG_RESERVED_NAME (\"aux\")"), manifest.err)
        // エントリーのパスが予約名
        Files.writeString(root.resolve("createscript.json"), """{"schemaVersion": 1, "name": "app", "version": "0.1.0", "entry": "src/con.cscr"}""")
        Files.writeString(root.resolve("src/con.cscr"), "println(2)\n")
        val entry = cli("run", root.toString())
        assertEquals(ExitCode.INVALID_INPUT, entry.code)
        assertTrue(entry.err.contains("CS_PKG_INVALID_PATH") && entry.err.contains("'con.cscr' is a reserved name on Windows"), entry.err)
        assertEquals("", entry.out)
        // v0.1.0-alpha と同じファイルの実行（import 無し）は、ファイルの名前を問わない
        assertEquals("2\n", cli("run", root.resolve("src/con.cscr").toString()).out)
    }
}
