package com.creatrbypc.createanalytical.script.module

import com.creatrbypc.createanalytical.script.api.CreateScript
import com.creatrbypc.createanalytical.script.compiler.CompileOptions
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticArgument
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticCode
import com.creatrbypc.createanalytical.script.vm.ProgramFingerprint
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

// S8.2 Phase 4: import できる範囲（ModuleAccess）。Core は、import の宣言ごとに、resolver を呼ぶ前に確かめる
class ModuleAccessTest {
    private val sources = mapOf(
        "alpha" to "import gamma\nfun a(): Int {\n    return gamma.g()\n}\n",
        "beta" to "import gamma\nfun b(): Int {\n    return gamma.g() + 1\n}\n",
        "gamma" to "fun g(): Int {\n    return 1\n}\n",
        "delta" to "fun d(): Int {\n    return 4\n}\n",
    )
    private val asked = ArrayList<String>()
    private val resolver = ModuleResolver { name ->
        asked.add(name)
        sources[name]?.let { ModuleLookup.Found(it) } ?: ModuleLookup.NotFound
    }
    private val main = "import alpha\nimport beta\nprintln(alpha.a() + beta.b())\n"

    private fun compile(source: String = main, access: ModuleAccess): ModuleCompileResult =
        CreateScript.compile(source, resolver, "main", CompileOptions.STANDALONE, access)

    @Test
    fun passesTheImporterOfEachDeclaration() {
        val seen = ArrayList<Pair<String?, String>>()
        val result = compile(access = { importer, module -> seen.add(importer to module); true })
        assertNotNull(result.program)
        // エントリーは null。gamma は alpha と beta の両方から import しているので、宣言ごとに 2 回確かめる（resolver は 1 回だけ）
        assertEquals(listOf(null to "alpha", "alpha" to "gamma", null to "beta", "beta" to "gamma"), seen)
        assertEquals(listOf("alpha", "gamma", "beta"), asked)
    }

    @Test
    fun refusesImportsThatAreNotVisible() {
        // beta から gamma だけを拒否する（alpha からは許す: 解決済みのモジュールでも、宣言ごとに確かめる）
        val result = compile(access = { importer, module -> !(importer == "beta" && module == "gamma") })
        assertNull(result.program)
        val diagnostic = result.diagnostics.single()
        assertEquals(DiagnosticCode.MODULE_NOT_VISIBLE, diagnostic.diagnostic.code)
        assertEquals("CS_MODULE_NOT_VISIBLE", diagnostic.diagnostic.code.id)
        assertEquals("beta", diagnostic.module)
        assertEquals(listOf(DiagnosticArgument.StringArgument("gamma")), diagnostic.diagnostic.arguments)
        assertEquals(1 to 8, diagnostic.diagnostic.position.line to diagnostic.diagnostic.position.column)
        // 拒否した名前の resolver は呼ばない（エントリーから直接 delta を拒否する）
        asked.clear()
        val direct = compile("import delta\nprintln(delta.d())\n", access = { _, module -> module != "delta" })
        assertEquals(listOf(DiagnosticCode.MODULE_NOT_VISIBLE), direct.diagnostics.map { it.diagnostic.code })
        assertEquals(null, direct.diagnostics.single().module)
        assertEquals(emptyList<String>(), asked)
    }

    // 組み込み関数の名前の import は、範囲を確かめる前に CS_MODULE_BUILTIN_NAME（既存の診断の順序のまま）
    @Test
    fun builtinNamesComeFirst() {
        val result = compile("import println\n", access = { _, _ -> false })
        assertEquals(listOf(DiagnosticCode.MODULE_BUILTIN_NAME), result.diagnostics.map { it.diagnostic.code })
    }

    // ModuleAccess.ALL は、ModuleAccess を渡さない compile と同じ命令・診断・ハッシュになる（既存の呼び出しの意味は変わらない）
    @Test
    fun allowingEverythingIsTheSameAsTheExistingCompile() {
        for (source in listOf(main, "import missing\nprintln(1)\n", "import alpha\nimport alpha\nprintln(alpha.a())\n", "println(1)\n")) {
            val existing = CreateScript.compile(source, resolver, "main", CompileOptions.STANDALONE)
            val withAccess = compile(source, ModuleAccess.ALL)
            assertEquals(existing.diagnostics, withAccess.diagnostics, source)
            assertEquals(existing.modules, withAccess.modules, source)
            assertArrayEquals(existing.sourceFingerprint, withAccess.sourceFingerprint, source)
            if (existing.program != null) assertArrayEquals(ProgramFingerprint.ofProgram(existing.program!!), ProgramFingerprint.ofProgram(withAccess.program!!), source)
            assertEquals(existing.program == null, withAccess.program == null, source)
        }
    }
}
