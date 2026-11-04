package com.creatrbypc.createanalytical.script.compiler

import com.creatrbypc.createanalytical.script.api.CreateScript
import com.creatrbypc.createanalytical.script.api.RunOptions
import com.creatrbypc.createanalytical.script.api.ScriptRun
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticArgument
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticCode
import com.creatrbypc.createanalytical.script.lexer.LexerLimits
import com.creatrbypc.createanalytical.script.module.ModuleLimits
import com.creatrbypc.createanalytical.script.module.ModuleLookup
import com.creatrbypc.createanalytical.script.module.ModuleResolver
import com.creatrbypc.createanalytical.script.vm.ProgramFingerprint
import com.creatrbypc.createanalytical.script.vm.SnapshotVerifier
import com.creatrbypc.createanalytical.script.vm.VmLimits
import com.creatrbypc.createanalytical.script.vm.VmState
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory

// 第8.0.1段階: コンパイル時のプログラムの大きさの上限（CompileOptions）。EMBEDDED・既定は 4096 命令、STANDALONE は上限なし
class CompileOptionsTest {
    private fun resolver(modules: Map<String, String>) = ModuleResolver { name -> modules[name]?.let { ModuleLookup.Found(it) } ?: ModuleLookup.NotFound }

    private fun finish(run: ScriptRun, maxTicks: Int = 1_000_000) {
        var ticks = 0
        while (!run.isFinished && ticks++ < maxTicks) run.tick()
    }

    // 1 つが約 1120 命令の関数を持つモジュール 4 つ（合わせて 4096 命令を超える）
    private val body = "fun f(): Int {\n    var x = 0\n" + "    x = x + 1\n".repeat(280) + "    return x\n}\n"
    private val modules = (1..4).associate { "m$it" to body }
    private val main = "import m1\nimport m2\nimport m3\nimport m4\nprintln(m1.f() + m2.f() + m3.f() + m4.f())\n"

    @Test
    fun standaloneCompilesAndRunsMoreThan4096Instructions() {
        val result = CreateScript.compile(main, resolver(modules), "main", CompileOptions.STANDALONE)
        val program = result.program ?: error("${result.diagnostics}")
        assertTrue(program.instructions.size > VmLimits.MAX_INSTRUCTIONS, "${program.instructions.size}")
        val printed = ArrayList<String>()
        val run = CreateScript.start(program, RunOptions(128, 0L)) { printed.add(it) }
        finish(run)
        assertEquals(VmState.HALTED, run.state)
        assertEquals(listOf("1120"), printed)
    }

    // EMBEDDED（4096 命令）と既定（これまでの API）は同じ理由で拒否する。診断の引数は設定した上限
    @Test
    fun embeddedAndDefaultReject() {
        for (result in listOf(
            CreateScript.compile(main, resolver(modules), "main", CompileOptions.EMBEDDED),
            CreateScript.compile(main, resolver(modules), "main"),
            CreateScript.compile(main, resolver(modules), "main", CompileOptions(4096)),
        )) {
            assertNull(result.program)
            val diagnostic = result.diagnostics.single().diagnostic
            assertEquals(DiagnosticCode.COMP_TOO_MANY_INSTRUCTIONS, diagnostic.code)
            assertEquals(listOf(DiagnosticArgument.IntArgument(4096)), diagnostic.arguments)
        }
        assertEquals(CompileOptions.EMBEDDED, CompileOptions.DEFAULT)
        assertEquals(4096, CompileOptions.EMBEDDED.maxInstructions)
        assertNull(CompileOptions.STANDALONE.maxInstructions)
    }

    // 上限ちょうどの命令数は受け付け、1 つ少ない上限では拒否する
    @Test
    fun customLimitIsExact() {
        val source = "println(1)\nprintln(2)\nprintln(3)\n"
        val size = CreateScript.compile(source, CompileOptions.STANDALONE).program!!.instructions.size
        assertNotNull(CreateScript.compile(source, CompileOptions(size)).program)
        val rejected = CreateScript.compile(source, CompileOptions(size - 1))
        assertNull(rejected.program)
        assertEquals(DiagnosticCode.COMP_TOO_MANY_INSTRUCTIONS, rejected.diagnostics.single().code)
        assertEquals(listOf(DiagnosticArgument.IntArgument(size - 1)), rejected.diagnostics.single().arguments)
        assertNull(Compiler.compileSource(source, CompileOptions(1)).program)
    }

    @Test
    fun rejectsInvalidOptions() {
        assertThrows(IllegalArgumentException::class.java) { CompileOptions(0) }
        assertThrows(IllegalArgumentException::class.java) { CompileOptions(-1) }
    }

    // 4096 命令以内のプログラムは、どの設定でも同じ命令・同じハッシュ・同じ診断（既存の単一ファイルのプログラムは変わらない）
    @Test
    fun optionsDoNotChangeAcceptedPrograms() {
        val sources = listOf(
            "println(1)",
            "var x = 0\nwhile (x < 3) {\n    x = x + 1\n}\nprintln(x)\n",
            "fun fib(n: Int): Int {\n    if (n < 2) {\n        return n\n    }\n    return fib(n - 1) + fib(n - 2)\n}\nprintln(fib(10))\n",
            "class Counter {\n    var value = 0\n    fun add(step: Int): Int {\n        value = value + step\n        return value\n    }\n}\nval c = Counter()\nfor (i in 1..3) {\n    c.add(i)\n}\nprintln(c.value)\n",
            "val l = mutableListOf(1.5, 2.5)\nl.add(3.0)\nprintln(l)\nprintln(sqrt(2.0))\nrandomSeed(1)\nprintln(randomInt(1, 6))\n",
            "val v = vector(3.0, 4.0)\nprintln(length(v))\nval unused = 1 + 1\n1 + 1\n",
            "val x: Int = \"a\"\n",
        )
        for (source in sources) {
            val before = Compiler.compileSource(source)
            for (options in listOf(CompileOptions.DEFAULT, CompileOptions.EMBEDDED, CompileOptions.STANDALONE)) {
                val now = CreateScript.compile(source, options)
                assertEquals(before, now, source)
                before.program?.let { assertArrayEquals(ProgramFingerprint.ofProgram(it), ProgramFingerprint.ofProgram(now.program!!), source) }
                val viaModules = CreateScript.compile(source, resolver(emptyMap()), "main", options)
                assertEquals(before.program, viaModules.program, source)
                assertEquals(before.diagnostics, viaModules.diagnostics.map { it.diagnostic }, source)
            }
        }
    }

    // 上限の無い大きなプログラムでも、実行時の上限（1 tick の命令数・呼び出しの深さ）はそのまま働く
    @Test
    fun runtimeLimitsStayForLargePrograms() {
        val program = CreateScript.compile(main, resolver(modules), "main", CompileOptions.STANDALONE).program!!
        val run = CreateScript.start(program, RunOptions(16, 0L))
        repeat(200) { assertTrue(run.tick().executed <= 16) }
        assertEquals(false, run.isFinished)

        val deep = modules + ("deep" to "fun down(n: Int): Int {\n    return down(n + 1)\n}\n")
        val deepProgram = CreateScript.compile("import deep\n" + main + "println(deep.down(0))\n", resolver(deep), "main", CompileOptions.STANDALONE).program!!
        val deepRun = CreateScript.start(deepProgram, RunOptions(4096, 0L))
        finish(deepRun)
        assertEquals(VmState.ERROR, deepRun.state)
        assertEquals(DiagnosticCode.RUN_CALL_DEPTH_EXCEEDED, deepRun.error!!.code)
    }

    // 入力の上限（1 ファイル 4096 文字・2048 トークン・2048 ノード、モジュール 16 個）でほぼ最大の命令数になるプログラム。
    // && を並べた式（1 ファイルで約 4000 命令）をエントリーと 16 のモジュールに置く。コンパイル（検証を含む）の時間と割り当てたメモリーを表示する
    @Test
    fun measureLargestProgramWithinInputLimits() {
        val chain = (1..30).joinToString("&&") { "b" } + "\n"
        // モジュールとしてエラー無くコンパイルできる範囲で、式の行をできるだけ多く並べる
        fun fill(head: String, tail: String): String {
            var count = 0
            while (true) {
                val next = head + chain.repeat(count + 1) + tail
                if (next.length > LexerLimits.MAX_SOURCE_LENGTH) break
                if (CreateScript.compile("import m\n", resolver(mapOf("m" to next)), "main", CompileOptions.STANDALONE).program == null) break
                count++
            }
            return head + chain.repeat(count) + tail
        }
        val moduleSource = fill("fun f(b: Boolean) {\n", "}\n")
        val names = (1..ModuleLimits.MAX_MODULES).map { "m$it" }
        val sources = names.associateWith { moduleSource }
        val entry = names.joinToString("") { "import $it\n" } + "val b = true\n" + names.joinToString("") { "$it.f(b)\n" }
        val entrySource = entry + chain.repeat(20)
        assertTrue(entrySource.length <= LexerLimits.MAX_SOURCE_LENGTH)

        repeat(2) { CreateScript.compile(entrySource, resolver(sources), "main", CompileOptions.STANDALONE) }
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val thread = Thread.currentThread().id
        val bytesBefore = bean.getThreadAllocatedBytes(thread)
        val start = System.nanoTime()
        val result = CreateScript.compile(entrySource, resolver(sources), "main", CompileOptions.STANDALONE)
        val millis = (System.nanoTime() - start) / 1_000_000.0
        val allocated = (bean.getThreadAllocatedBytes(thread) - bytesBefore) / 1024 / 1024
        val program = result.program ?: error("${result.diagnostics}")
        val verifyStart = System.nanoTime()
        assertNotNull(SnapshotVerifier.framesOf(program))
        val verifyMillis = (System.nanoTime() - verifyStart) / 1_000_000.0
        println(
            "[8.0.1 measure] ${program.instructions.size} instructions from ${names.size + 1} files: compile ${"%.1f".format(millis)} ms " +
                "($allocated MiB allocated), verification alone ${"%.1f".format(verifyMillis)} ms, steps limit ${SnapshotVerifier.maxSteps(program)}",
        )
        assertTrue(program.instructions.size > 10 * VmLimits.MAX_INSTRUCTIONS)
        val embedded = CreateScript.compile(entrySource, resolver(sources), "main", CompileOptions.EMBEDDED)
        assertNull(embedded.program)
        assertTrue(embedded.diagnostics.any { it.diagnostic.code == DiagnosticCode.COMP_TOO_MANY_INSTRUCTIONS })
        val run = CreateScript.start(program, RunOptions(4096, 0L))
        finish(run)
        assertEquals(VmState.HALTED, run.state)
    }

    // 検証に重い形（変数の多い関数の中の、多数の while ループ。合流と逆向きの分岐が多い）を入力の上限いっぱいに並べたもの。時間とメモリーを表示する
    @Test
    fun measureLoopHeavyProgram() {
        val locals = (1..40).joinToString("") { "    var v$it = 0\n" }
        val loop = "    while (v1 < 1) {\n        v1 = v1 + 1\n        v2 = v1\n    }\n"
        fun fill(): String {
            var count = 0
            while (true) {
                val next = "fun f() {\n" + locals + loop.repeat(count + 1) + "}\n"
                if (next.length > LexerLimits.MAX_SOURCE_LENGTH) break
                if (CreateScript.compile("import m\n", resolver(mapOf("m" to next)), "main", CompileOptions.STANDALONE).program == null) break
                count++
            }
            return "fun f() {\n" + locals + loop.repeat(count) + "}\n"
        }
        val moduleSource = fill()
        val names = (1..ModuleLimits.MAX_MODULES).map { "m$it" }
        val entry = names.joinToString("") { "import $it\n" } + (1..60).joinToString("") { "var g$it = 0\n" } + names.joinToString("") { "$it.f()\n" }
        val sources = names.associateWith { moduleSource }
        repeat(2) { CreateScript.compile(entry, resolver(sources), "main", CompileOptions.STANDALONE) }
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val thread = Thread.currentThread().id
        val bytesBefore = bean.getThreadAllocatedBytes(thread)
        val start = System.nanoTime()
        val result = CreateScript.compile(entry, resolver(sources), "main", CompileOptions.STANDALONE)
        val millis = (System.nanoTime() - start) / 1_000_000.0
        val allocated = (bean.getThreadAllocatedBytes(thread) - bytesBefore) / 1024 / 1024
        val program = result.program ?: error("${result.diagnostics}")
        println("[8.0.1 measure] loop-heavy: ${program.instructions.size} instructions, ${program.variableCount} variables: compile ${"%.1f".format(millis)} ms ($allocated MiB allocated)")
        assertTrue(program.instructions.size > VmLimits.MAX_INSTRUCTIONS)
        val run = CreateScript.start(program, RunOptions(4096, 0L))
        finish(run)
        assertEquals(VmState.HALTED, run.state)
    }
}
