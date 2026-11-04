package com.creatrbypc.createanalytical.cli

import com.creatrbypc.createanalytical.script.api.CreateScript
import com.creatrbypc.createanalytical.script.module.ModuleLookup
import com.creatrbypc.createanalytical.script.vm.ProgramFingerprint
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

// 第8.0段階: createscript run の import（モジュールはプロジェクトのルートの直下の モジュール名.cscr）
class ModuleCliTest {
    @TempDir
    lateinit var directory: Path

    private class Result(val code: Int, val out: String, val err: String) {
        val lines: List<String> get() = out.lines().dropLast(1)
    }

    private fun cli(vararg args: String): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = PrintStream(out, true, Charsets.UTF_8).use { o ->
            PrintStream(err, true, Charsets.UTF_8).use { e -> CreateScriptCli(o, e).execute(args.toList()) }
        }
        return Result(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    private fun write(path: Path, source: String): Path = path.also {
        Files.createDirectories(it.parent)
        Files.writeString(it, source)
    }

    private fun project(): Path = Files.createDirectories(directory.resolve("my-project"))

    private val geometry = "fun square(x: Int): Int {\n    return x * x\n}\n"

    // 目標の例（README に載せたもの）
    @Test
    fun runsTheExample() {
        val root = project()
        write(root.resolve("geometry.cscr"), geometry)
        val main = write(root.resolve("main.cscr"), "import geometry\n\nprintln(geometry.square(5))\n")
        val result = cli("run", main.toString())
        assertEquals("", result.err)
        assertEquals(ExitCode.OK, result.code)
        assertEquals(listOf("25"), result.lines)
    }

    @Test
    fun runsSeveralModules() {
        val root = project()
        write(root.resolve("geometry.cscr"), "import numbers\nfun area(w: Int, h: Int): Int {\n    return numbers.times(w, h)\n}\n")
        write(root.resolve("numbers.cscr"), "fun times(a: Int, b: Int): Int {\n    return a * b\n}\n")
        write(root.resolve("labels.cscr"), "fun unit(): String {\n    return \"m2\"\n}\n")
        val main = write(root.resolve("main.cscr"), "import geometry\nimport labels\nimport geometry\nprintln(geometry.area(3, 4))\nprintln(labels.unit())\n")
        val result = cli("run", main.toString())
        assertEquals(ExitCode.OK, result.code)
        assertEquals(listOf("12", "m2"), result.lines)
        // 同じ import の重複は警告（実行は続ける）
        assertEquals("$main:3:8: warning CS_PARSE_DUPLICATE_IMPORT (\"geometry\")\n", result.err)
    }

    // --project-root で、実行するファイルとは別のディレクトリをルートにできる（ファイルはルートの中）
    @Test
    fun usesTheProjectRootOption() {
        val root = project()
        write(root.resolve("geometry.cscr"), geometry)
        val main = write(root.resolve("apps/main.cscr"), "import geometry\nprintln(geometry.square(7))\n")
        assertEquals(ExitCode.COMPILE_ERROR, cli("run", main.toString()).code)
        val result = cli("run", main.toString(), "--project-root", root.toString())
        assertEquals(ExitCode.OK, result.code, result.err)
        assertEquals(listOf("49"), result.lines)
        // ルートの外のファイルは実行しない
        val outside = write(directory.resolve("other/main.cscr"), "println(1)\n")
        val rejected = cli("run", outside.toString(), "--project-root", root.toString())
        assertEquals(ExitCode.USAGE, rejected.code)
        assertTrue(rejected.err.contains("is not inside the project root"), rejected.err)
        assertEquals(ExitCode.NO_INPUT, cli("run", main.toString(), "--project-root", directory.resolve("missing").toString()).code)
        assertEquals(ExitCode.NO_INPUT, cli("run", main.toString(), "--project-root", main.toString()).code)
    }

    // モジュールの中の診断・実行時エラーは、そのモジュールのファイルの位置で表示する
    @Test
    fun showsPositionsInModuleFiles() {
        val root = project()
        val bad = write(root.resolve("bad.cscr"), "fun f(): Int {\n    return \"text\"\n}\n")
        val main = write(root.resolve("main.cscr"), "import bad\nprintln(bad.f())\n")
        val compile = cli("run", main.toString())
        assertEquals(ExitCode.COMPILE_ERROR, compile.code)
        assertEquals("$bad:2:12: error CS_SEM_TYPE_MISMATCH (\"Int\", \"String\")\n", compile.err)

        val big = write(root.resolve("big.cscr"), "fun grow(): Int {\n    var x = 2147483647\n    x = x + 1\n    return x\n}\n")
        val run = cli("run", write(root.resolve("run.cscr"), "println(1)\nimport big\n").toString())
        assertEquals(ExitCode.COMPILE_ERROR, run.code)
        val main2 = write(root.resolve("main2.cscr"), "import big\nprintln(1)\nprintln(big.grow())\n")
        val runtime = cli("run", main2.toString())
        assertEquals(ExitCode.RUNTIME_ERROR, runtime.code)
        assertEquals(listOf("1"), runtime.lines)
        assertTrue(runtime.err.startsWith("$big:3:11: error CS_RUN_INTEGER_OVERFLOW"), runtime.err)
    }

    @Test
    fun reportsMissingAndCyclicModules() {
        val root = project()
        val main = write(root.resolve("main.cscr"), "import geometry\nprintln(geometry.square(5))\n")
        val missing = cli("run", main.toString())
        assertEquals(ExitCode.COMPILE_ERROR, missing.code)
        assertEquals("$main:1:8: error CS_MODULE_NOT_FOUND (\"geometry\")\n", missing.err)

        write(root.resolve("a.cscr"), "import b\nfun f(): Int {\n    return b.g()\n}\n")
        val b = write(root.resolve("b.cscr"), "import a\nfun g(): Int {\n    return 1\n}\n")
        val cyclic = cli("run", write(root.resolve("cycle.cscr"), "import a\nprintln(a.f())\n").toString())
        assertEquals(ExitCode.COMPILE_ERROR, cyclic.code)
        assertEquals("$b:1:8: error CS_MODULE_CYCLE (\"a -> b -> a\")\n", cyclic.err)
        // 実行するファイル自身の import
        val self = write(root.resolve("self.cscr"), "import self\nprintln(1)\n")
        assertEquals("$self:1:8: error CS_MODULE_CYCLE (\"self -> self\")\n", cli("run", self.toString()).err)
    }

    // ルートの外のファイルは使わない（"../" などのパスは書けず、ルートの外の同じ名前のファイルも探さない）
    @Test
    fun staysInsideTheProjectRoot() {
        val root = project()
        write(directory.resolve("secret.cscr"), "fun value(): Int {\n    return 42\n}\n")
        write(root.resolve("sub/inner.cscr"), "fun value(): Int {\n    return 1\n}\n")
        val cases = mapOf(
            "import secret\nprintln(secret.value())\n" to "1:8: error CS_MODULE_NOT_FOUND (\"secret\")",
            "import inner\nprintln(inner.value())\n" to "1:8: error CS_MODULE_NOT_FOUND (\"inner\")",
            "import \"../secret\"\n" to "1:8: error CS_PARSE_INVALID_IMPORT",
            "import \"/etc/hosts\"\n" to "1:8: error CS_PARSE_INVALID_IMPORT",
            "import sub.inner\n" to "1:11: error CS_PARSE_INVALID_IMPORT",
        )
        for ((source, expected) in cases) {
            val main = write(root.resolve("main.cscr"), source)
            val result = cli("run", main.toString())
            assertEquals(ExitCode.COMPILE_ERROR, result.code, source)
            assertEquals("$main:$expected\n", result.err, source)
        }
    }

    // シンボリックリンクは、たどった先がルートの中の通常のファイルのときだけ使う
    @Test
    fun followsSymbolicLinksOnlyInsideTheRoot() {
        val root = project()
        write(directory.resolve("outside.cscr"), "fun value(): Int {\n    return 42\n}\n")
        write(root.resolve("lib/real.cscr"), "fun value(): Int {\n    return 7\n}\n")
        Files.createSymbolicLink(root.resolve("escape.cscr"), root.relativize(directory.resolve("outside.cscr")))
        Files.createSymbolicLink(root.resolve("linked.cscr"), Path.of("lib/real.cscr"))
        Files.createSymbolicLink(root.resolve("broken.cscr"), Path.of("nothing.cscr"))
        Files.createSymbolicLink(root.resolve("folder.cscr"), Path.of("lib"))
        val main = root.resolve("main.cscr")

        write(main, "import linked\nprintln(linked.value())\n")
        assertEquals(listOf("7"), cli("run", main.toString()).lines)
        write(main, "import escape\nprintln(escape.value())\n")
        assertEquals("$main:1:8: error CS_MODULE_OUTSIDE_PROJECT (\"escape\")\n", cli("run", main.toString()).err)
        write(main, "import broken\n")
        assertEquals("$main:1:8: error CS_MODULE_UNREADABLE (\"broken\", \"broken symbolic link\")\n", cli("run", main.toString()).err)
        write(main, "import folder\n")
        assertEquals("$main:1:8: error CS_MODULE_UNREADABLE (\"folder\", \"not a regular file\")\n", cli("run", main.toString()).err)
        // ルート自体がシンボリックリンクでもよい（たどった先をルートにする）
        val alias = Files.createSymbolicLink(directory.resolve("alias"), root)
        write(main, "import linked\nprintln(linked.value())\n")
        assertEquals(listOf("7"), cli("run", alias.resolve("main.cscr").toString()).lines)
    }

    // ファイル名は大文字・小文字まで一致するものだけ。大文字・小文字だけが違うファイルが複数あれば曖昧
    @Test
    fun matchesTheExactFileName() {
        val root = project()
        write(root.resolve("Geometry.cscr"), geometry)
        val main = write(root.resolve("main.cscr"), "import geometry\nprintln(geometry.square(5))\n")
        assertEquals("$main:1:8: error CS_MODULE_NOT_FOUND (\"geometry\")\n", cli("run", main.toString()).err)
        // 大文字・小文字を区別しないファイルシステム（macOS の既定など）では、2 つ目のファイルを作れない
        val lower = root.resolve("geometry.cscr")
        assumeFalse(Files.exists(lower), "the file system does not distinguish upper and lower case")
        write(lower, geometry)
        assertEquals("$main:1:8: error CS_MODULE_AMBIGUOUS (\"geometry\")\n", cli("run", main.toString()).err)
    }

    @Test
    fun reportsUnreadableModules() {
        val root = project()
        Files.write(root.resolve("binary.cscr"), byteArrayOf(0xC3.toByte(), 0x28))
        write(root.resolve("large.cscr"), "// " + "x".repeat(CliLimits.MAX_SOURCE_BYTES) + "\n")
        val main = root.resolve("main.cscr")
        write(main, "import binary\n")
        assertEquals("$main:1:8: error CS_MODULE_UNREADABLE (\"binary\", \"not valid UTF-8\")\n", cli("run", main.toString()).err)
        write(main, "import large\n")
        assertEquals("$main:1:8: error CS_MODULE_UNREADABLE (\"large\", \"file too large (at most 4096 characters)\")\n", cli("run", main.toString()).err)
        // モジュールに書けるのは import と関数だけ
        val statement = write(root.resolve("statement.cscr"), "println(1)\n")
        write(main, "import statement\n")
        assertEquals("$statement:1:1: error CS_MODULE_TOP_LEVEL_STATEMENT\n", cli("run", main.toString()).err)
    }

    // モジュールの関数も --max-ticks・--instructions-per-tick の上限を受ける
    @Test
    fun keepsTheRunLimits() {
        val root = project()
        write(root.resolve("loop.cscr"), "fun forever() {\n    while (true) {\n    }\n}\n")
        val main = write(root.resolve("main.cscr"), "import loop\nprintln(1)\nloop.forever()\n")
        val result = cli("run", main.toString(), "--max-ticks", "50", "--instructions-per-tick", "4")
        assertEquals(ExitCode.MAX_TICKS_EXCEEDED, result.code)
        assertEquals(listOf("1"), result.lines)
        assertEquals("$main: stopped after 50 ticks (--max-ticks); the program did not finish\n", result.err)
    }

    // import の無いファイル（すべてのサンプル）は、これまでの CreateScript.compile(source) と同じ命令・ハッシュ・診断になる
    @Test
    fun samplesCompileAsBefore() {
        val samples = Files.list(Paths.get(System.getProperty("createscript.samples"))).use { files -> files.filter { it.toString().endsWith(".cscr") }.sorted().toList() }
        assertTrue(samples.size >= 6)
        for (sample in samples) {
            val source = Files.readString(sample)
            val before = CreateScript.compile(source)
            val now = CreateScript.compile(source, { ModuleLookup.NotFound }, "main")
            assertEquals(before.program, now.program, sample.toString())
            before.program?.let { assertArrayEquals(ProgramFingerprint.ofProgram(it), ProgramFingerprint.ofProgram(now.program!!)) }
            assertEquals(before.diagnostics, now.diagnostics.map { it.diagnostic }, sample.toString())
            assertTrue(now.diagnostics.all { it.module == null })
        }
    }

    @Test
    fun helpMentionsTheProjectRoot() {
        assertTrue(cli("--help").out.contains("--project-root <directory>"))
        assertEquals(ExitCode.USAGE, cli("run", "x.cscr", "--project-root").code)
    }
}
