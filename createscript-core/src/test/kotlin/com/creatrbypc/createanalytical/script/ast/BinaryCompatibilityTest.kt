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
