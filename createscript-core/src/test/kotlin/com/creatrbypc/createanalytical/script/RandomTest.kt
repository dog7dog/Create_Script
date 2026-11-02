package com.creatrbypc.createanalytical.script

import com.creatrbypc.createanalytical.script.compiler.Compiler
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticCode
import com.creatrbypc.createanalytical.script.lexer.Lexer
import com.creatrbypc.createanalytical.script.parser.Parser
import com.creatrbypc.createanalytical.script.scheduler.ExecutionCondition
import com.creatrbypc.createanalytical.script.scheduler.PowerLevel
import com.creatrbypc.createanalytical.script.scheduler.Scheduler
import com.creatrbypc.createanalytical.script.semantic.ScriptType
import com.creatrbypc.createanalytical.script.semantic.SemanticAnalyzer
import com.creatrbypc.createanalytical.script.vm.ApproximateComparison
import com.creatrbypc.createanalytical.script.vm.CompiledProgram
import com.creatrbypc.createanalytical.script.vm.Constant
import com.creatrbypc.createanalytical.script.vm.CsVm
import com.creatrbypc.createanalytical.script.vm.HostFunction
import com.creatrbypc.createanalytical.script.vm.Instruction
import com.creatrbypc.createanalytical.script.vm.MathFunction
import com.creatrbypc.createanalytical.script.vm.NumericKind
import com.creatrbypc.createanalytical.script.vm.Opcode
import com.creatrbypc.createanalytical.script.vm.ProgramFingerprint
import com.creatrbypc.createanalytical.script.vm.SnapshotVerifier
import com.creatrbypc.createanalytical.script.vm.Value
import com.creatrbypc.createanalytical.script.vm.VmState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.SplittableRandom
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.nextDown
import kotlin.random.Random

// 第3.11e段階の乱数（random・randomInt・randomSeed）と isClose を、意味解析から CS_VM・SnapshotVerifier・Scheduler まで通して確かめる。
// 乱数の期待値は JDK の SplittableRandom（SplitMix64 と同じ列）、isClose の期待値は BigDecimal による誤差の無い計算
class RandomTest {
    private fun compile(source: String): CompiledProgram {
        val result = Compiler.compileSource(source)
        return result.program ?: error("compile failed: ${result.diagnostics.map { it.code }} in $source")
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

    private fun finish(vm: CsVm): CsVm {
        var steps = 0
        while (vm.state == VmState.RUNNING || vm.state == VmState.WAITING) {
            vm.advance(1000).also { if (vm.state == VmState.WAITING) vm.countWaitTick() }
            check(++steps < 2_000_000) { "did not finish" }
        }
        return vm
    }

    private fun output(source: String, seed: Long = 0L): List<String> {
        val vm = finish(CsVm(compile(source), seed))
        assertEquals(VmState.HALTED, vm.state, "error ${vm.error} in $source")
        return vm.console.history
    }

    private fun runtimeError(source: String): Pair<DiagnosticCode?, Int?> {
        val vm = finish(CsVm(compile(source)))
        return vm.error?.code to vm.error?.position?.column
    }

    private fun typeOf(source: String, name: String): ScriptType? =
        SemanticAnalyzer.analyze(Parser.parse(Lexer.tokenize(source))).program!!.declarations.values.single { it.name == name }.type

    // 200 個ずつリストに集めて待機するプログラムを走らせ、待機のたびにリストの要素（Double・Int）を読み出す
    private fun drawn(source: String, seed: Long, batches: Int): List<Value> {
        val vm = CsVm(compile("val xs = mutableListOf<${if (source.startsWith("randomInt")) "Int" else "Double"}>()\nwhile (true) {\n  xs.clear()\n  for (i in 0 until 200) { xs.add($source) }\n  waitTicks(1)\n}"), seed)
        val values = ArrayList<Value>()
        repeat(batches) {
            while (vm.state != VmState.WAITING) {
                assertEquals(VmState.RUNNING, vm.state, "${vm.error}")
                if (vm.isCollecting) vm.collectGarbage(1000) else vm.run(1000)
            }
            values += vm.snapshot()!!.objects.single().list!!.elements
            vm.countWaitTick()
        }
        return values
    }

    // 仕様どおりの参照の実装（SplittableRandom の nextLong を元にする）
    private class Reference(seed: Long) {
        private val source = SplittableRandom(seed)

        fun unit(): Double = source.nextDouble()

        fun between(min: Double, max: Double): Double {
            val u = unit()
            val width = max - min
            val value = if (width.isInfinite()) (min / 2 + u * (max / 2 - min / 2)) * 2 else min + u * width
            return if (value >= max) max.nextDown() else value
        }

        fun int(min: Int, max: Int): Int {
            val bound = max.toLong() - min
            var bits = source.nextLong() ushr 1
            var value = bits % bound
            while (bits - value + (bound - 1) < 0) {
                bits = source.nextLong() ushr 1
                value = bits % bound
            }
            return (min + value).toInt()
        }
    }

    private val seeds = listOf(0L, 1L, -1L, 42L, 7L, Long.MIN_VALUE, Long.MAX_VALUE, 123_456_789L, -987_654_321L, 0x5DEECE66DL, 2_147_483_647L, -2_147_483_648L)

    // ---- random() は SplitMix64（JDK の SplittableRandom と同じ列）。ホストの種と randomSeed(seed) のどちらからも同じ規則で始まる ----

    @Test
    fun randomMatchesSplittableRandom() {
        for (seed in seeds) {
            val reference = SplittableRandom(seed)
            val values = drawn("random()", seed, 50)
            assertEquals(10_000, values.size)
            for ((index, value) in values.withIndex()) assertEquals(reference.nextDouble(), (value as Value.DoubleValue).value, "seed $seed #$index")
        }
        // randomSeed(seed) は状態を seed.toLong() にする（ホストの種によらない）
        for (seed in listOf(0, 1, -1, 42, Int.MAX_VALUE, Int.MIN_VALUE)) {
            val reference = SplittableRandom(seed.toLong())
            val literal = if (seed == Int.MIN_VALUE) "-2147483648" else seed.toString()
            assertEquals(List(30) { reference.nextDouble().toString() }, output("randomSeed($literal)\nfor (i in 0 until 30) { println(random()) }", 999L))
        }
        // 同じプログラムでも、ホストの種が違えば違う列になる
        assertNotEquals(output("for (i in 0 until 5) { println(random()) }", 1L), output("for (i in 0 until 5) { println(random()) }", 2L))
        assertTrue(drawn("random()", 3L, 20).all { (it as Value.DoubleValue).value.let { v -> v >= 0.0 && v < 1.0 } })
    }

    // ---- random(min, max)・randomInt(min, max) は max を含まない。偏りが無く、範囲の端・幅が無限大になる範囲も正しい ----

    @Test
    fun rangesMatchReference() {
        val doubleRanges = listOf(0.0 to 1.0, -5.0 to 5.0, 10.0 to 20.0, -1e308 to 1e308, -1.7976931348623157E308 to 1.7976931348623157E308, 0.0 to 4.9e-324, 1.0 to 1.0000000000000002)
        for ((min, max) in doubleRanges) {
            for (seed in seeds.take(4)) {
                val reference = Reference(seed)
                val values = drawn("random($min, $max)", seed, 5).map { (it as Value.DoubleValue).value }
                for ((index, value) in values.withIndex()) {
                    assertEquals(reference.between(min, max), value, "[$min, $max) seed $seed #$index")
                    assertTrue(value >= min && value < max && value.isFinite(), "[$min, $max): $value")
                }
            }
        }
        val intRanges = listOf(0 to 6, 1 to 7, -3 to -1, 0 to 2, 0 to 1_073_741_825, Int.MIN_VALUE to Int.MAX_VALUE, Int.MAX_VALUE - 1 to Int.MAX_VALUE, Int.MIN_VALUE to Int.MIN_VALUE + 1, -100 to 100)
        for ((min, max) in intRanges) {
            for (seed in seeds.take(4)) {
                val reference = Reference(seed)
                val literal = { n: Int -> if (n == Int.MIN_VALUE) "-2147483648" else n.toString() }
                val values = drawn("randomInt(${literal(min)}, ${literal(max)})", seed, 5).map { (it as Value.IntValue).value }
                for ((index, value) in values.withIndex()) {
                    assertEquals(reference.int(min, max), value, "[$min, $max) seed $seed #$index")
                    assertTrue(value >= min && value < max, "[$min, $max): $value")
                }
            }
        }
        // 偏りが無い（カイ二乗。固定の種なので結果は毎回同じ）: randomInt(0, 6) と randomInt(0, 7) を 6000・7000 回
        for (bound in listOf(6, 7)) {
            val counts = IntArray(bound)
            drawn("randomInt(0, $bound)", 11L, 5 * bound).forEach { counts[(it as Value.IntValue).value]++ }
            val expected = counts.sum().toDouble() / bound
            val chi = counts.sumOf { (it - expected) * (it - expected) / expected }
            assertTrue(chi < 25.0, "chi-square $chi for ${counts.toList()}")
        }
        // 1 と 6 を含み 7 を含まない
        assertEquals((1..6).toSet(), drawn("randomInt(1, 7)", 5L, 3).map { (it as Value.IntValue).value }.toSet())
    }

    // ---- 型の規則と診断。範囲の誤り（空・NaN・Infinity）は実行時エラー ----

    @Test
    fun randomDiagnostics() {
        assertEquals(ScriptType.DOUBLE, typeOf("val v = random()", "v"))
        assertEquals(ScriptType.DOUBLE, typeOf("val v = random(1.0, 2.0)", "v"))
        assertEquals(ScriptType.INT, typeOf("val v = randomInt(1, 2)", "v"))
        val errors = listOf(
            "println(random(1.0))" to DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT,
            "println(random(1.0, 2.0, 3.0))" to DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT,
            "println(randomInt())" to DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT,
            "println(randomInt(1))" to DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT,
            "randomSeed()" to DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT,
            "randomSeed(1, 2)" to DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT,
            "println(random(0, 10.0))" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "println(random(0.0, 10))" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "println(random(1f, 2.0))" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "println(randomInt(0, 1.5))" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "randomSeed(1.5)" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "randomSeed(\"a\")" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "println(1.0.random())" to DiagnosticCode.SEM_MEMBER_ACCESS_UNSUPPORTED,
            "println(nextDouble())" to DiagnosticCode.SEM_UNKNOWN_FUNCTION,
            "val d: Int = random()" to DiagnosticCode.SEM_TYPE_MISMATCH,
        )
        for ((source, code) in errors) assertEquals(listOf(code), codes(source), source)
        assertEquals(listOf(9), listOf(Compiler.compileSource("println(random(1.0))").diagnostics.single().position.column))
        assertEquals(listOf(16), listOf(Compiler.compileSource("println(random(0, 10.0))").diagnostics.single().position.column))
        // 実行時エラー（位置は関数の名前）
        val runtime = listOf(
            "println(random(1.0, 1.0))", "println(random(2.0, 1.0))", "println(random(0.0, 0.0 / 0.0))", "println(random(0.0 / 0.0, 1.0))",
            "println(random(0.0, 1.0 / 0.0))", "println(random(-1.0 / 0.0, 0.0))", "println(randomInt(5, 5))", "println(randomInt(5, 4))",
            "println(randomInt(2147483647, -2147483648))",
        )
        for (source in runtime) assertEquals(DiagnosticCode.RUN_INVALID_RANDOM_RANGE to 9, runtimeError(source), source)
    }

    // ---- VM ごとに独立: 同じ種なら同じ列、違う種なら違う列。交互に動かしても混ざらない ----

    @Test
    fun randomIsIndependentPerVm() {
        val program = compile("var x = 0.0\nwhile (true) { x = random() }")
        fun values(vm: CsVm, count: Int) = List(count) {
            while (true) {
                vm.run(1)
                val pc = vm.snapshot()!!.pc
                if (program.instructions[pc - 1].opcode == Opcode.STORE) break
            }
            (vm.snapshot()!!.variables[0] as Value.DoubleValue).value
        }
        val a = CsVm(program, 5L)
        val b = CsVm(program, 5L)
        val c = CsVm(program, 6L)
        val interleaved = ArrayList<Double>()
        val alone = values(CsVm(program, 5L), 200)
        repeat(100) {
            interleaved += values(a, 1)
            values(c, 3)
            interleaved += values(a, 1)
            values(b, 2)
        }
        assertEquals(alone, interleaved)
        assertEquals(alone.take(10), values(CsVm(program, 5L), 10))
        assertNotEquals(alone.take(10), values(CsVm(program, 6L), 10))
    }

    // ---- 乱数の状態は保存・復元を通して変わらない。すべての境界で復元しても、続きの列は中断しない場合と同じ ----

    @Test
    fun randomSurvivesSnapshots() {
        val source = "val a = random()\nval b = randomInt(0, 1000)\nwaitTicks(3)\nprintln(a)\nprintln(b)\nprintln(random())\nprintln(randomInt(-50, 50))\nprintln(random(10.0, 20.0))\n" +
            "randomSeed(7)\nprintln(random())\nprintln(isClose(a, a + 1e-12))"
        val program = compile(source)
        for (seed in listOf(99L, -3L)) {
            val expected = finish(CsVm(program, seed)).console.history
            val reference = Reference(seed)
            val first = reference.unit()
            assertEquals(listOf(first.toString(), reference.int(0, 1000).toString(), reference.unit().toString(), reference.int(-50, 50).toString(), reference.between(10.0, 20.0).toString()), expected.take(5))
            assertEquals(listOf(SplittableRandom(7L).nextDouble().toString(), "true"), expected.drop(5))
            val original = CsVm(program, seed)
            while (original.state == VmState.RUNNING || original.state == VmState.WAITING) {
                val cut = original.snapshot()!!
                // 復元するときのホストの種は使わない（保存した状態から続ける）
                val restored = CsVm.restore(program, cut)
                assertNotNull(restored, "rejected: ${SnapshotVerifier.verify(program, cut)}")
                assertEquals(cut, restored!!.snapshot())
                while (restored.state == VmState.RUNNING || restored.state == VmState.WAITING) step(restored)
                assertEquals(expected, restored.console.history)
                step(original)
            }
            assertEquals(expected, original.console.history)
        }
        val vm = CsVm(compile("randomSeed(3)\nwaitTicks(1)"), 1L)
        assertEquals(1L, vm.snapshot()!!.randomState)
        while (vm.state != VmState.WAITING) vm.run(1)
        assertEquals(3L, vm.snapshot()!!.randomState)
    }

    // ---- 名前は予約しない。既存のプログラム（第3.11d段階の jar でコンパイルした指紋）は変わらない ----

    // 第3.11d段階のビルド（乱数・isClose・Vector・Matrix の無い jar）でコンパイルしたときの指紋。第3.11e段階で追加する名前を利用者が使うプログラム
    private val legacyFingerprints = listOf(
        "val random = 3\nvar length = random * 2\nlength = 4\nprintln(length + random)" to "97211123f5a383ac1375c6269e4935ce17418eb6890d3b6425c4441fa5c0072c",
        "fun random(): Int { return 4 }\nfun dot(a: Int, b: Int): Int { return a * b }\nprintln(random())\nprintln(dot(3, 4))" to "0811914475e44fc2c32a287a94231561279ca106055d2aa3171c1cc7270d1edf",
        "class Vector {\n  var x = 1.5\n  var rowCount = 2\n  fun length(): Double { return x * 2.0 }\n}\nval v = Vector()\nprintln(v.length())\nprintln(v.rowCount)" to
            "b42b8ee69ba3c84b24351780114bbbe9f523e1595b7eb57198d1ab8ecb5d449a",
        "class Matrix\nval m = Matrix()\nprintln(m)" to "81462a55d126b06c1a7c4b5bea0b52c335b2d7bf8bc566d4e13949b24a698c94",
        "fun normalize(vector: List<Double>, matrix: Int): Double { return vector[0] * matrix }\nfor (randomSeed in 0..2) { println(normalize(listOf(1.5, 2.0), randomSeed)) }" to
            "f5a344a3066d6dab3c34de369164a9fb3e6a9883e3a201fa4f05711ee8c5150c",
        "val m = mutableListOf(mutableListOf(1.0, 2.0))\nm[0][1] = 5.0\nprintln(m)\nprintln(hypot(3.0, 4.0) + ln(E))\nprintln(1.5.isNaN())" to
            "745108c2643a2dee6cf0a534549b655f1f37cd93374e46ce3b28103d66317a0d",
        "fun isClose(a: Double, b: Double): Boolean { return a == b }\nvar transpose = 0\nwhile (transpose < 3) { transpose = transpose + 1 }\nwaitTicks(2)\nprintln(isClose(1.0, 1.0))\nprintln(transpose)" to
            "383b4185911a020fb9b74b362f035332133a1fe41a7d1c89aa9e6edd9908d308",
        "fun matmul(randomInt: Int): Int { return randomInt * 2 }\nclass P {\n  var columnCount = 3\n}\nprintln(P().columnCount)\nprintln(matmul(21))" to
            "4a40347b46c73b9b5a9e6c98f534e549c98beca5bb310b1905bd00dbf6ce92a6",
    )

    @Test
    fun namesAreNotReservedAndFingerprintsAreKept() {
        for ((source, hash) in legacyFingerprints) {
            assertEquals(hash, ProgramFingerprint.ofProgram(compile(source)).joinToString("") { "%02x".format(it) }, source)
        }
        assertEquals(listOf("4", "12"), output(legacyFingerprints[1].first))
        assertEquals(listOf("true", "3"), output(legacyFingerprints[6].first))
        // 変数と同じ名前でも、呼び出しは乱数のまま
        assertEquals(listOf("3", "true"), output("val random = 3\nprintln(random)\nprintln(random() < 1.0)"))
        // ユーザー定義の関数が優先する（同じ名前の関数があると、乱数は呼べない）
        assertEquals(listOf(DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT), codes("fun randomInt(a: Int): Int { return a }\nprintln(randomInt(1, 2))"))
        assertEquals(listOf("7"), output("class M {\n  fun randomSeed(a: Int): Int { return a + 6 }\n  fun f(): Int { return randomSeed(1) }\n}\nprintln(M().f())"))
        assertEquals(listOf("random@1"), output("class random\nprintln(random())"))
    }

    // ---- 命令: 乱数は CALL_HOST（番号 3〜6）、isClose は MATH の番号 27（tolerance を省くと初期値の定数を積む） ----

    @Test
    fun instructionsUseExistingOpcodes() {
        assertEquals(listOf(3, 4, 5, 6), listOf(HostFunction.RANDOM, HostFunction.RANDOM_RANGE, HostFunction.RANDOM_INT, HostFunction.RANDOM_SEED).map { it.id })
        assertEquals(listOf(0, 1, 2), HostFunction.entries.take(3).map { it.id })
        assertEquals(27, MathFunction.IS_CLOSE.id)
        assertEquals(3, MathFunction.IS_CLOSE.arity)
        fun calls(source: String) = compile(source).instructions.filter { it.opcode == Opcode.CALL_HOST || it.opcode == Opcode.MATH }.map { it.opcode to it.operand }
        assertEquals(
            listOf(Opcode.CALL_HOST to 3, Opcode.CALL_HOST to 4, Opcode.CALL_HOST to 5, Opcode.CALL_HOST to 6, Opcode.CALL_HOST to HostFunction.PRINTLN.id),
            calls("val a = random()\nval b = random(1.0, 2.0)\nval c = randomInt(1, 2)\nrandomSeed(4)\nprintln(a)"),
        )
        val defaults = compile("val a = isClose(1.0, 2.0)\nval b = isClose(1f, 2f)\nval c = isClose(1.0, 2.0, 0.5)")
        assertEquals(
            listOf(MathFunction.IS_CLOSE.operand(NumericKind.DOUBLE), MathFunction.IS_CLOSE.operand(NumericKind.FLOAT), MathFunction.IS_CLOSE.operand(NumericKind.DOUBLE)),
            defaults.instructions.filter { it.opcode == Opcode.MATH }.map { it.operand },
        )
        assertTrue(Constant.DoubleConstant(1e-9.toRawBits()) in defaults.constants && Constant.FloatConstant(1e-5f.toRawBits()) in defaults.constants)
        assertEquals(ScriptType.BOOLEAN, typeOf("val v = isClose(1f, 2f)", "v"))
        // 1 回の乱数・isClose は 1 命令
        val vm = CsVm(compile("val a = random()\nval b = isClose(a, 0.5, 1.0)\nprintln(b)"), 8L)
        assertEquals(2, vm.run(2))
        assertEquals(Value.DoubleValue.of(SplittableRandom(8L).nextDouble()), vm.snapshot()!!.variables[0])
        assertEquals(5, vm.run(5))
        assertEquals(Value.BoolValue(true), vm.snapshot()!!.variables[1])
    }

    // ---- Verifier と VM: 不正な CALL_HOST・MATH の operand、型の合わない値を拒否する ----

    private fun replaced(program: CompiledProgram, opcode: Opcode, replacement: (Instruction) -> Instruction): CompiledProgram {
        val index = program.instructions.indexOfFirst { it.opcode == opcode }
        check(index >= 0)
        return program.copy(instructions = program.instructions.mapIndexed { i, instruction -> if (i == index) replacement(instruction) else instruction })
    }

    @Test
    fun invalidBytecodeIsRejected() {
        val range = compile("val d = random(1.0, 2.0)\nprintln(d)")
        val close = compile("val d = 1.5\nprintln(isClose(d, d, d))")
        assertNotNull(SnapshotVerifier.framesOf(range))
        assertNotNull(SnapshotVerifier.framesOf(close))
        for (operand in listOf(7, -1, Int.MAX_VALUE, HostFunction.RANDOM_INT.id, HostFunction.RANDOM_SEED.id)) {
            assertNull(SnapshotVerifier.framesOf(replaced(range, Opcode.CALL_HOST) { it.copy(operand = operand) }), "CALL_HOST $operand")
        }
        val unknown = MathFunction.entries.size * NumericKind.COUNT
        for (operand in listOf(MathFunction.IS_CLOSE.operand(NumericKind.INT), MathFunction.IS_CLOSE.operand(NumericKind.FLOAT), unknown, unknown + 2)) {
            assertNull(SnapshotVerifier.framesOf(replaced(close, Opcode.MATH) { it.copy(operand = operand) }), "MATH $operand")
        }
        // Verifier を通さずに実行しても、VM が実行時エラーで止める
        for ((program, opcode, operand) in listOf(
            Triple(range, Opcode.CALL_HOST, 7), Triple(range, Opcode.CALL_HOST, HostFunction.RANDOM_INT.id), Triple(range, Opcode.CALL_HOST, HostFunction.RANDOM_SEED.id),
            Triple(close, Opcode.MATH, MathFunction.IS_CLOSE.operand(NumericKind.INT)), Triple(close, Opcode.MATH, MathFunction.IS_CLOSE.operand(NumericKind.FLOAT)), Triple(close, Opcode.MATH, unknown),
        )) {
            val vm = finish(CsVm(replaced(program, opcode) { it.copy(operand = operand) }))
            assertEquals(DiagnosticCode.RUN_INVALID_BYTECODE, vm.error?.code, "$opcode $operand")
        }
    }

    // ---- isClose: 境界の例（4 章の表）。NaN は false、同じ符号の Infinity・0.0 と -0.0 は true、有限値どうしはオーバーフローしない ----

    @Test
    fun closeHandlesBoundaries() {
        val cases = listOf(
            "1.7976931348623157E308, -1.7976931348623157E308, 1.5" to false, "1.7976931348623157E308, -1.7976931348623157E308, 2.0" to true,
            "1e300, -1e300, 1.9999999999999998" to false, "1e308, 1.0000000001e308" to true, "1e308, 1.00000001e308" to false,
            "4.9E-324, 0.0, 1e-300" to true, "4.9E-324, 0.0, 0.0" to false, "4.9E-324, 0.0, 4.9E-324" to true, "1.0E-323, 0.0, 4.9E-324" to false,
            "sin(PI), 0.0" to true, "0.1 + 0.2, 0.3" to true, "0.0, -0.0, 0.0" to true, "1.0 / 0.0, 1.0 / 0.0, 0.0" to true, "1.0 / 0.0, -1.0 / 0.0, 1e300" to false,
            "1.0 / 0.0, 1.7976931348623157E308, 1.0" to false, "0.0 / 0.0, 0.0 / 0.0, 1.0" to false, "0.0 / 0.0, 1.0" to false, "1.0, 1.0000000001" to true,
            "1.0, 1.000000002" to false, "1e-10, 0.0" to true, "1e-8, 0.0" to false, "-1e-10, 1e-10" to true, "100.0, 100.00000001" to true, "100.0, 100.0000002" to false,
            "1.7976931348623157E308, 1.7976931348623157E308, 0.0" to true, "1.7976931348623157E308, 1.0, 1.0" to true, "-1.7976931348623157E308, 1.0, 0.9999999999999999" to false,
            "3.4028235E38f, -3.4028235E38f, 1.5f" to false, "3.4028235E38f, -3.4028235E38f, 2f" to true, "1.4E-45f, 0f" to true, "1f, 1.00001f" to false,
            "1f, 1.000001f" to true, "0f, -0f, 0f" to true, "1f / 0f, 1f / 0f" to true, "1f / 0f, -1f / 0f, 1f" to false, "0f / 0f, 0f / 0f" to false, "1.4E-45f, 0f, 0f" to false,
        )
        assertEquals(cases.map { it.second.toString() }, cases.chunked(30).flatMap { chunk -> output(chunk.joinToString("\n") { "println(isClose(${it.first}))" }) })
        // 単純な式 |a - b| <= tol × max(1, |a|, |b|) は、差のオーバーフローで true と誤る
        val max = Double.MAX_VALUE
        assertTrue(abs(max - -max) <= 1.5 * max(1.0, max(abs(max), abs(-max))))
        // 不正な tolerance は a・b によらず実行時エラー（位置は isClose）
        for (source in listOf(
            "println(isClose(1.0, 1.0, -1e-9))", "println(isClose(1.0, 1.0, 0.0 / 0.0))", "println(isClose(1.0, 2.0, 1.0 / 0.0))", "println(isClose(0.0 / 0.0, 1.0, -1.0))",
            "println(isClose(1f, 1f, -1f))", "println(isClose(1f, 1f, 1f / 0f))", "println(isClose(1f, 1f, 0f / 0f))",
        )) assertEquals(DiagnosticCode.RUN_INVALID_TOLERANCE to 9, runtimeError(source), source)
        assertEquals(listOf("true"), output("println(isClose(1.0, 1.0, -0.0))"))
        val errors = listOf(
            "println(isClose(1, 2))" to DiagnosticCode.SEM_INVALID_OPERAND_TYPE,
            "println(isClose(1.0, 1f))" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "println(isClose(1.0, 2.0, 1f))" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "println(isClose(1.0, 2.0, 1))" to DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH,
            "println(isClose(1.0))" to DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT,
            "println(isClose(1.0, 2.0, 3.0, 4.0))" to DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT,
            "println(1.0.isClose(2.0))" to DiagnosticCode.SEM_MEMBER_ACCESS_UNSUPPORTED,
            "val d: Double = isClose(1.0, 1.0)" to DiagnosticCode.SEM_TYPE_MISMATCH,
        )
        for ((source, code) in errors) assertEquals(listOf(code), codes(source), source)
    }

    // ---- isClose: 無作為の値（境界のすぐ内側・外側・非正規化数・極端な値・オーバーフローする組み合わせ）を BigDecimal の誤差の無い判定と比べる ----

    private fun exact(a: Double, b: Double, tolerance: Double): Boolean {
        if (a == b) return true
        if (!a.isFinite() || !b.isFinite()) return false
        val x = BigDecimal(a)
        val y = BigDecimal(b)
        return x.subtract(y).abs() <= BigDecimal(tolerance).multiply(x.abs().max(y.abs()).max(BigDecimal.ONE))
    }

    @Test
    fun closeIsExact() {
        val random = Random(3_11_5)
        val specials = listOf(
            0.0, -0.0, Double.MIN_VALUE, 2 * Double.MIN_VALUE, java.lang.Double.MIN_NORMAL, 1e-300, 1.0, 1.0.nextDown(), Math.nextUp(1.0), 2.0, 3.0, 1e-9, 1e300,
            Double.MAX_VALUE, Double.MAX_VALUE.nextDown(), Math.scalb(1.0, 1000), Math.scalb(1.0, 1001), Math.scalb(1.0, 970), Math.scalb(1.0, -747), Double.NaN,
            Double.POSITIVE_INFINITY,
        )
        val tolerances = listOf(0.0, Double.MIN_VALUE, 3 * Double.MIN_VALUE, 1e-310, Math.scalb(1.0, -900), 1e-300, 1e-15, 1e-9, 1e-3, 0.5, 1.0, 1.5, 2.0.nextDown(), 2.0, 1e300, Double.MAX_VALUE)
        fun value(): Double {
            val v = when (random.nextInt(4)) {
                0 -> specials.random(random)
                1 -> Math.scalb(random.nextDouble(), random.nextInt(2100) - 1075)
                2 -> 1.0 + java.util.Random(random.nextLong()).nextGaussian() * Math.scalb(1.0, -random.nextInt(60))
                else -> Double.fromBits(random.nextLong())
            }
            return if (random.nextBoolean()) v else -v
        }
        var checked = 0
        var near = 0
        repeat(1_000_000) {
            val a = value()
            val tolerance = if (random.nextInt(5) == 0) Math.scalb(random.nextDouble(), -random.nextInt(1100)) else tolerances.random(random)
            val b = when (random.nextInt(4)) {
                0 -> a * (1 + Math.scalb(random.nextDouble() - 0.5, -random.nextInt(55)))
                1 -> -a + Math.scalb(random.nextDouble() - 0.5, random.nextInt(2000) - 1000)
                // 境界の近く（真の境界から 1 ulp 程度）
                2 -> (a + tolerance * max(1.0, abs(a)) * (if (random.nextBoolean()) 1 else -1)).also { near++ }
                else -> value()
            }
            assertEquals(exact(a, b, tolerance), ApproximateComparison.isClose(a, b, tolerance), "isClose($a, $b, $tolerance)")
            checked++
        }
        // Float: 値は Double で正確に表せるため、Double の判定と同じ
        repeat(300_000) {
            val a = if (random.nextBoolean()) Float.fromBits(random.nextInt()) else value().toFloat()
            val tolerance = listOf(0f, Float.MIN_VALUE, 1e-7f, 1e-5f, 0.5f, 1f, 1.5f, 2f.nextDown(), 2f, Float.MAX_VALUE).random(random)
            val b = if (random.nextBoolean()) (a + tolerance * max(1f, abs(a)) * (if (random.nextBoolean()) 1 else -1)) else Float.fromBits(random.nextInt())
            assertEquals(exact(a.toDouble(), b.toDouble(), tolerance.toDouble()), ApproximateComparison.isClose(a.toDouble(), b.toDouble(), tolerance.toDouble()), "isClose(${a}f, ${b}f, ${tolerance}f)")
        }
        assertTrue(checked == 1_000_000 && near > 200_000, "near $near")
    }

    // ---- 予算と公平性: 乱数・isClose も 1 命令 = 1 単位で、FAST 128 命令 / tick・全体 4096 命令 / tick を守る ----

    private val loops = listOf(
        "var d = 0.0\nwhile (true) { d = random() + random(1.0, 2.0) }",
        "var n = 0\nwhile (true) { n = randomInt(-5, 5) + n % 7 }",
        "var b = false\nwhile (true) { b = isClose(random(), 0.5, 0.1) || isClose(1f, 1.00001f) }",
        "while (true) { randomSeed(randomInt(-2147483648, 2147483647)) }",
    )

    @Test
    fun programsStayWithinBudgetAndFair() {
        val programs = loops.map(::compile)
        for (program in programs) assertEquals(VmState.RUNNING, CsVm(program, 1L).also { it.run(500_000) }.state)
        val scheduler = Scheduler<Int>(4096)
        repeat(300) { scheduler.register(it, CsVm(programs[it % programs.size], it.toLong())) }
        val fast = ExecutionCondition(PowerLevel.FAST, true)
        scheduler.tick { fast }
        val totals = IntArray(300)
        repeat(200) {
            val result = scheduler.tick { fast }
            assertEquals(4096, result.budgetUsed)
            assertTrue(result.executed.values.all { it <= PowerLevel.FAST.instructionsPerTick })
            assertTrue(result.finished.isEmpty(), "${result.finished}")
            result.executed.forEach { (key, count) -> totals[key] += count }
        }
        assertTrue(totals.max() - totals.min() <= 1, "unfair: ${totals.min()}..${totals.max()}")
        val few = Scheduler<Int>(4096)
        programs.forEachIndexed { index, program -> few.register(index, CsVm(program, index.toLong())) }
        few.tick { fast }
        repeat(50) { few.tick { fast } }
        assertEquals(programs.indices.associateWith { PowerLevel.FAST.instructionsPerTick }, few.tick { fast }.executed)
    }

    // ---- 性能（実測） ----

    private fun nanosPerInstruction(vms: List<CsVm>): Double {
        vms.forEach { it.run(200_000) }
        val start = System.nanoTime()
        var executed = 0L
        vms.forEach { executed += it.run(1_000_000) }
        return (System.nanoTime() - start).toDouble() / executed
    }

    private fun perTick(scheduler: Scheduler<Int>, ticks: Int = 1000): String {
        val fast = ExecutionCondition(PowerLevel.FAST, true)
        repeat(300) { scheduler.tick { fast } }
        val times = DoubleArray(ticks)
        for (index in 0 until ticks) {
            val start = System.nanoTime()
            scheduler.tick { fast }
            times[index] = (System.nanoTime() - start) / 1_000_000.0
        }
        val sorted = times.sorted()
        return "avg %.3f / p50 %.3f / p99 %.3f / max %.3f ms".format(times.average(), sorted[ticks / 2], sorted[ticks * 99 / 100], sorted.last())
    }

    @Test
    fun measureRandom() {
        val int = "var n = 0\nwhile (true) { n = (n + 1) % 1000 * 1 }"
        val labels = listOf("random + random(min, max)", "randomInt", "isClose", "randomSeed + randomInt")
        for ((label, source) in listOf("Int arithmetic" to int) + labels.zip(loops)) {
            println("[3.11e measure] per instruction, $label: %.1f ns".format(nanosPerInstruction(List(20) { CsVm(compile(source), it.toLong()) })))
        }
        for ((label, source) in listOf("1000 VMs random" to loops[0], "1000 VMs isClose" to loops[2], "1000 VMs Int arithmetic" to int)) {
            val program = compile(source)
            val scheduler = Scheduler<Int>(4096)
            repeat(1000) { scheduler.register(it, CsVm(program, it.toLong()).also { machine -> machine.run(3000) }) }
            println("[3.11e measure] $label: ${perTick(scheduler)}")
        }
    }
}
