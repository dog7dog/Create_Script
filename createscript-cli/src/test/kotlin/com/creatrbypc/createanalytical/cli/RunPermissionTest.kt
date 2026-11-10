package com.creatrbypc.createanalytical.cli

import com.creatrbypc.createanalytical.install.ArchiveCache
import com.creatrbypc.createanalytical.install.Installer
import com.creatrbypc.createanalytical.install.PackageFetcher
import com.creatrbypc.createanalytical.install.PackageSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

// S8.2 L-5 の案 C: run（プロジェクトモード）は、ほかのユーザーが書き換えられるディレクトリにあるパッケージを使わない
class RunPermissionTest {
    @TempDir
    lateinit var directory: Path

    private fun cli(vararg args: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = PrintStream(out, true, Charsets.UTF_8).use { o -> PrintStream(err, true, Charsets.UTF_8).use { e -> CreateScriptCli(o, e).execute(args.toList()) } }
        return Triple(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    private fun project(name: String, main: String): Path {
        val root = Files.createDirectories(directory.resolve(name))
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("createscript.json"), """{"schemaVersion": 1, "name": "app", "version": "0.1.0", "entry": "src/main.cscr"}""")
        Files.writeString(root.resolve("src/main.cscr"), main)
        return root
    }

    private fun mode(path: Path) = Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS) as Int and 0b111_111_111

    @Test
    fun runRefusesPackagesThatOtherUsersCanChange() {
        val root = project("app", "import numbers\nprintln(numbers.v())\n")
        Installer(root, PackageFetcher(NumbersReleases(), ArchiveCache(directory.resolve("cache")))).install(PackageSpec("github:owner/numbers", "1.0.0"), false)
        assertEquals("1\n", cli("run", root.toString()).second)
        val packages = root.resolve(".createscript/packages")
        Files.setPosixFilePermissions(packages, PosixFilePermissions.fromString("rwxrwxrwx"))
        val (code, out, err) = cli("run", root.toString())
        assertEquals(ExitCode.INVALID_INPUT, code, err)
        assertEquals("", out)
        assertTrue(err.contains("${packages.toRealPath()} can be changed by other users (permissions rwxrwxrwx)") && err.contains("The packages were not used"), err)
        assertEquals(0b111_111_111, mode(packages), "the permissions were not changed")
        Files.setPosixFilePermissions(packages, PosixFilePermissions.fromString("rwxr-xr-x"))
        assertEquals("1\n", cli("run", root.toString()).second)
    }

    // パッケージを使わないプロジェクト・ファイルを指定した実行（v0.1.0-alpha と同じ）は、これまでどおり
    @Test
    fun projectsWithoutPackagesAndSingleFilesAreUnchanged() {
        val root = project("plain", "println(7)\n")
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxrwxrwx"))
        try {
            assertEquals(Triple(ExitCode.OK, "7\n", ""), cli("run", root.toString()))
            assertEquals(Triple(ExitCode.OK, "7\n", ""), cli("run", root.resolve("src/main.cscr").toString()))
        } finally {
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }
}
