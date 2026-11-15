package com.creatrbypc.createanalytical.script

import com.creatrbypc.createanalytical.script.compiler.CompileOptions
import com.creatrbypc.createanalytical.script.compiler.Compiler
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticCode
import com.creatrbypc.createanalytical.script.vm.CompiledProgram
import com.creatrbypc.createanalytical.script.vm.CsVm
import com.creatrbypc.createanalytical.script.vm.ErrorHandler
import com.creatrbypc.createanalytical.script.vm.ProgramFingerprint
import com.creatrbypc.createanalytical.script.vm.SnapshotVerifier
import com.creatrbypc.createanalytical.script.vm.VmState
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// 第9.0〜9.3段階: リストの関数（where・map・group・each・average）、パイプライン |>、エラーフォールバック ?!、ラムダと型推論を、
// ソースから CS_VM・SnapshotVerifier（保存・復元）まで通して確かめる
class CollectionPipelineTest {
    private fun compile(source: String, options: CompileOptions = CompileOptions.DEFAULT): CompiledProgram {
        val result = Compiler.compileSource(source, options)
        return result.program ?: error("compile failed: ${result.diagnostics.map { "${it.code}@${it.position.line}:${it.position.column}" }} in $source")
    }

    private fun codes(source: String): List<DiagnosticCode> {
        val result = Compiler.compileSource(source)
        assertNull(result.program, "program must be null when there are errors: $source")
        return result.diagnostics.map { it.code }
    }

    private fun step(vm: CsVm) {
        when {
            vm.state == VmState.WAITING -> vm.countWaitTick()
            vm.isCollecting -> vm.collectGarbage(1)
            else -> vm.run(1)
        }
    }

    private fun run(program: CompiledProgram): CsVm {
        val vm = CsVm(program)
        var steps = 0
        while (vm.state == VmState.RUNNING || vm.state == VmState.WAITING) {
            vm.advance(1000).also { if (vm.state == VmState.WAITING) vm.countWaitTick() }
            check(++steps < 2_000_000) { "did not finish" }
        }
        return vm
    }

    private fun output(source: String): List<String> {
        val vm = run(compile(source))
        assertEquals(VmState.HALTED, vm.state, "error ${vm.error} in $source")
        return vm.console.history
    }

    private fun runtimeError(source: String): DiagnosticCode {
        val vm = run(compile(source))
        assertEquals(VmState.ERROR, vm.state, source)
        return vm.error!!.code
    }

    // ---- S9.0 リストの関数 ----

    @Test
    fun whereMapGroupEachAverage() {
        assertEquals(listOf("[3, 4, 5]"), output("val xs = listOf(1, 2, 3, 4, 5)\nprintln(xs.where { it > 2 })"))
        assertEquals(listOf("[2, 4, 6]"), output("val xs = mutableListOf(1, 2, 3)\nprintln(xs.map { x -> x * 2 })"))
        assertEquals(listOf("[1.5, 3.0]"), output("println(listOf(1, 2).map { it * 1.5 })"))
        assertEquals(listOf("[[1, 3, 5], [2, 4]]"), output("println(listOf(1, 2, 3, 4, 5).group { it % 2 })"))
        assertEquals(listOf("[[apple, avocado], [banana]]", "2"), output("val g = listOf(\"apple\", \"banana\", \"avocado\").group { it == \"banana\" }\nprintln(g)\nprintln(g.size)"))
        assertEquals(listOf("[[a, c], [b]]"), output("println(listOf(\"a\", \"b\", \"c\").group { s -> if2(s) })\nfun if2(s: String): String {\n    if (s == \"b\") {\n        return \"y\"\n    }\n    return \"x\"\n}"))
        assertEquals(listOf("1", "2", "3", "6"), output("var sum = 0\nlistOf(1, 2, 3).each {\n    println(it)\n    sum = sum + it\n}\nprintln(sum)"))
        assertEquals(listOf("2.5", "NaN", "2.0", "0.25"), output("println(listOf(1, 2, 3, 4).average())\nprintln(listOf<Int>().average())\nprintln(listOf(1.5f, 2.5f).average())\nprintln(listOf(0.25).average())"))
        // 空のリスト・結果は新しいリストで、受け手は変わらない
        assertEquals(listOf("[]", "[]", "[]", "[1, 2]"), output("val xs = mutableListOf(1, 2)\nprintln(listOf<Int>().where { it > 0 })\nprintln(listOf<Int>().map { it })\nprintln(listOf<Int>().group { it })\nval ys = xs.where { true }\nprintln(xs)"))
        // 連鎖・入れ子のラムダ（外側の引数を内側で使う）・ラムダの中の変数とループ
        assertEquals(listOf("[20, 40]", "[[11, 21], [12, 22]]", "[2, 9]"), output(
            "val xs = listOf(1, 2, 3, 4)\nprintln(xs.where { it % 2 == 0 }.map { it * 10 })\n" +
                "println(listOf(1, 2).map { a -> listOf(10, 20).map { b -> a + b } })\n" +
                "println(listOf(3, 5).map { n ->\n    var total = 0\n    for (i in 0 until n) {\n        if (i == 1) { continue }\n        total = total + i\n    }\n    total\n})",
        ))
        // 関数の中・メソッドの中（ローカル変数で展開する）
        assertEquals(listOf("[4, 5]", "9"), output(
            "fun big(xs: List<Int>, min: Int): List<Int> {\n    return xs.where { it >= min }\n}\nclass Box {\n    var items = listOf(4, 5)\n    fun total(): Int {\n        var t = 0\n        items.each { t = t + it }\n        return t\n    }\n}\nprintln(big(listOf(1, 4, 5), 4))\nprintln(Box().total())",
        ))
    }

    @Test
    fun collectionErrors() {
        assertEquals(listOf(DiagnosticCode.SEM_TYPE_MISMATCH), codes("println(listOf(1).where { it + 1 })"))
        assertEquals(listOf(DiagnosticCode.SEM_UNIT_VALUE_NOT_ALLOWED), codes("println(listOf(1).map { println(it) })"))
        assertEquals(listOf(DiagnosticCode.SEM_GROUP_KEY_TYPE), codes("println(listOf(1).group { it * 1.0 })"))
        assertEquals(listOf(DiagnosticCode.SEM_INVALID_OPERAND_TYPE), codes("println(listOf(\"a\").average())"))
        assertEquals(listOf(DiagnosticCode.SEM_LAMBDA_PARAMETER_COUNT), codes("println(listOf(1).map { a, b -> a })"))
        assertEquals(listOf(DiagnosticCode.SEM_RETURN_IN_LAMBDA), codes("fun f(): Int {\n    listOf(1).each { return 1 }\n    return 0\n}"))
        assertEquals(listOf(DiagnosticCode.SEM_BREAK_OUTSIDE_LOOP), codes("while (true) {\n    listOf(1).each { break }\n}"))
        assertEquals(listOf(DiagnosticCode.SEM_UNDECLARED_VARIABLE), codes("println(listOf(1).map { x -> it })"))
        assertEquals(listOf(DiagnosticCode.SEM_LAMBDA_NOT_ALLOWED), codes("println({ 1 })"))
        assertEquals(listOf(DiagnosticCode.SEM_LAMBDA_IN_INITIALIZER), codes("class A {\n    val xs = listOf(1).map { it }\n}"))
        assertEquals(listOf(DiagnosticCode.SEM_TYPE_MISMATCH), codes("println(listOf(1).map { x: String -> x })"))
    }

    // 受け手のリストを本体で伸ばしても、要素数の上限で止まる（際限なく回らない）
    @Test
    fun growingTheReceiverStopsAtTheListLimit() {
        assertEquals(DiagnosticCode.RUN_LIST_TOO_LARGE, runtimeError("val xs = mutableListOf(1)\nxs.each { xs.add(it) }"))
    }

    // ---- S9.1 パイプライン ----

    @Test
    fun pipelines() {
        assertEquals(listOf("[30, 40]"), output("val xs = listOf(1, 2, 3, 4)\nval r = xs\n    |> where { it > 2 }\n    |> map { it * 10 }\nprintln(r)"))
        assertEquals(listOf("7", "12"), output("fun add(a: Int, b: Int): Int {\n    return a + b\n}\nfun twice(a: Int): Int {\n    return a * 2\n}\nprintln(3 |> add(4))\n3 |> add(3) |> twice |> println"))
        assertEquals(listOf("3.0"), output("println(9.0 |> sqrt)"))
        // 同じ名前の関数があれば、リストの関数より関数を呼ぶ
        assertEquals(listOf("mine 2"), output("fun map(xs: List<Int>, n: Int): String {\n    return \"mine\"\n}\nprintln(listOf(1, 2) |> map(1))\nprintln(listOf(1, 2).size)").let { listOf(it.joinToString(" ")) })
        // + より弱い（左全体が最初の引数）
        assertEquals(listOf("6"), output("fun twice(a: Int): Int {\n    return a * 2\n}\nprintln(1 + 2 |> twice)"))
        assertEquals(listOf(DiagnosticCode.SEM_INVALID_PIPE_TARGET), codes("println(1 |> (2 + 3))"))
    }

    // ---- S9.2 エラーフォールバック ----

    @Test
    fun fallbacks() {
        assertEquals(listOf("-1", "5"), output("val zero = 0\nprintln(10 / zero ?! -1)\nprintln(10 / 2 ?! -1)"))
        assertEquals(listOf("0", "[]"), output("val xs = listOf(1)\nprintln(xs[5] ?! 0)\nval e: List<Int> = xs.map { 10 / (it - 1) } ?! listOf()\nprintln(e)"))
        // 関数の中で起きたエラー（呼び出しフレームを戻す）・再帰の途中
        assertEquals(listOf("-1", "7"), output("fun div(a: Int, b: Int): Int {\n    return a / b\n}\nfun deep(n: Int): Int {\n    if (n == 0) {\n        return 1 / n\n    }\n    return deep(n - 1)\n}\nprintln(deep(5) ?! -1)\nprintln(div(14, 2) ?! 0)"))
        // 内側が先・右結合・入れ子
        assertEquals(listOf("2", "3"), output("val z = 0\nprintln(1 / z ?! 2 / 1 ?! 9)\nprintln((1 / z ?! 1 / z) ?! 3)"))
        // 文として（Unit）・途中の副作用は残る・計算途中のスタック（1 +）は捨てずに範囲の前の深さへ戻す
        assertEquals(listOf("before", "fallback", "3", "1"), output("val z = 0\nvar n = 0\nfun boom() {\n    println(\"before\")\n    n = 1\n    n = 1 / z\n}\nboom() ?! println(\"fallback\")\nval a = 1 + (2 * (3 / z) ?! 2)\nprintln(a)\nprintln(n)"))
        // 初期化式の中の ?! は使える。コンストラクタの外からは、初期化中のエラーを回復しない（初期化中のインスタンスを残さない）
        assertEquals(listOf("7"), output("fun zero(): Int {\n    return 0\n}\nclass A {\n    val x = 1 / zero() ?! 7\n}\nprintln(A().x)"))
        assertEquals(DiagnosticCode.RUN_DIVISION_BY_ZERO, runtimeError("fun zero(): Int {\n    return 0\n}\nclass A {\n    val x = 1 / zero()\n}\nprintln(A().x ?! 5)"))
        // 資源の上限・初期化前のプロパティは回復しない
        assertEquals(DiagnosticCode.RUN_CALL_DEPTH_EXCEEDED, runtimeError("fun f(n: Int): Int {\n    return f(n + 1)\n}\nprintln(f(0) ?! 1)"))
        assertEquals(DiagnosticCode.RUN_LIST_TOO_LARGE, runtimeError("val xs = mutableListOf<Int>()\nval r = (for2(xs)) ?! 0\nfun for2(xs: MutableList<Int>): Int {\n    while (true) {\n        xs.add(1)\n    }\n    return 0\n}"))
        // 型
        assertEquals(listOf(DiagnosticCode.SEM_TYPE_MISMATCH), codes("println(1 ?! \"a\")"))
        // waitTicks の負数・LINALG・coerceIn
        assertEquals(listOf("ok", "0", "1"), output("waitTicks(-1) ?! println(\"ok\")\nprintln(vector(1.0).normalize().size.let0() ?! 0)\nprintln(5.coerceIn(3, 1) ?! 1)".replace("vector(1.0).normalize().size.let0()", "normalize(vector(0.0)).size")))
    }

    // 回復の後も実行は続き、保存・復元のどの境界でも結果は中断しない実行と同じ（範囲の命令・受け手・関数の中・待機の途中を含む）
    @Test
    fun everyBoundaryRestores() {
        val sources = listOf(
            "fun div(a: Int, b: Int): Int {\n    waitTicks(1)\n    return a / b\n}\nval xs = listOf(1, 0, 2)\nval r = xs.map { div(10, it) ?! -1 }\nprintln(r)\nprintln(xs |> where { it > 0 } |> group { it % 2 })\nprintln(xs.average())",
            "fun safe(xs: List<Int>, i: Int): Int {\n    waitTicks(1)\n    return pick(xs, i) ?! -i\n}\nfun pick(xs: List<Int>, i: Int): Int {\n    waitTicks(1)\n    return xs[i]\n}\nprintln(listOf(0, 1, 5).map { safe(listOf(9, 8), it) })",
            "var total = 0\nval f = { x: Int -> x * x }\nlistOf(1, 2, 3).each {\n    waitTicks(1)\n    total = total + f(it)\n}\nprintln(total)\nval z = 0\nval q = (listOf(1, 2).map { it / z }) ?! listOf(7)\nprintln(q)",
        )
        for (source in sources) {
            val program = compile(source)
            val expected = CsVm(program).also { while (it.state == VmState.RUNNING || it.state == VmState.WAITING) step(it) }
            assertEquals(VmState.HALTED, expected.state, "${expected.error}")
            val original = CsVm(program)
            var cuts = 0
            while (original.state == VmState.RUNNING || original.state == VmState.WAITING) {
                val snapshot = original.snapshot()!!
                val restored = CsVm.restore(program, snapshot)
                assertNotNull(restored, "rejected at pc ${snapshot.pc}: ${SnapshotVerifier.verify(program, snapshot)} in $source")
                assertEquals(snapshot, restored!!.snapshot())
                while (restored.state == VmState.RUNNING || restored.state == VmState.WAITING) step(restored)
                assertEquals(expected.console.history, restored.console.history, source)
                step(original)
                cuts++
            }
            assertTrue(cuts > 50)
        }
    }

    // ?! を使わないプログラムの命令とハッシュはこれまでと同じ（範囲は空）。使うプログラムだけ範囲がハッシュに入る
    @Test
    fun handlersOnlyAppearWithFallbacks() {
        val plain = compile("val xs = listOf(1, 2)\nprintln(xs[0] / 1)")
        assertTrue(plain.handlers.isEmpty())
        val withHandler = compile("val xs = listOf(1, 2)\nprintln(xs[0] / 1 ?! 0)")
        assertEquals(1, withHandler.handlers.size)
        val stripped = withHandler.copy(handlers = emptyList())
        assertTrue(!ProgramFingerprint.ofProgram(withHandler).contentEquals(ProgramFingerprint.ofProgram(stripped)))
        assertArrayEquals(ProgramFingerprint.ofProgram(plain), ProgramFingerprint.ofProgram(plain.copy()))
    }

    // 不正な範囲は検証に通らない
    @Test
    fun malformedHandlersAreRejected() {
        val program = compile("val z = 0\nprintln(1 / z ?! 2)")
        val handler = program.handlers.single()
        val bad = listOf(
            handler.copy(stackDepth = 60),
            handler.copy(handler = handler.start),
            handler.copy(end = handler.start),
            handler.copy(end = program.instructions.size + 1),
            ErrorHandler(0, 1, program.instructions.size, 0),
        )
        for (candidate in bad) assertNull(SnapshotVerifier.framesOf(program.copy(handlers = listOf(candidate))), "$candidate")
        assertNotNull(SnapshotVerifier.framesOf(program))
    }

    // ---- S9.3 ラムダと型推論 ----

    @Test
    fun functionValuesAndInference() {
        assertEquals(listOf("6", "[2, 4]", "[1, 4, 9]", "true"), output(
            "val double = { x: Int -> x * 2 }\nval square: (Int) -> Int = { it * it }\nval small: (Int) -> Boolean = { it < 3 }\nprintln(double(3))\nprintln(listOf(1, 2).map(double))\nprintln(listOf(1, 2, 3) |> map(square))\nprintln(small(1))",
        ))
        assertEquals(listOf("[2, 4]", "11"), output("fun twice(x: Int): Int {\n    return x * 2\n}\nval t = ::twice\nprintln(listOf(1, 2).map(::twice))\nprintln(t(5) + 1)"))
        assertEquals(listOf("hi", "3"), output("val greet: () -> Unit = { println(\"hi\") }\ngreet()\nval add = { a: Int, b: Int -> a + b }\nprintln(add(1, 2))"))
        // 空のリストの要素の型を文脈から決める
        assertEquals(listOf("0", "[5]", "[]"), output("val a: List<Int> = listOf()\nval b: MutableList<Int> = mutableListOf()\nb.add(5)\nprintln(a.size)\nprintln(b)\nfun none(): List<String> {\n    return listOf()\n}\nprintln(none())"))
        // 結果の型の推論（Double）・関数の値を関数の値に入れる
        assertEquals(listOf("1.5", "4"), output("val half = { x: Int -> x / 2.0 }\nprintln(half(3))\nval d = { x: Int -> x + 1 }\nval e = d\nprintln(e(3))"))
    }

    @Test
    fun functionValueErrors() {
        assertEquals(listOf(DiagnosticCode.SEM_LAMBDA_PARAMETER_TYPE_REQUIRED), codes("val f = { x -> x }"))
        assertEquals(listOf(DiagnosticCode.SEM_LAMBDA_NOT_ALLOWED), codes("var f = { x: Int -> x }"))
        assertEquals(listOf(DiagnosticCode.SEM_LAMBDA_NOT_ALLOWED), codes("val f = { x: Int -> x }\nprintln(f)"))
        assertEquals(listOf(DiagnosticCode.SEM_LAMBDA_NOT_ALLOWED), codes("val f = { x: Int -> x }\nval xs = listOf(f)"))
        assertEquals(listOf(DiagnosticCode.SEM_FUNCTION_TYPE_NOT_ALLOWED), codes("fun g(f: (Int) -> Int): Int {\n    return 1\n}"))
        assertEquals(listOf(DiagnosticCode.SEM_FUNCTION_VALUE_OUTSIDE_SCOPE), codes("val f = { x: Int -> x }\nfun g(): Int {\n    return f(1)\n}"))
        assertEquals(listOf(DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH), codes("val f = { x: Int -> x }\nprintln(f(\"a\"))"))
        assertEquals(listOf(DiagnosticCode.SEM_TYPE_MISMATCH), codes("val f: (Int) -> String = { it }"))
        assertEquals(listOf(DiagnosticCode.SEM_LIST_TYPE_REQUIRED), codes("val a = listOf()"))
        assertEquals(listOf(DiagnosticCode.SEM_UNKNOWN_FUNCTION), codes("println(listOf(1).map(::nothing))"))
    }

    // 関数の値を入れ子に呼ぶと展開が指数的に増える。回数の上限で止める（独立版の上限なしの設定でも）
    @Test
    fun inlineExpansionIsBounded() {
        // 引数の無いラムダは変数を使わないため、変数の数の上限より先に展開の回数の上限に達する
        val source = StringBuilder("val f0 = { 1 }\n")
        for (i in 1..14) source.append("val f$i = { f${i - 1}() + f${i - 1}() }\n")
        source.append("println(f14())\n")
        val result = Compiler.compileSource(source.toString(), CompileOptions.STANDALONE)
        assertNull(result.program)
        assertEquals(listOf(DiagnosticCode.COMP_TOO_MANY_INLINE_EXPANSIONS), result.diagnostics.map { it.code })
        // 大きな本体を何度も展開する場合は、展開で出す命令の合計の上限で止める
        val body = (1..300).joinToString("\n") { "    n = n + 1" }
        val calls = (1..1000).joinToString("\n") { "println(big())" }
        val large = Compiler.compileSource("var n = 0\nval big = {\n$body\n    n\n}\n$calls\n", CompileOptions.STANDALONE)
        assertNull(large.program)
        assertEquals(listOf(DiagnosticCode.COMP_TOO_MANY_INLINE_EXPANSIONS), large.diagnostics.map { it.code })
        // 少なければ動く
        assertEquals(listOf("4"), output("val f0 = { x: Int -> x + 1 }\nval f1 = { x: Int -> f0(x) + f0(x) }\nval f2 = { x: Int -> f1(x) + f1(x) }\nprintln(f2(0))"))
    }
}
