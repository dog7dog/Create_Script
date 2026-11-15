package com.creatrbypc.createanalytical.script.compiler

import com.creatrbypc.createanalytical.script.api.CreateScript
import com.creatrbypc.createanalytical.script.api.RunOptions
import com.creatrbypc.createanalytical.script.diagnostic.Diagnostic
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticArgument
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticCode
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticSeverity
import com.creatrbypc.createanalytical.script.lexer.LexerLimits
import com.creatrbypc.createanalytical.script.module.ModuleCompileResult
import com.creatrbypc.createanalytical.script.module.ModuleLimits
import com.creatrbypc.createanalytical.script.module.ModuleLookup
import com.creatrbypc.createanalytical.script.module.ModuleResolver
import com.creatrbypc.createanalytical.script.parser.ParserLimits
import com.creatrbypc.createanalytical.script.vm.CompiledProgram
import com.creatrbypc.createanalytical.script.vm.VmLimits
import com.creatrbypc.createanalytical.script.vm.VmState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory

// 第8.0.1段階: 独立版（CompileOptions.STANDALONE）の入力の上限。文字数 → トークン数 → AST のノード数 → モジュールの数 → 命令数 の順に、
// 境界の値・既定（EMBEDDED）との違い・合計の上限・作業量の上限・悪意のある入力（スタック・時間・メモリーを使い切らせる形）を確かめる
class StandaloneLimitsTest {
    private val standalone = CompileOptions.STANDALONE
    private val embedded = CompileOptions.EMBEDDED

    private fun errors(diagnostics: List<Diagnostic>) = diagnostics.filter { it.severity == DiagnosticSeverity.ERROR }

    private fun resolver(modules: Map<String, String>) = ModuleResolver { name -> modules[name]?.let { ModuleLookup.Found(it) } ?: ModuleLookup.NotFound }

    private fun runToEnd(program: CompiledProgram): List<String> {
        val printed = ArrayList<String>()
        val run = CreateScript.start(program, RunOptions(4096, 0L)) { printed.add(it) }
        var ticks = 0
        while (!run.isFinished && ticks++ < 1_000_000) run.tick()
        assertEquals(VmState.HALTED, run.state, "${run.error}")
        return printed
    }

    // 小さなスタック（512 KiB）のスレッドで、時間を測って実行する。JVM のスタックを深く使う処理があれば StackOverflowError になる
    private fun <T> onSmallStack(label: String, block: () -> T): T {
        var result: Result<T>? = null
        val thread = Thread(null, {
            val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
            val id = Thread.currentThread().id
            val bytes = bean.getThreadAllocatedBytes(id)
            val start = System.nanoTime()
            result = runCatching(block)
            println("[8.0.1 limits] $label: ${(System.nanoTime() - start) / 1_000_000} ms, ${(bean.getThreadAllocatedBytes(id) - bytes) / 1024 / 1024} MiB allocated")
        }, label, 512L * 1024)
        thread.start()
        thread.join(120_000)
        assertTrue(!thread.isAlive, "$label did not finish within 120 s")
        return result!!.getOrThrow()
    }

    @Test
    fun presets() {
        assertEquals(CompileOptions.DEFAULT, embedded)
        assertEquals(CompileOptions(4096, 4096, 2048, 2048, 16, 4096 * 17, null, 2048 * 17, null), embedded)
        assertEquals(LexerLimits.MAX_SOURCE_LENGTH, embedded.maxSourceLength)
        assertEquals(ParserLimits.MAX_NODES, embedded.maxNodes)
        assertEquals(ModuleLimits.MAX_MODULES, embedded.maxModules)
        assertNull(standalone.maxInstructions)
        assertEquals(listOf(1_000_000, 250_000, 250_000, 1024, 8_000_000, 1_000_000), with(standalone) { listOf(maxSourceLength, maxTokens, maxNodes, maxModules, maxTotalSourceLength, maxTotalTokens) })
        assertEquals(128, standalone.maxPropertyTypeChain)
        assertEquals(100_000_000L, standalone.maxVerificationSteps)
        // 位置（offset）が Int を超えうる合計の上限は作れない。負・0 の上限も作れない
        assertThrows(IllegalArgumentException::class.java) { CompileOptions(maxTotalSourceLength = Int.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { CompileOptions(maxTokens = 0) }
        assertThrows(IllegalArgumentException::class.java) { CompileOptions(maxTokens = 3000, maxTotalTokens = 2999) }
        assertThrows(IllegalArgumentException::class.java) { CompileOptions(maxVerificationSteps = 0) }
    }

    // ---- 1. 文字数 ----

    @Test
    fun sourceLength() {
        val program = "println(1)\n"
        val exact = program + "//" + "x".repeat(standalone.maxSourceLength - program.length - 2)
        assertEquals(standalone.maxSourceLength, exact.length)
        assertEquals(listOf("1"), runToEnd(CreateScript.compile(exact, standalone).program!!))
        val over = CreateScript.compile("$exact ", standalone)
        assertEquals(listOf(DiagnosticCode.LEX_SOURCE_TOO_LONG), over.diagnostics.map { it.code })
        assertEquals(listOf(DiagnosticArgument.IntArgument(1_000_001), DiagnosticArgument.IntArgument(1_000_000)), over.diagnostics.single().arguments)
        // 既定（EMBEDDED・これまでの API）は 4096 文字のまま
        val small = program + "//" + "x".repeat(5000)
        assertEquals(listOf(DiagnosticCode.LEX_SOURCE_TOO_LONG), CreateScript.compile(small).diagnostics.map { it.code })
        assertEquals(listOf(DiagnosticCode.LEX_SOURCE_TOO_LONG), CreateScript.compile(small, embedded).diagnostics.map { it.code })
        assertNotNull(CreateScript.compile(small, standalone).program)
    }

    // ---- 2. トークン数 ----

    @Test
    fun tokenCount() {
        // println(1) と改行は 5 トークン。50,000 行でちょうど 250,000
        val exact = "println(1)\n".repeat(standalone.maxTokens / 5)
        val program = CreateScript.compile(exact, standalone).program!!
        assertEquals(standalone.maxTokens / 5, runToEnd(program).size)
        val over = CreateScript.compile(exact + "1", standalone)
        assertEquals(listOf(DiagnosticCode.LEX_TOO_MANY_TOKENS), errors(over.diagnostics).map { it.code })
        assertEquals(listOf(DiagnosticArgument.IntArgument(250_000)), errors(over.diagnostics).single().arguments)
        assertEquals(listOf(DiagnosticCode.LEX_TOO_MANY_TOKENS), CreateScript.compile("1\n".repeat(1025), embedded).diagnostics.map { it.code })
    }

    // ---- 3. AST のノード数 ----

    @Test
    fun nodeCount() {
        // val b = true は 2 ノード。!!!!!!!!b の文は 10 ノード（否定 8・名前・文）、!!!!!!b の文は 8 ノード
        val exact = "val b = true\n" + "!!!!!!!!b\n".repeat(24_999) + "!!!!!!b\n"
        val program = CreateScript.compile(exact, standalone).program
        assertNotNull(program)
        assertTrue(program!!.instructions.size > 200_000, "${program.instructions.size}")
        val over = CreateScript.compile(exact + "b\n", standalone)
        assertEquals(listOf(DiagnosticCode.PARSE_TOO_MANY_NODES), errors(over.diagnostics).map { it.code })
        assertEquals(listOf(DiagnosticArgument.IntArgument(250_000)), errors(over.diagnostics).single().arguments)
        assertEquals(listOf(DiagnosticCode.PARSE_TOO_MANY_NODES), errors(CreateScript.compile("val b = true\n" + "!!!!!!!!b\n".repeat(300), embedded).diagnostics).map { it.code })
        // 入れ子の深さ（32）はどの設定でも同じ
        val deep = "val x = " + "(".repeat(40) + "1" + ")".repeat(40) + "\n"
        assertEquals(listOf(DiagnosticCode.PARSE_NESTING_TOO_DEEP), CreateScript.compile(deep, standalone).diagnostics.map { it.code })
    }

    // ---- 4. モジュールの数 ----

    @Test
    fun moduleCount() {
        val count = standalone.maxModules
        // 定数（256 個まで）を増やさないよう、どのモジュールも同じ値を返す
        val modules = (1..count + 1).associate { "m$it" to "fun f(): Int {\n    return 1\n}\n" }
        val main = (1..count).joinToString("") { "import m$it\n" } + "println(m1.f() + m$count.f())\n"
        val result = CreateScript.compile(main, resolver(modules), "main", standalone)
        assertEquals(count, result.modules.size)
        assertEquals(listOf("2"), runToEnd(result.program!!))
        val over = CreateScript.compile("import m${count + 1}\n$main", resolver(modules), "main", standalone)
        assertEquals(listOf(DiagnosticCode.MODULE_TOO_MANY), over.diagnostics.map { it.diagnostic.code })
        assertEquals(listOf(DiagnosticArgument.StringArgument("m$count"), DiagnosticArgument.IntArgument(count)), over.diagnostics.single().diagnostic.arguments)
        // 既定は 16 のまま
        val seventeen = (1..17).joinToString("") { "import m$it\n" }
        assertEquals(listOf(DiagnosticCode.MODULE_TOO_MANY), CreateScript.compile(seventeen, resolver(modules), "main", embedded).diagnostics.map { it.diagnostic.code })
        assertEquals(listOf(DiagnosticArgument.StringArgument("m17"), DiagnosticArgument.IntArgument(16)), CreateScript.compile(seventeen, resolver(modules), "main", embedded).diagnostics.single().diagnostic.arguments)
    }

    // 1024 段の import の連鎖と循環。たどる処理は再帰しないため、小さなスタックでも終わる
    @Test
    fun longImportChainsAndCycles() {
        val n = standalone.maxModules
        val chain = ModuleResolver { name ->
            val k = name.removePrefix("m").toInt()
            ModuleLookup.Found(if (k < n) "import m${k + 1}\nfun f(): Int {\n    return 1\n}\n" else "fun f(): Int {\n    return 1\n}\n")
        }
        val resolved = onSmallStack("import chain of $n") { CreateScript.compile("import m1\nprintln(m1.f())\n", chain, "main", standalone) }
        assertEquals(n, resolved.modules.size)
        assertEquals("m$n", resolved.modules.first())
        assertEquals(listOf("1"), runToEnd(resolved.program!!))
        val cycle = ModuleResolver { name -> ModuleLookup.Found("import m${name.removePrefix("m").toInt() % n + 1}\nfun f() {\n}\n") }
        val cyclic = onSmallStack("import cycle of $n") { CreateScript.compile("import m1\n", cycle, "main", standalone) }
        val diagnostic = cyclic.diagnostics.single().diagnostic
        assertEquals(DiagnosticCode.MODULE_CYCLE, diagnostic.code)
        assertTrue((diagnostic.arguments.single() as DiagnosticArgument.StringArgument).value.startsWith("m1 -> m2 -> m3"))
    }

    // ---- 5. 命令数 ----

    // 命令数は 4096 を大きく超えてよい。&& を並べた式（1 トークンあたり約 2 命令）で 40 万命令を超えるプログラム
    @Test
    fun largeInstructionCounts() {
        val chain = (1..30).joinToString("&&") { "b" } + "\n"
        val source = "fun f(b: Boolean) {\n" + chain.repeat(4000) + "}\nf(true)\nprintln(1)\n"
        val program = onSmallStack("400k instructions") { CreateScript.compile(source, standalone).program }
        assertTrue(program!!.instructions.size > 400_000, "${program.instructions.size}")
        assertEquals(listOf("1"), runToEnd(program))
        assertEquals(DiagnosticCode.LEX_SOURCE_TOO_LONG, CreateScript.compile(source, embedded).diagnostics.single().code)
    }

    // ---- 合計の上限 ----

    @Test
    fun totalLimits() {
        // 文字数: 1,000,000 文字のファイル 8 つ（エントリーを含めて 8,000,000 文字を超える）
        val filler = "fun f() {\n}\n//" + "x".repeat(999_986)
        assertEquals(1_000_000, filler.length)
        val big = (1..8).associate { "m$it" to filler }
        val chars = CreateScript.compile((1..8).joinToString("") { "import m$it\n" }, resolver(big), "main", standalone)
        val tooLarge = chars.diagnostics.single().diagnostic
        assertEquals(DiagnosticCode.MODULE_PROGRAM_TOO_LARGE, tooLarge.code)
        assertEquals(listOf(DiagnosticArgument.StringArgument("m8"), DiagnosticArgument.StringArgument("characters"), DiagnosticArgument.IntArgument(8_000_000)), tooLarge.arguments)
        // トークン数: 約 240,000 トークンのファイル 5 つ（合計 1,000,000 を超える）
        val tokens = "fun f(b: Boolean) {\n" + ((1..30).joinToString("&&") { "b" } + "\n").repeat(4000) + "}\n"
        val many = (1..5).associate { "m$it" to tokens }
        val tokenResult = onSmallStack("token total") { CreateScript.compile((1..5).joinToString("") { "import m$it\n" }, resolver(many), "main", standalone) }
        val tooMany = tokenResult.diagnostics.single().diagnostic
        assertEquals(DiagnosticCode.MODULE_PROGRAM_TOO_LARGE, tooMany.code)
        assertEquals(listOf(DiagnosticArgument.StringArgument("m5"), DiagnosticArgument.StringArgument("tokens"), DiagnosticArgument.IntArgument(1_000_000)), tooMany.arguments)
        // 上限の内側（4 つ）は、コンパイルして実行できる（約 190 万命令）
        val fits = onSmallStack("about 960k tokens in 5 files") { CreateScript.compile((1..4).joinToString("") { "import m$it\n" } + "m1.f(true)\nprintln(1)\n", resolver(many), "main", standalone) }
        val program = fits.program ?: error("${fits.diagnostics}")
        println("[8.0.1 limits] largest program: ${program.instructions.size} instructions")
        assertTrue(program.instructions.size > 1_800_000)
        assertEquals(listOf("1"), runToEnd(program))
    }

    // ---- 作業量の上限（バイトコードの検証） ----

    @Test
    fun verificationBudget() {
        val source = "var x = 0\n" + "x = x + 1\n".repeat(100) + "println(x)\n"
        val tight = CreateScript.compile(source, standalone.copy(maxVerificationSteps = 50))
        assertEquals(listOf(DiagnosticCode.COMP_PROGRAM_TOO_COMPLEX), tight.diagnostics.map { it.code })
        assertEquals(listOf(DiagnosticArgument.IntArgument(50)), tight.diagnostics.single().arguments)
        assertNotNull(CreateScript.compile(source, standalone).program)
        // 上限の無い既定（EMBEDDED）は、これまでどおり理論上の上限で検証する
        assertNotNull(CreateScript.compile(source, embedded).program)
    }

    // ---- 悪意のある入力（第8.0.1段階で直したもの）。どれも小さなスタックで、短い時間で終わる ----

    // 長い呼び出しの連鎖（再帰でない）は呼び出しの深さの上限で拒否する。長い再帰の循環はコンパイルできる
    @Test
    fun longCallChains() {
        val n = 12_000
        val chain = (1..n).joinToString("") { "fun f$it(a: Int): Int {\n    return f${it + 1}(a)\n}\n" } + "fun f${n + 1}(a: Int): Int {\n    return a\n}\nprintln(f1(1))\n"
        val rejected = onSmallStack("call chain of $n") { CreateScript.compile(chain, standalone) }
        assertEquals(listOf(DiagnosticCode.COMP_CALL_TOO_DEEP), rejected.diagnostics.map { it.code })
        val cycle = (1..n).joinToString("") { "fun f$it(a: Int): Int {\n    return f${if (it == n) 1 else it + 1}(a)\n}\n" } + "println(1)\n"
        assertNotNull(onSmallStack("recursive cycle of $n") { CreateScript.compile(cycle, standalone) }.program)
    }

    // 再帰の循環を通して使う大域変数。初期化前の使用を、これまでと同じ診断（最も前で宣言された変数）で報告する
    @Test
    fun globalsThroughLongRecursiveChains() {
        val n = 10_000
        val functions = (1..n).joinToString("") { "fun f$it() {\n    if (g < 0) {\n        f${if (it == n) 1 else it + 1}()\n    }\n}\n" } +
            "fun last() {\n    h = 1\n    f1()\n}\n"
        val source = "var g = 0\nlast()\nvar h = 0\n$functions"
        val result = onSmallStack("globals through $n functions") { CreateScript.compile(source, standalone) }
        assertEquals(listOf(DiagnosticCode.SEM_CALL_BEFORE_VARIABLE_DECLARATION), result.diagnostics.map { it.code })
        assertEquals(listOf(DiagnosticArgument.StringArgument("last"), DiagnosticArgument.StringArgument("h")), result.diagnostics.single().arguments)
    }

    // 型の注釈の無いプロパティの長い連鎖は、独立版では上限（128）で止める。既定（EMBEDDED）では上限が無い（ノード数の上限で連鎖は短い）。
    // 実行できる連鎖は、生成の入れ子が呼び出しの深さの上限（16）以内のもの
    @Test
    fun propertyTypeChains() {
        fun chain(n: Int) = (1..n).joinToString("") { "class C$it {\n    val x = C${it + 1}().x\n}\n" } + "class C${n + 1} {\n    val x = 1\n}\nprintln(C1().x)\n"
        val long = onSmallStack("property type chain of 8000") { CreateScript.compile(chain(8000), standalone) }
        assertEquals(DiagnosticCode.SEM_PROPERTY_TYPE_CHAIN_TOO_DEEP, errors(long.diagnostics).first().code)
        assertEquals(listOf("1"), runToEnd(onSmallStack("property type chain of 10") { CreateScript.compile(chain(10), standalone) }.program!!))
        // 上限ちょうど（128）の連鎖は意味解析を通る（生成の入れ子が深すぎるため、Compiler が呼び出しの深さで拒否する）
        assertEquals(listOf(DiagnosticCode.COMP_CALL_TOO_DEEP), onSmallStack("property type chain of 127") { CreateScript.compile(chain(127), standalone) }.diagnostics.map { it.code })
        // 型の注釈を書けば連鎖しない
        val annotated = (1..8000).joinToString("") { "class C$it {\n    val x: Int = 1\n}\n" } + "println(C8000().x)\n"
        assertEquals(listOf("1"), runToEnd(onSmallStack("annotated properties") { CreateScript.compile(annotated, standalone) }.program!!))
        assertEquals(listOf("1"), runToEnd(CreateScript.compile(chain(10), embedded).program!!))
    }

    // 生成の循環の検出（クラスの数の 2 乗の手間にならない）
    @Test
    fun manyConstructionCycles() {
        val n = 8000
        val source = (1..n).joinToString("") { "class C$it {\n    val next = C${it % n + 1}()\n}\n" } + "println(1)\n"
        val result = onSmallStack("construction cycle of $n") { CreateScript.compile(source, standalone) }
        assertTrue(result.diagnostics.all { it.code == DiagnosticCode.SEM_CYCLIC_CONSTRUCTION })
        assertTrue(result.diagnosticsTruncated)
    }

    // a < b, c < d, … の並び（型引数の先読みが、トークン数の 2 乗の手間にならない。以前は 6 万個で約 10 秒かかった）。引数の数のエラーになる
    @Test
    fun comparisonsThatLookLikeTypeArguments() {
        val source = "val a = 1\nval b = 2\nprintln(" + (1..60_000).joinToString(", ") { "a<b" } + ")\n"
        val start = System.nanoTime()
        val result = onSmallStack("60000 comparisons in a call") { CreateScript.compile(source, standalone) }
        assertTrue((System.nanoTime() - start) / 1_000_000_000.0 < 5.0)
        assertEquals(listOf(DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT), result.diagnostics.map { it.code })
        // 型引数として読める形は、これまでどおり型引数
        assertEquals(listOf("[1, 2]"), runToEnd(CreateScript.compile("println(listOf<Int>(1, 2))", standalone).program!!))
    }

    // 深い型引数（List<List<…>> の入れ子）も再帰せずに扱う
    @Test
    fun deeplyNestedTypes() {
        val depth = 20_000
        val type = "List<".repeat(depth) + "Int" + ">".repeat(depth)
        val source = "fun f(x: $type): Int {\n    return 1\n}\nprintln(2)\n"
        assertEquals(listOf("2"), runToEnd(onSmallStack("type nested $depth deep") { CreateScript.compile(source, standalone) }.program!!))
    }

    // モジュールのファイルの位置は Int に収まり、多くのファイルの中の診断・実行時エラーの位置を正しく直せる
    @Test
    fun positionsAcrossManyModules() {
        val n = 1000
        val modules = (1..n).associate { "m$it" to "fun f(): Int {\n    var x = 2147483647\n    x = x + 1\n    return x\n}\n" }
        val result: ModuleCompileResult = CreateScript.compile((1..n).joinToString("") { "import m$it\n" } + "println(m$n.f())\n", resolver(modules), "main", standalone)
        val run = CreateScript.start(result.program!!, RunOptions(4096, 0L))
        while (!run.isFinished) run.tick()
        val located = result.locate(run.error!!)
        assertEquals(DiagnosticCode.RUN_INTEGER_OVERFLOW, located.diagnostic.code)
        assertEquals("m$n" to 3, located.module to located.diagnostic.position.line)
    }

    // VM の上限（変数 64・定数 256・スタック 64・呼び出しの深さ 16 など）は、独立版でも変わらない
    @Test
    fun vmLimitsAreUnchanged() {
        val variables = (1..VmLimits.MAX_VARIABLES + 1).joinToString("") { "val v$it = 1\n" }
        assertEquals(DiagnosticCode.COMP_TOO_MANY_VARIABLES, CreateScript.compile(variables, standalone).diagnostics.single().code)
        val constants = (1..VmLimits.MAX_CONSTANTS + 1).joinToString("") { "println($it)\n" }
        assertEquals(DiagnosticCode.COMP_TOO_MANY_CONSTANTS, CreateScript.compile(constants, standalone).diagnostics.single().code)
        val deepCalls = (1..VmLimits.MAX_CALL_DEPTH + 1).joinToString("") { "fun f$it() {\n    f${it + 1}()\n}\n" } + "fun f${VmLimits.MAX_CALL_DEPTH + 2}() {\n}\nf1()\n"
        assertEquals(DiagnosticCode.COMP_CALL_TOO_DEEP, CreateScript.compile(deepCalls, standalone).diagnostics.single().code)
    }
}
