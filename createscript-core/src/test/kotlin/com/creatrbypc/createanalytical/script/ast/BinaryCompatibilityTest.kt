package com.creatrbypc.createanalytical.script.ast

import com.creatrbypc.createanalytical.script.lexer.Lexer
import com.creatrbypc.createanalytical.script.parser.Parser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// S8.1: v0.1.0-alpha の Core に対してコンパイルしたコード（テストのリソースの compat/v010alpha/OldCaller.class）を、新しい Core で実行する
class BinaryCompatibilityTest {
    @Test
    fun codeCompiledAgainstV010AlphaStillRuns() {
        val caller = Class.forName("compat.v010alpha.OldCaller")
        @Suppress("UNCHECKED_CAST")
        val results = caller.getMethod("run").invoke(null) as List<String>
        val expected = listOf(
            "constructor with defaults", "constructor with three lists", "component1-3", "copy with defaults", "copy with three lists",
            "equals and hashCode", "copy keeps the other fields", "compile",
        )
        assertEquals(expected, results)
    }

    // S8.2 Phase 4: ModuleAccess を足す前の Core（9f938b4）に対してコンパイルした ModuleResolver の実装と呼び出し（compat/s82phase3）が、そのまま動く
    @Test
    fun moduleCodeCompiledBeforeModuleAccessStillRuns() {
        val user = Class.forName("compat.s82phase3.OldModuleUser")
        @Suppress("UNCHECKED_CAST")
        val results = user.getMethod("run").invoke(null) as List<String>
        assertEquals(listOf("compile with defaults", "compile with options", "lambda resolver", "source fingerprint", "lookup results", "locate", "run and resume"), results)
    }

    // A2（Q2: VmSnapshot 一式は Stable）: v0.1.0-alpha の Core に対してコンパイルした、保存・復元するホスト（compat/v010alpha/OldSnapshotHost）が、
    // 新しい Core で何 tick かごとに保存し、VmSnapshot・CallFrameSnapshot・ObjectSnapshot・ListSnapshot・Value・SlotType を v0.1.0-alpha のコンストラクターで
    // 作り直して再開しても、中断なしの実行と同じ出力になる（オブジェクト・入れ子のリスト・Float・Double・Vector・乱数・再帰と待機を含む）。
    // 1 tick に 1 命令で、間隔を変えて保存する位置（待機中・関数の中・複数 tick にまたがる命令の途中）を変える。毎 tick だと、tick をまたぐ命令
    // （vector(...) など。ここでは最大 3 tick）が復元のたびに最初からやり直しになって進まないため、間隔はそれより長くする
    @Test
    fun snapshotHostCompiledAgainstV010AlphaStillSavesAndRestores() {
        val source = "class Box {\n  var items = mutableListOf(1, 2)\n  var weight = 1.5\n}\n" +
            "fun fill(b: Box, n: Int): Int {\n  if (n == 0) { return b.items.size }\n  b.items.add(n)\n  waitTicks(1)\n  return fill(b, n - 1)\n}\n" +
            "val box = Box()\nval grid = listOf(listOf(1, 2), listOf(3))\nvar f = 2.5f\nval v = vector(3.0, 4.0)\nval r = randomInt(0, 100)\n" +
            "println(fill(box, 3))\nwaitTicks(2)\nf = f * 2.0f\nprintln(box.items)\nprintln(grid)\nprintln(f)\nprintln(length(v))\n" +
            "println(box.weight + 1.0)\nprintln(r >= 0)\n"
        val host = Class.forName("compat.v010alpha.OldSnapshotHost")
        for (every in listOf(4, 5, 7, 11)) {
            @Suppress("UNCHECKED_CAST")
            val results = host.getMethod("run", String::class.java, Int::class.javaPrimitiveType).invoke(null, source, every) as List<String>
            assertEquals(listOf("compile", "run without saving", "save and resume", "same output", "same console"), results, "every $every ticks")
        }
    }

    // v0.1.0-alpha の Program の JVM シグネチャ（javap で比べたもの）が残っている
    @Test
    fun keepsTheV010AlphaSignatures() {
        val list = java.util.List::class.java
        val program = Program::class.java
        val marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
        program.getConstructor(list, list, list)
        program.getConstructor(list, list, list, Int::class.javaPrimitiveType, marker)
        program.getMethod("copy", list, list, list)
        program.getMethod("copy\$default", program, list, list, list, Int::class.javaPrimitiveType, Any::class.java)
        (1..3).forEach { program.getMethod("component$it") }
        // 現在の 4 つの項目のものもある
        program.getConstructor(list, list, list, list)
        program.getMethod("copy", list, list, list, list)
    }

    // データクラスの equals・hashCode・toString・copy は 4 つの項目のまま（imports も比べる・写す）
    @Test
    fun dataClassBehaviourIsUnchanged() {
        val parsed = Parser.parse(Lexer.tokenize("import geometry\nprintln(1)\n")).program!!
        assertEquals(1, parsed.imports.size)
        val copied = parsed.copy(statements = emptyList())
        assertEquals(parsed.imports, copied.imports)
        val withoutImports = parsed.copy(imports = emptyList())
        assertNotEquals(parsed, withoutImports)
        assertTrue(parsed.toString().contains("imports=[ImportDeclaration(name=geometry"))
        assertEquals(Program(parsed.statements, parsed.functions, parsed.classes, parsed.imports), parsed)
        assertEquals(Program(parsed.statements, parsed.functions, parsed.classes, parsed.imports).hashCode(), parsed.hashCode())
        // 項目が 3 つだけの呼び出しは、ソースでは 4 つの項目のコンストラクター（imports は既定の空）になる
        assertEquals(emptyList<ImportDeclaration>(), Program(emptyList(), emptyList(), emptyList()).imports)
    }
}
