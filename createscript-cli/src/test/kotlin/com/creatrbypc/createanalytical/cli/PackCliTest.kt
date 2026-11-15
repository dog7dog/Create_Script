package com.creatrbypc.createanalytical.cli

import com.creatrbypc.createanalytical.project.PackageArchives
import com.creatrbypc.createanalytical.project.ProjectRules
import com.creatrbypc.createanalytical.script.compiler.CompileOptions
import com.creatrbypc.createanalytical.script.lexer.HARD_KEYWORDS
import com.creatrbypc.createanalytical.script.lexer.LexerLimits
import com.creatrbypc.createanalytical.script.semantic.BuiltinFunctions
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

// S8.2 Phase 1: createscript pack と、library のマニフェストの検査
class PackCliTest {
    @TempDir
    lateinit var directory: Path

    private class Result(val code: Int, val out: String, val err: String)

    private fun cli(vararg args: String): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = PrintStream(out, true, Charsets.UTF_8).use { o ->
            PrintStream(err, true, Charsets.UTF_8).use { e -> CreateScriptCli(o, e).execute(args.toList()) }
        }
        return Result(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    private val manifest = """
        {
          "schemaVersion": 2,
          "kind": "library",
          "name": "vector-math",
          "version": "1.2.0",
          "license": "MIT",
          "modules": ["vector_math"]
        }
    """.trimIndent() + "\n"

    private fun library(name: String = "lib"): Path {
        val root = Files.createDirectories(directory.resolve(name))
        Files.writeString(root.resolve("createscript.json"), manifest)
        Files.createDirectories(root.resolve("modules"))
        Files.writeString(root.resolve("modules/vector_math.cscr"), "import helpers\nfun length(x: Double, y: Double): Double {\n    return helpers.root(x * x + y * y)\n}\n")
        Files.writeString(root.resolve("modules/helpers.cscr"), "fun root(v: Double): Double {\n    return sqrt(v)\n}\n")
        Files.writeString(root.resolve("LICENSE"), "MIT License\n")
        return root
    }

    @Test
    fun packsALibrary() {
        val root = library()
        // 入れないもの（プロジェクトのほかのファイル）
        Files.writeString(root.resolve("notes.txt"), "not packed")
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("src/demo.cscr"), "println(1)\n")
        val result = cli("pack", root.toString())
        assertEquals(ExitCode.OK, result.code, result.err)
        val archive = root.resolve("createscript-package.zip")
        val bytes = Files.readAllBytes(archive)
        val hex = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("Packed vector-math 1.2.0: $archive (4 files, ${bytes.size} bytes)\nsha256 $hex\n", result.out)
        val read = PackageArchives.read(bytes, "vector-math", "1.2.0").archive!!
        assertEquals(listOf("LICENSE", "createscript.json", "modules/helpers.cscr", "modules/vector_math.cscr"), read.files.keys.toList())
        // 同じソースからは同じバイト列（別の出力先に作り直して比べる）
        val again = directory.resolve("again.zip")
        assertEquals(ExitCode.OK, cli("pack", root.toString(), "--output", again.toString()).code)
        assertArrayEquals(bytes, Files.readAllBytes(again))
        // 既にあるファイルは上書きしない
        val refused = cli("pack", root.toString())
        assertEquals(ExitCode.CANNOT_CREATE, refused.code)
        assertTrue(refused.err.contains("already exists; remove it or choose another file with --output"), refused.err)
        assertArrayEquals(bytes, Files.readAllBytes(archive))
        // 標準の unzip でも読める（あれば）
        val unzip = try {
            ProcessBuilder("unzip", "-tq", archive.toString()).redirectErrorStream(true).start()
        } catch (error: java.io.IOException) {
            null
        }
        if (unzip != null) assertEquals(0, unzip.waitFor())
    }

    @Test
    fun checksTheProject() {
        // application は pack できない
        val application = Files.createDirectories(directory.resolve("app"))
        assertEquals(ExitCode.OK, cli("init", application.toString()).code)
        val notLibrary = cli("pack", application.toString())
        assertEquals(ExitCode.INVALID_INPUT, notLibrary.code)
        assertTrue(notLibrary.err.contains("is not a library"), notLibrary.err)
        // modules に書いたファイルが無い
        val missing = library("missing")
        Files.delete(missing.resolve("modules/vector_math.cscr"))
        val missingResult = cli("pack", missing.toString())
        assertEquals(ExitCode.INVALID_INPUT, missingResult.code)
        assertTrue(missingResult.err.contains(":7:15: error CS_PKG_MODULE_FILE_NOT_FOUND (\"vector_math\")"), missingResult.err)
        assertEquals(ExitCode.INVALID_INPUT, cli("manifest", "check", missing.toString()).code)
        // modules/ の中の、入れられないもの（ほかのファイル・サブディレクトリ・シンボリックリンク）。何も作らない
        val extra = library("extra")
        Files.writeString(extra.resolve("modules/build.sh"), "rm -rf /\n")
        Files.createDirectories(extra.resolve("modules/sub"))
        Files.createSymbolicLink(extra.resolve("modules/linked.cscr"), extra.resolve("modules/helpers.cscr"))
        val extraResult = cli("pack", extra.toString())
        assertEquals(ExitCode.INVALID_INPUT, extraResult.code)
        for (expected in listOf("modules/build.sh", "modules/sub", "modules/linked.cscr\", \"symbolic links are not packed")) assertTrue(extraResult.err.contains(expected), extraResult.err)
        assertFalse(Files.exists(extra.resolve("createscript-package.zip")))
        // 使えないソース（UTF-8 でない）・リンクの LICENSE
        val invalid = library("invalid")
        Files.write(invalid.resolve("modules/helpers.cscr"), byteArrayOf(0xC3.toByte(), 0x28))
        assertTrue(cli("pack", invalid.toString()).err.contains("error CS_PKG_ARCHIVE_INVALID_SOURCE (\"modules/helpers.cscr\", \"not valid UTF-8\")"))
        val linkedLicense = library("linked-license")
        Files.delete(linkedLicense.resolve("LICENSE"))
        Files.createSymbolicLink(linkedLicense.resolve("LICENSE"), directory.resolve("outside.txt").also { Files.writeString(it, "secret") })
        val licenseResult = cli("pack", linkedLicense.toString())
        assertEquals(ExitCode.INVALID_INPUT, licenseResult.code)
        assertTrue(licenseResult.err.contains("only regular files that are not symbolic links are packed"), licenseResult.err)
        // modules がシンボリックリンク
        val linkedModules = library("linked-modules")
        val real = Files.move(linkedModules.resolve("modules"), directory.resolve("real-modules"))
        Files.createSymbolicLink(linkedModules.resolve("modules"), real)
        assertEquals(ExitCode.INVALID_INPUT, cli("pack", linkedModules.toString()).code)
    }

    @Test
    fun manifestCheckForLibraries() {
        val root = library("checked")
        val result = cli("manifest", "check", root.toString())
        assertEquals(ExitCode.OK, result.code, result.err)
        assertEquals("${root.resolve("createscript.json")}: ok (vector-math 1.2.0, library, modules vector_math, 0 dependencies)\n", result.out)
    }

    @Test
    fun usage() {
        assertEquals(ExitCode.USAGE, cli("pack", "a", "b").code)
        assertEquals(ExitCode.USAGE, cli("pack", "--output").code)
        assertEquals(ExitCode.USAGE, cli("pack", "--force").code)
        assertTrue(cli("--help").out.contains("createscript pack [directory] [--output <file>]"))
    }

    // createscript-project は Core に依存しないため、モジュール名の規則・ソースの大きさを自分でも持つ。Core・CLI の値と同じであること
    @Test
    fun projectRulesMatchTheCore() {
        assertEquals(HARD_KEYWORDS, ProjectRules.HARD_KEYWORDS)
        assertEquals(BuiltinFunctions.NAMES, ProjectRules.BUILTIN_FUNCTIONS)
        assertEquals(LexerLimits.MAX_IDENTIFIER_LENGTH, ProjectRules.MAX_MODULE_NAME_LENGTH)
        assertEquals(CompileOptions.STANDALONE.maxSourceLength, ProjectRules.MAX_SOURCE_LENGTH)
        assertEquals(CliLimits.MAX_SOURCE_BYTES, ProjectRules.MAX_SOURCE_BYTES)
        assertNotNull(ProjectRules.isValidModuleName("vector_math").takeIf { it })
    }
}
