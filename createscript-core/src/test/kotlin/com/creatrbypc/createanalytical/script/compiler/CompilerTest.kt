package com.creatrbypc.createanalytical.script.compiler

import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticArgument
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticCode
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticCollector
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticPhase
import com.creatrbypc.createanalytical.script.diagnostic.SourcePosition
import com.creatrbypc.createanalytical.script.vm.CompiledProgram
import com.creatrbypc.createanalytical.script.vm.Constant
import com.creatrbypc.createanalytical.script.vm.FunctionInfo
import com.creatrbypc.createanalytical.script.vm.HostFunction
import com.creatrbypc.createanalytical.script.vm.Instruction
import com.creatrbypc.createanalytical.script.vm.Opcode
import com.creatrbypc.createanalytical.script.vm.SnapshotVerifier
import com.creatrbypc.createanalytical.script.vm.VmLimits
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompilerTest {
    private fun compile(source: String): CompiledProgram {
        val result = Compiler.compileSource(source)
        assertFalse(result.hasErrors, "unexpected errors: ${result.diagnostics.map { it.code }}")
        return result.program!!
    }

    private fun opcodes(source: String) = compile(source).instructions.map { it.opcode }

    @Test
    fun declarationAndAssignment() {
        assertEquals(
            listOf(Opcode.PUSH_CONST, Opcode.STORE, Opcode.LOAD, Opcode.PUSH_CONST, Opcode.ADD, Opcode.STORE, Opcode.HALT),
            opcodes("var x = 1\nx = x + 2"),
        )
        val program = compile("val a = 1\nval s = \"x\"")
        assertEquals(2, program.variableCount)
    }

    @Test
    fun expressionStatementsArePopped() {
        val program = compile("println(1)\nwaitTicks(0)\nprintln()")
        assertEquals(
            listOf(
                Opcode.PUSH_CONST, Opcode.CALL_HOST, Opcode.POP,
                Opcode.PUSH_CONST, Opcode.CALL_HOST, Opcode.POP,
                Opcode.CALL_HOST, Opcode.POP, Opcode.HALT,
            ),
            program.instructions.map { it.opcode },
        )
        val hosts = program.instructions.filter { it.opcode == Opcode.CALL_HOST }.map { it.operand }
        assertEquals(listOf(HostFunction.PRINTLN.id, HostFunction.WAIT_TICKS.id, HostFunction.PRINTLN_EMPTY.id), hosts)
    }

    @Test
    fun unaryOperators() {
        // -2147483648 は1つの定数として積む（NEGATE は出さない）
        val min = compile("val a = -2147483648")
        assertEquals(listOf(Opcode.PUSH_CONST, Opcode.STORE, Opcode.HALT), min.instructions.map { it.opcode })
        assertEquals(listOf(Constant.IntConstant(Int.MIN_VALUE)), min.constants)
        assertEquals(listOf(Opcode.LOAD, Opcode.NEGATE), opcodes("val a = 1\nval b = -a").subList(2, 4))
        // 単項 + は命令を出さない
        assertEquals(listOf(Opcode.LOAD, Opcode.STORE), opcodes("val a = 1\nval b = +a").subList(2, 4))
    }

    // ---- Boolean・比較・論理演算子（第3.7b段階） ----

    private fun shape(source: String) = compile(source).instructions.map { if (it.opcode == Opcode.JUMP || it.opcode == Opcode.JUMP_IF_FALSE || it.opcode == Opcode.PUSH_BOOL) "${it.opcode}:${it.operand}" else it.opcode.name }

    @Test
    fun booleanAndComparisonInstructions() {
        // true / false は定数プールを使わない
        val literals = compile("val t = true\nval f = false")
        assertEquals(listOf("PUSH_BOOL:1", "STORE", "PUSH_BOOL:0", "STORE", "HALT"), shape("val t = true\nval f = false"))
        assertEquals(emptyList<Constant>(), literals.constants)
        assertEquals(listOf("LOAD", "NOT", "STORE"), shape("val t = true\nval f = !t").subList(2, 5))
        assertEquals(listOf("LOAD", "NOT", "NOT", "STORE"), shape("val t = true\nval f = !!t").subList(2, 6))
        for ((symbol, opcode) in listOf("==" to "EQUAL", "!=" to "NOT_EQUAL", "<" to "LESS", "<=" to "LESS_EQUAL", ">" to "GREATER", ">=" to "GREATER_EQUAL")) {
            assertEquals(listOf("PUSH_CONST", "PUSH_CONST", opcode, "STORE", "HALT"), shape("val b = 1 $symbol 2"), symbol)
        }
        // String は内容で比べるため、同じ内容の定数は1つにまとめても結果は変わらない
        assertEquals(listOf(Constant.StringConstant("a")), compile("val b = \"a\" == \"a\"").constants)
    }

    @Test
    fun shortCircuitLayout() {
        // a && b: a; JUMP_IF_FALSE L; b; JUMP E; L: PUSH_BOOL 0; E:
        assertEquals(
            listOf("PUSH_BOOL:1", "STORE", "LOAD", "JUMP_IF_FALSE:6", "LOAD", "JUMP:7", "PUSH_BOOL:0", "STORE", "HALT"),
            shape("val p = true\nval x = p && p"),
        )
        // a || b: a; JUMP_IF_FALSE R; PUSH_BOOL 1; JUMP E; R: b; E:
        assertEquals(
            listOf("PUSH_BOOL:1", "STORE", "LOAD", "JUMP_IF_FALSE:6", "PUSH_BOOL:1", "JUMP:7", "LOAD", "STORE", "HALT"),
            shape("val p = true\nval x = p || p"),
        )
        // 入れ子: (p || q) && r の右辺は、外側の JUMP_IF_FALSE の後に置く
        assertEquals(
            listOf(
                "PUSH_BOOL:1", "STORE", "PUSH_BOOL:0", "STORE",
                "LOAD", "JUMP_IF_FALSE:8", "PUSH_BOOL:1", "JUMP:9", "LOAD", "JUMP_IF_FALSE:12", "LOAD", "JUMP:13", "PUSH_BOOL:0",
                "CALL_HOST", "POP", "HALT",
            ),
            shape("val p = true\nval q = false\nprintln((p || q) && p)"),
        )
    }

    @Test
    fun shortCircuitStackDepth() {
        // 右辺を評価しない経路の深さは分岐先で戻すため、深さは経路ごとの最大になる
        assertEquals(1, compile("val p = true\nval x = p && p || p && p").maxStackDepth)
        assertEquals(3, compile("val p = 1 + (2 + 3) < 4 && true").maxStackDepth)
        assertEquals(3, compile("val p = true && 1 + (2 + 3) < 4").maxStackDepth)
        // 32 段の入れ子でも上限（64）を超えない
        val nested = "val p = true\nval x = " + "(p && ".repeat(30) + "p" + ")".repeat(30)
        assertEquals(1, compile(nested).maxStackDepth)
    }

    // Compiler の出力は毎回 SnapshotVerifier の検証を通る。通らなければ内部エラー CS_COMP_VERIFICATION_FAILED で、プログラムを返さない
    @Test
    fun outputIsVerified() {
        val program = compile("val p = 1 < 2 || 1 / 0 == 0\nprintln(p && !p)")
        assertNotNull(SnapshotVerifier.framesOf(program))
        assertSame(program, Compiler.verified(program, DiagnosticCollector()))
        val origin = SourcePosition(0, 1, 1)
        val broken = listOf(
            // 分岐先が範囲外
            program.copy(instructions = program.instructions.map { if (it.opcode == Opcode.JUMP) it.copy(operand = 999) else it }),
            // 合流点でスタックの型が合わない（右辺を評価しない経路で Boolean の代わりに Int を積む）
            program.copy(instructions = program.instructions.map { if (it.opcode == Opcode.PUSH_BOOL) Instruction(Opcode.PUSH_CONST, 0, origin) else it }),
            // 合流点でスタックの深さが合わない（右辺を評価しない経路で何も積まない）
            program.copy(instructions = program.instructions.mapIndexed { pc, it -> if (it.opcode == Opcode.PUSH_BOOL) Instruction(Opcode.JUMP, pc + 1, origin) else it }),
            // 分岐の条件が Boolean でない
            CompiledProgram(listOf(Instruction(Opcode.PUSH_CONST, 0, origin), Instruction(Opcode.JUMP_IF_FALSE, 2, origin), Instruction(Opcode.HALT, 0, origin)), listOf(Constant.IntConstant(1)), 0, 1),
            // 宣言した深さ（maxStackDepth）を超えて積む
            program.copy(maxStackDepth = program.maxStackDepth - 1),
        )
        for (bad in broken) {
            val diagnostics = DiagnosticCollector()
            assertNull(Compiler.verified(bad, diagnostics), "$bad")
            val error = diagnostics.diagnostics.single()
            assertEquals(DiagnosticCode.COMP_VERIFICATION_FAILED, error.code)
            assertEquals(DiagnosticPhase.COMPILER, error.phase)
            assertTrue(diagnostics.hasErrors)
        }
    }

    // ---- if 文（第3.7c段階） ----

    @Test
    fun ifLayout() {
        // if: c; JUMP_IF_FALSE E; ブロック; E:（最後のブロックの後に JUMP は出さない）
        assertEquals(
            listOf("PUSH_BOOL:1", "JUMP_IF_FALSE:5", "PUSH_CONST", "CALL_HOST", "POP", "HALT"),
            shape("if (true) {\n    println(\"OK\")\n}"),
        )
        // 空のブロックは次の命令へ移るだけ
        assertEquals(listOf("PUSH_BOOL:0", "JUMP_IF_FALSE:2", "HALT"), shape("if (false) { }"))
        // if / else: c; JUMP_IF_FALSE N; ブロック1; JUMP E; N: else ブロック; E:
        assertEquals(
            listOf("PUSH_BOOL:1", "JUMP_IF_FALSE:6", "PUSH_CONST", "CALL_HOST", "POP", "JUMP:9", "PUSH_CONST", "CALL_HOST", "POP", "HALT"),
            shape("if (true) { println(1) } else { println(2) }"),
        )
        // else if: 各ブロックの後は出口へ、条件が false なら次の条件へ
        assertEquals(
            listOf(
                "PUSH_BOOL:0", "JUMP_IF_FALSE:6", "PUSH_CONST", "CALL_HOST", "POP", "JUMP:15",
                "PUSH_BOOL:1", "JUMP_IF_FALSE:12", "PUSH_CONST", "CALL_HOST", "POP", "JUMP:15",
                "PUSH_CONST", "CALL_HOST", "POP",
                "HALT",
            ),
            shape("if (false) { println(1) } else if (true) { println(2) } else { println(3) }"),
        )
    }

    @Test
    fun blockVariablesGetTheirOwnSlots() {
        // ブロックの変数・シャドーイングした変数は宣言ごとに別の番号（ブロックを出ても再利用しない）
        val program = compile("val x = 1\nif (true) { val x = 2\nval y = 3 } else { val y = 4 }\nval z = x")
        assertEquals(5, program.variableCount)
        val stores = program.instructions.filter { it.opcode == Opcode.STORE }.map { it.operand }
        assertEquals(listOf(0, 1, 2, 3, 4), stores)
        // 最後の val z = x は外側の x（番号 0）を読む
        assertEquals(0, program.instructions.last { it.opcode == Opcode.LOAD }.operand)
        // 上限 64 はブロックを含めた宣言の総数
        val many = (0 until 32).joinToString("\n") { "if (true) { val a = $it\nval b = $it }" }
        compile(many)
        val tooMany = Compiler.compileSource(many + "\nval c = 1")
        assertEquals(listOf(DiagnosticCode.COMP_TOO_MANY_VARIABLES), tooMany.diagnostics.map { it.code })
    }

    @Test
    fun nestedIfsAreVerified() {
        // 30 段の if の入れ子（各段に else とブロックの変数。最も内側の n = v29 + 1 で深さの合計 32）も検証に通る
        val source = "var n = 0\n" + (0 until 30).joinToString("") { "if (n == $it) { val v$it = $it\nn = v$it + 1\n" } +
            "println(n)" + (0 until 30).joinToString("") { "\n} else { n = 0 }" }
        val program = compile(source)
        assertNotNull(SnapshotVerifier.framesOf(program))
        assertEquals(1 + 30, program.variableCount)
    }

    // ---- while 文（第3.7d段階） ----

    @Test
    fun whileLayout() {
        // 先頭: 条件; JUMP_IF_FALSE 出口; 本体; JUMP 先頭; 出口:
        assertEquals(listOf("PUSH_BOOL:1", "JUMP_IF_FALSE:3", "JUMP:0", "HALT"), shape("while (true) { }"))
        assertEquals(listOf("PUSH_BOOL:0", "JUMP_IF_FALSE:3", "JUMP:0", "HALT"), shape("while (false) { }"))
        assertEquals(
            listOf("PUSH_CONST", "STORE", "LOAD", "PUSH_CONST", "LESS", "JUMP_IF_FALSE:11", "LOAD", "PUSH_CONST", "ADD", "STORE", "JUMP:2", "HALT"),
            shape("var i = 0\nwhile (i < 3) { i = i + 1 }"),
        )
        // 入れ子: 内側の JUMP は内側の条件の先頭へ、外側の JUMP は外側の条件の先頭へ
        assertEquals(
            listOf("PUSH_BOOL:1", "JUMP_IF_FALSE:6", "PUSH_BOOL:0", "JUMP_IF_FALSE:5", "JUMP:2", "JUMP:0", "HALT"),
            shape("while (true) { while (false) { } }"),
        )
        // 文の境界の深さは 0 のまま
        assertEquals(2, compile("var i = 0\nwhile (i < 3) { i = i + 1 }").maxStackDepth)
    }

    @Test
    fun loopBodyVariablesAreVerified() {
        // 本体の変数は宣言ごとに1つの番号。ループの先頭では MaybeInitialized になるが、宣言より前には読まない
        val program = compile("var i = 0\nwhile (i < 2) { val sq = i * i\nprintln(sq)\nif (sq > 0) { val t = sq\nprintln(t) }\ni = i + 1 }")
        assertEquals(3, program.variableCount)
        assertNotNull(SnapshotVerifier.framesOf(program))
        // ブロック 31 段の while の入れ子も検証に通る
        val nested = "var n = 0\n" + "while (n < 1) {\n".repeat(31) + "n = 1" + "\n}".repeat(31)
        assertNotNull(SnapshotVerifier.framesOf(compile(nested)))
    }

    // ---- break / continue（第3.8a段階） ----

    @Test
    fun breakAndContinueLayout() {
        // break は出口へ、continue は条件の先頭へ（本体の先頭ではない）
        assertEquals(listOf("PUSH_BOOL:1", "JUMP_IF_FALSE:4", "JUMP:4", "JUMP:0", "HALT"), shape("while (true) { break }"))
        assertEquals(listOf("PUSH_BOOL:1", "JUMP_IF_FALSE:4", "JUMP:0", "JUMP:0", "HALT"), shape("while (true) { continue }"))
        // continue は条件（LOAD i; PUSH_CONST 3; LESS）の先頭 2 へ戻る
        assertEquals(
            listOf("PUSH_CONST", "STORE", "LOAD", "PUSH_CONST", "LESS", "JUMP_IF_FALSE:8", "JUMP:2", "JUMP:2", "HALT"),
            shape("var i = 0\nwhile (i < 3) { continue }"),
        )
        // 入れ子: 内側の break / continue は内側のループだけ、外側のものは外側のループの出口・先頭へ
        assertEquals(
            listOf("PUSH_BOOL:1", "JUMP_IF_FALSE:8", "PUSH_BOOL:0", "JUMP_IF_FALSE:6", "JUMP:6", "JUMP:2", "JUMP:8", "JUMP:0", "HALT"),
            shape("while (true) { while (false) { break }\nbreak }"),
        )
        assertEquals(
            listOf("PUSH_BOOL:1", "JUMP_IF_FALSE:8", "PUSH_BOOL:0", "JUMP_IF_FALSE:6", "JUMP:2", "JUMP:2", "JUMP:0", "JUMP:0", "HALT"),
            shape("while (true) { while (false) { continue }\ncontinue }"),
        )
        // if の中の break の後も、if の出口・ループの先頭へ通常どおり移る
        assertEquals(
            listOf("PUSH_BOOL:1", "JUMP_IF_FALSE:8", "PUSH_BOOL:0", "JUMP_IF_FALSE:6", "JUMP:8", "JUMP:7", "JUMP:0", "JUMP:0", "HALT"),
            shape("while (true) { if (false) { break } else { continue } }"),
        )
    }

    @Test
    fun breakAndContinueAreVerified() {
        // break の後の到達しない文、ブロックの途中で抜ける経路、本体の変数（出口で MaybeInitialized）も検証に通る
        val sources = listOf(
            "var i = 0\nwhile (true) { val x = i\nbreak\ni = x + 1 }\nprintln(i)",
            "var i = 0\nwhile (i < 5) { i = i + 1\nval sq = i * i\nif (sq > 4) { val big = sq\nif (big > 9) { break }\ncontinue }\nprintln(sq) }",
            "var a = 0\nwhile (a < 3) { a = a + 1\nvar b = 0\nwhile (true) { b = b + 1\nif (b > a) { break }\nif (b == 2) { continue }\nprintln(b) }\nif (a == 2) { continue }\nprintln(a) }",
        )
        for (source in sources) {
            val program = compile(source)
            assertNotNull(SnapshotVerifier.framesOf(program), source)
            // 文の境界の深さは 0 のまま（break / continue の時点でスタックは空）
            assertTrue(program.maxStackDepth <= 2, source)
        }
    }

    // ---- 整数範囲の for（第3.8b段階） ----

    @Test
    fun forLayout() {
        // until: i = 始点; 終端 = 終点; 先頭: i < 終端 でなければ出口; 本体; i = i + 1; JUMP 先頭
        assertEquals(
            listOf("PUSH_CONST", "STORE", "PUSH_CONST", "STORE", "LOAD", "LOAD", "LESS", "JUMP_IF_FALSE:13", "LOAD", "PUSH_CONST", "ADD", "STORE", "JUMP:4", "HALT"),
            shape("for (i in 0 until 5) { }"),
        )
        // .. / downTo: 最初に1回だけ空でないかを確かめ、本体の後で i == 終端 なら加算・減算の前に抜ける
        assertEquals(
            listOf(
                "PUSH_CONST", "STORE", "PUSH_CONST", "STORE", "LOAD", "LOAD", "LESS_EQUAL", "JUMP_IF_FALSE:17",
                "LOAD", "LOAD", "NOT_EQUAL", "JUMP_IF_FALSE:17", "LOAD", "PUSH_CONST", "ADD", "STORE", "JUMP:8", "HALT",
            ),
            shape("for (i in 0..5) { }"),
        )
        assertEquals(listOf("GREATER_EQUAL", "SUBTRACT"), shape("for (i in 5 downTo 0) { }").filter { it == "GREATER_EQUAL" || it == "SUBTRACT" })
        // continue は更新へ、break は出口へ
        val withJumps = shape("for (i in 0 until 5) { continue\nbreak }")
        assertEquals(listOf("JUMP:10", "JUMP:15"), withJumps.subList(8, 10))
        // 変数の番号: ループ変数、終端（名前なし）の順。定数 1 は for で初めて定数プールに入る
        val program = compile("val a = 7\nfor (i in a + 1..a * 2) { val sq = i * i }")
        assertEquals(4, program.variableCount)
        assertEquals(listOf(0, 1, 2, 3), program.instructions.filter { it.opcode == Opcode.STORE }.map { it.operand }.distinct())
        assertTrue(Constant.IntConstant(1) in program.constants)
        // 終点の式（a * 2）はループの開始時に1回だけ: MULTIPLY は本体の前に1つだけある
        val multiply = program.instructions.indexOfFirst { it.opcode == Opcode.MULTIPLY }
        val firstJump = program.instructions.indexOfFirst { it.opcode == Opcode.JUMP_IF_FALSE }
        assertTrue(multiply < firstJump)
        assertEquals(2, program.instructions.count { it.opcode == Opcode.MULTIPLY })
    }

    @Test
    fun forVariablesAndVerification() {
        // 上限 64 はループ変数と終端の番号を含めた総数（for 1 つで 2 つ使う）
        val many = (0 until 32).joinToString("\n") { "for (i in 0..0) { }" }
        assertEquals(64, compile(many).variableCount)
        val tooMany = Compiler.compileSource(many + "\nval x = 1")
        assertEquals(listOf(DiagnosticCode.COMP_TOO_MANY_VARIABLES), tooMany.diagnostics.map { it.code })
        val sources = listOf(
            "for (i in 2147483645..2147483647) { if (i == 2147483646) { continue }\nprintln(i) }",
            "var n = 0\nfor (i in 0..3) { var j = i\nwhile (j > 0) { j = j - 1\nfor (k in j downTo 0) { if (k == 1) { break }\nn = n + k } } }\nprintln(n)",
            "for (i in 0 until 3) { val v = i\nif (v == 1) { val w = v\ncontinue }\nprintln(v) }",
        )
        for (source in sources) assertNotNull(SnapshotVerifier.framesOf(compile(source)), source)
        // 30 段の for の入れ子
        val nested = (0 until 30).joinToString("") { "for (i$it in 0..1) {\n" } + "println(1)" + "\n}".repeat(30)
        assertNotNull(SnapshotVerifier.framesOf(compile(nested)))
    }

    @Test
    fun constantPoolIsTypedDeduplicatedAndOrdered() {
        val program = compile("val a = 5\nval b = 5\nval s = \"x\"\nval t = \"x\"\nval u = \"5\"\nprintln(7)")
        assertEquals(
            listOf(Constant.IntConstant(5), Constant.StringConstant("x"), Constant.StringConstant("5"), Constant.IntConstant(7)),
            program.constants,
        )
        // 同じソースからは常に同じ結果になる
        assertEquals(program, compile("val a = 5\nval b = 5\nval s = \"x\"\nval t = \"x\"\nval u = \"5\"\nprintln(7)"))
    }

    @Test
    fun stackDepth() {
        // 1・2・3・4 を順に積んでから加算するため、最大 4
        assertEquals(4, compile("val a = 1 + (2 + (3 + 4))").maxStackDepth)
    }

    @Test
    fun constantLimit() {
        compile((0 until 256).joinToString("\n") { "println($it)" })
        val result = Compiler.compileSource((0 until 257).joinToString("\n") { "println($it)" })
        assertEquals(listOf(DiagnosticCode.COMP_TOO_MANY_CONSTANTS), result.diagnostics.map { it.code })
        assertEquals(listOf(DiagnosticArgument.IntArgument(256)), result.diagnostics.single().arguments)
        assertNull(result.program)
    }

    @Test
    fun variableLimit() {
        compile((0 until 64).joinToString("\n") { "val a$it = 1" })
        val result = Compiler.compileSource((0 until 65).joinToString("\n") { "val a$it = 1" })
        assertEquals(listOf(DiagnosticCode.COMP_TOO_MANY_VARIABLES), result.diagnostics.map { it.code })
        assertNull(result.program)
    }

    @Test
    fun earlierErrorsSkipCompilation() {
        val result = Compiler.compileSource("val a = b")
        assertEquals(listOf(DiagnosticCode.SEM_UNDECLARED_VARIABLE), result.diagnostics.map { it.code })
        assertNull(result.program)
        // 警告だけならコンパイルできる
        val warned = Compiler.compileSource("val a = 1\na")
        assertEquals(listOf(DiagnosticCode.SEM_UNUSED_EXPRESSION), warned.diagnostics.map { it.code })
        assertNotNull(warned.program)
    }

    // ---- ユーザー定義関数（第3.8c段階） ----

    @Test
    fun functionLayout() {
        // トップレベルの文（HALT まで）の後に、関数を定義の順に並べる。関数の末尾は RETURN（明示した return の後にも出す）
        val program = compile("fun b() { println(2) }\nfun a() {\n  b()\n  return\n}\na()\nprintln(1)")
        assertEquals(
            listOf("CALL", "POP", "PUSH_CONST", "CALL_HOST", "POP", "HALT", "PUSH_CONST", "CALL_HOST", "POP", "RETURN", "CALL", "POP", "RETURN", "RETURN"),
            program.instructions.map { it.opcode.name },
        )
        assertEquals(listOf(FunctionInfo(6, 0), FunctionInfo(10, 0)), program.functions)
        // CALL の operand は関数の番号（定義の順）
        assertEquals(listOf(1, 0), program.instructions.filter { it.opcode == Opcode.CALL }.map { it.operand })
        // 関数の無いプログラムは関数表が空で、命令は第3.8b段階と同じ
        assertEquals(emptyList<FunctionInfo>(), compile("println(1)").functions)
    }

    @Test
    fun functionVariables() {
        // 関数の変数（ループ変数・終端を含む）は呼び出しフレームのローカル変数で、番号は関数ごとに 0 から。トップレベルの変数は大域変数
        val program = compile("var g = 0\nfun f() {\n  val a = g + 1\n  for (i in 0..a) { g = i }\n}\nfun h() {\n  var b = 2\n  b = b + g\n}\nf()\nh()")
        assertEquals(1, program.variableCount)
        assertEquals(listOf(3, 1), program.functions.map { it.localCount })
        fun operands(from: Int, to: Int, vararg opcodes: Opcode) =
            program.instructions.subList(from, to).filter { it.opcode in opcodes }.map { it.operand }.toSortedSet().toList()
        val (f, h) = program.functions
        assertEquals(listOf(0), operands(f.entry, h.entry, Opcode.LOAD, Opcode.STORE))
        assertEquals(listOf(0, 1, 2), operands(f.entry, h.entry, Opcode.LOAD_LOCAL, Opcode.STORE_LOCAL))
        assertEquals(listOf(0), operands(h.entry, program.instructions.size, Opcode.LOAD_LOCAL, Opcode.STORE_LOCAL))
        assertEquals(emptyList<Int>(), operands(0, f.entry, Opcode.LOAD_LOCAL, Opcode.STORE_LOCAL))
        // 変数の上限 64 は、トップレベルと関数ごとに別々に数える
        val full = "fun f() {\n" + (0 until 64).joinToString("") { "val v$it = $it\n" } + "}\n" + (0 until 64).joinToString("") { "val w$it = $it\n" } + "f()"
        assertNotNull(Compiler.compileSource(full).program)
        val over = "fun f() {\n" + (0..64).joinToString("") { "val v$it = $it\n" } + "}"
        assertEquals(listOf(DiagnosticCode.COMP_TOO_MANY_VARIABLES), Compiler.compileSource(over).diagnostics.map { it.code })
    }

    // ---- 引数・戻り値（第3.8d段階） ----

    @Test
    fun argumentAndReturnLayout() {
        // 引数は左から順に積み、CALL がローカル変数 0, 1 へ移す。値を返す RETURN は operand 1。値を返す関数の末尾には RETURN を出さない
        val add = compile("fun add(a: Int, b: Int): Int {\n  return a + b\n}\nprintln(add(1, 2))")
        assertEquals(
            listOf("PUSH_CONST", "PUSH_CONST", "CALL", "CALL_HOST", "POP", "HALT", "LOAD_LOCAL", "LOAD_LOCAL", "ADD", "RETURN"),
            add.instructions.map { it.opcode.name },
        )
        assertEquals(listOf(0, 1), add.instructions.filter { it.opcode == Opcode.LOAD_LOCAL }.map { it.operand })
        assertEquals(1, add.instructions.last().operand)
        assertEquals(listOf(FunctionInfo(6, 2, 2)), add.functions)
        // 引数のある Unit の関数は末尾に RETURN 0
        val show = compile("fun show(s: String) {\n  val t = s\n  println(t)\n}\nshow(\"x\")")
        assertEquals(listOf(FunctionInfo(4, 2, 1)), show.functions)
        assertEquals(Opcode.RETURN to 0, show.instructions.last().let { it.opcode to it.operand })
        // 使わない戻り値は POP で捨てる（スタックに残らない）
        assertEquals(listOf("CALL", "POP", "HALT", "PUSH_CONST", "RETURN"), shape("fun one(): Int { return 1 }\none()"))
    }

    @Test
    fun parameterAndStackLimits() {
        fun function(parameters: Int) = "fun f(" + (0 until parameters).joinToString(", ") { "p$it: Int" } + ") { }\n"
        assertNotNull(Compiler.compileSource(function(VmLimits.MAX_PARAMETERS)).program)
        val tooMany = Compiler.compileSource(function(VmLimits.MAX_PARAMETERS + 1))
        assertEquals(listOf(DiagnosticCode.COMP_TOO_MANY_PARAMETERS), tooMany.diagnostics.map { it.code })
        assertEquals(SourcePosition(156, 1, 157), tooMany.diagnostics.single().position)
        // 呼び出し先のスタックは呼び出し元の計算途中の値（ここでは 25 個）の上に積み重なる。連鎖の合計が 64 を超えたら、トップレベルの呼び出しを報告する
        // （2 段: 25 + 26 = 51、3 段: 25 + 25 + 26 = 76）
        fun chain(levels: Int) = (1..levels).joinToString("") {
            "fun f$it(): Int {\n  return " + "1 + (".repeat(25) + (if (it < levels) "f${it + 1}()" else "1") + ")".repeat(25) + "\n}\n"
        } + "println(f1())"
        assertNotNull(Compiler.compileSource(chain(2)).program)
        val deep = Compiler.compileSource(chain(3))
        assertEquals(listOf(DiagnosticCode.COMP_STACK_TOO_DEEP), deep.diagnostics.map { it.code })
        // 位置は最後の行（println(f1())）の f1
        assertEquals(SourcePosition(chain(3).lastIndexOf("f1()"), 10, 9), deep.diagnostics.single().position)
    }
}
