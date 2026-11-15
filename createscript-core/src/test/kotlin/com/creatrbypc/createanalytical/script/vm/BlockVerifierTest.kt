package com.creatrbypc.createanalytical.script.vm

import com.creatrbypc.createanalytical.script.api.CreateScript
import com.creatrbypc.createanalytical.script.compiler.CompileOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.random.Random

// 第8.0.1段階: 基本ブロック単位の検証（SnapshotVerifier.analyzeBlocks）が、これまでの命令位置ごとの検証（framesOf）と同じ結果になること。
// 正しいプログラム（Compiler の出力）と、それを 1 命令ずつ書き換えた（多くは正しくない）プログラムで比べる
class BlockVerifierTest {
    private val sources = listOf(
        "println(1)",
        "var x = 0\nwhile (x < 3) {\n    x = x + 1\n    if (x == 2) {\n        continue\n    }\n    println(x)\n}\n",
        "for (i in 1..3) {\n    for (j in 3 downTo 1) {\n        if (i == j) {\n            break\n        }\n        println(i * j)\n    }\n}\n",
        "fun fib(n: Int): Int {\n    if (n < 2) {\n        return n\n    }\n    return fib(n - 1) + fib(n - 2)\n}\nprintln(fib(10))\n",
        "var g = 0\nfun set() {\n    g = 5\n}\nset()\nprintln(g)\nval late = 1\nfun read(): Int {\n    return late\n}\nprintln(read())\n",
        "class Counter {\n    var value = 0\n    fun add(step: Int): Int {\n        value = value + step\n        return value\n    }\n}\nval c = Counter()\nfor (i in 1..3) {\n    c.add(i)\n}\nprintln(c.value)\n",
        "class Node {\n    var next: Node = Leaf().node()\n}\nclass Leaf {\n    fun node(): Node {\n        return Node()\n    }\n}\nprintln(1)\n",
        "val l = mutableListOf(listOf(1, 2), listOf(3))\nl.add(listOf(4))\nprintln(l[1][0] + l.size)\nval m = matrix(vector(1.0, 2.0), vector(3.0, 4.0))\nprintln(matmul(m, transpose(m)))\n",
        "randomSeed(3)\nval a = random(1.0, 2.0)\nprintln(isClose(a, a))\nprintln(sqrt(2.0).roundToInt())\nprintln(1.5f.toDouble())\n",
        "var a = 0\nvar b = true\nwhile (b && a < 10 || a == 3) {\n    a = a + 1\n    waitTicks(1)\n    if (a > 4) {\n        b = false\n    } else if (a == 2) {\n        val inner = a\n        println(inner)\n    }\n}\n",
        "fun f(n: Int): Int {\n    var r = 0\n    for (i in 0 until n) {\n        if (i % 2 == 0) {\n            r = r + g(i)\n        }\n    }\n    return r\n}\nfun g(x: Int): Int {\n    if (x > 3) {\n        return f(x - 3)\n    }\n    return x\n}\nprintln(f(9))\n",
    )

    private fun samples(): List<String> {
        val directory = Paths.get(System.getProperty("user.dir")).resolveSibling("createscript-cli").resolve("samples")
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.list(directory).use { files -> files.filter { it.toString().endsWith(".cscr") }.sorted().map { Files.readString(it) }.toList() }
    }

    private fun programs(): List<CompiledProgram> = (sources + samples()).mapNotNull { CreateScript.compile(it).program }

    // framesOf と analyzeBlocks の結果（検証できるか、到達する位置のスタックの最大の深さ）
    private fun reference(program: CompiledProgram): Int? = SnapshotVerifier.framesOf(program)?.maxOfOrNull { it?.stack?.size ?: 0 }

    private fun blocks(program: CompiledProgram): Int? = (SnapshotVerifier.analyzeBlocks(program, SnapshotVerifier.maxSteps(program)) as? SnapshotVerifier.BlockAnalysis.Verified)?.maxStackDepth

    @Test
    fun agreesOnCompiledPrograms() {
        val programs = programs()
        assertTrue(programs.size >= 14, "${programs.size}")
        for (program in programs) {
            val expected = reference(program)
            assertTrue(expected != null)
            assertEquals(expected, blocks(program))
        }
        for (program in listOf(BranchPrograms.COUNTDOWN)) assertEquals(reference(program), blocks(program))
    }

    // 1 命令の opcode か operand を書き換えたプログラム。検証できるかどうかと、できたときのスタックの最大の深さが一致する
    @Test
    fun agreesOnMutatedPrograms() {
        val random = Random(801)
        val opcodes = Opcode.entries
        var rejected = 0
        var compared = 0
        for (program in programs()) {
            repeat(300) {
                val index = random.nextInt(program.instructions.size)
                val original = program.instructions[index]
                val mutated = if (random.nextBoolean()) {
                    original.copy(opcode = opcodes[random.nextInt(opcodes.size)])
                } else {
                    original.copy(operand = original.operand + random.nextInt(-3, 4))
                }
                val changed = program.copy(instructions = program.instructions.toMutableList().also { it[index] = mutated })
                val expected = reference(changed)
                if (expected == null) rejected++
                assertEquals(expected, blocks(changed), "$index: $original -> $mutated")
                compared++
            }
        }
        assertTrue(compared > 3000 && rejected > compared / 4, "$compared compared, $rejected rejected")
    }

    // 処理回数は理論上の上限（maxSteps）以内で、上限を小さくすると打ち切る
    @Test
    fun respectsStepLimits() {
        for (program in programs()) {
            assertTrue(SnapshotVerifier.analyzeBlocks(program, SnapshotVerifier.maxSteps(program)) is SnapshotVerifier.BlockAnalysis.Verified)
            assertEquals(SnapshotVerifier.BlockAnalysis.StepLimitReached, SnapshotVerifier.analyzeBlocks(program, 1))
        }
    }

    // 合流点の多い不利な形（命令位置ごとの検証では、30 万命令で約 15 秒・400 MiB かかったもの）を、処理回数の上限の中で短い時間に検証する。
    // 200 万命令では処理回数（約 1.3 億回）が独立版の上限（1 億回）を超え、打ち切る
    @Test
    fun handlesAdversarialBranches() {
        for (size in listOf(300_000, 2_000_000)) {
            val program = adversarial(size)
            val start = System.nanoTime()
            val result = SnapshotVerifier.analyzeBlocks(program, minOf(CompileOptions.STANDALONE.maxVerificationSteps!!, SnapshotVerifier.maxSteps(program)))
            val millis = (System.nanoTime() - start) / 1_000_000
            println("[8.0.1 verifier] adversarial ${program.instructions.size} instructions: ${result.javaClass.simpleName} in $millis ms (theoretical steps ${SnapshotVerifier.maxSteps(program)})")
            assertTrue(if (size == 300_000) result is SnapshotVerifier.BlockAnalysis.Verified else result == SnapshotVerifier.BlockAnalysis.StepLimitReached)
            assertTrue(millis < 30_000)
        }
    }

    private fun adversarial(size: Int): CompiledProgram {
        fun op(opcode: Opcode, operand: Int = 0) = BranchPrograms.op(opcode, operand)
        val segment = 4
        val filler = (size - VmLimits.MAX_VARIABLES * segment - 2) / 2 * 2
        val instructions = ArrayList<Instruction>(size)
        repeat(filler / 2) { instructions += op(Opcode.PUSH_BOOL, 1); instructions += op(Opcode.POP) }
        for (variable in 0 until VmLimits.MAX_VARIABLES) {
            val target = if (variable > 0) filler + (variable - 1) * segment else 0
            instructions += listOf(op(Opcode.PUSH_CONST, 0), op(Opcode.STORE, variable), op(Opcode.PUSH_BOOL, 1), op(Opcode.JUMP_IF_FALSE, target))
        }
        instructions += op(Opcode.HALT)
        return CompiledProgram(instructions, listOf(Constant.IntConstant(1)), VmLimits.MAX_VARIABLES, 1)
    }
}
