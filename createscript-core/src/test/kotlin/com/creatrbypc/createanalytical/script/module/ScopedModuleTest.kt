package com.creatrbypc.createanalytical.script.module

import com.creatrbypc.createanalytical.script.api.CreateScript
import com.creatrbypc.createanalytical.script.api.RunOptions
import com.creatrbypc.createanalytical.script.compiler.CompileOptions
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticArgument
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticCode
import com.creatrbypc.createanalytical.script.vm.ProgramFingerprint
import com.creatrbypc.createanalytical.script.vm.VmState
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// S8.2 の H-1: import 元ごとにモジュールを探すコンパイル（CreateScript.compileScoped・ScopedModuleResolver）
class ScopedModuleTest {
    // 2 つのパッケージ（p・q）が、どちらも公開しないモジュール util を持つ。ID は "パッケージ/モジュール"
    private val modules = mapOf(
        "p/p" to "import util\nfun a(): Int {\n    return util.v()\n}\n",
        "p/util" to "fun v(): Int {\n    return 1\n}\n",
        "q/q" to "import util\nfun b(): Int {\n    return util.v() * 10\n}\n",
        "q/util" to "fun v(): Int {\n    return 2\n}\n",
        "util" to "fun v(): Int {\n    return 100\n}\n",
    )
    private val asked = ArrayList<Pair<String?, String>>()

    // 持ち主（"/" の前。ローカルは ""）が同じものを優先し、プロジェクトからは p・q の公開モジュール（p・q）とローカルのモジュールが見える
    private val scoped = ScopedModuleResolver { importer, name ->
        asked.add(importer to name)
        val owner = importer?.substringBefore('/', "").orEmpty()
        val id = when {
            owner.isNotEmpty() -> "$owner/$name"
            name == "p" || name == "q" -> "$name/$name"
            else -> name
        }
        modules[id]?.let { ScopedModuleLookup.Found(id, it) } ?: ScopedModuleLookup.Failed(ModuleLookup.NotFound)
    }

    private fun run(result: ModuleCompileResult): List<String> {
        val program = result.program ?: error("compile failed: ${result.diagnostics}")
        val printed = ArrayList<String>()
        val run = CreateScript.start(program, RunOptions(128, 0L)) { printed.add(it) }
        var ticks = 0
        while (!run.isFinished && ticks++ < 100_000) run.tick()
        assertEquals(VmState.HALTED, run.state, "error: ${run.error}")
        return printed
    }

    private fun compile(main: String, resolver: ScopedModuleResolver = scoped, entry: String? = "main") =
        CreateScript.compileScoped(main, resolver, entry, CompileOptions.STANDALONE)

    // 別のパッケージの同じ名前の（公開しない）モジュールと、同じ名前のローカルのモジュールが、1 つのプログラムに共存する。呼び出しは、
    // そのファイルの import が指すモジュールに行く
    @Test
    fun modulesWithTheSameNameCoexist() {
        val result = compile("import p\nimport q\nimport util\nprintln(p.a())\nprintln(q.b())\nprintln(util.v())\n")
        assertEquals(listOf("1", "20", "100"), run(result))
        assertEquals(listOf("p/util", "p/p", "q/util", "q/q", "util"), result.modules)
        // 同じ import 元と名前は 1 回だけ問い合わせる
        assertEquals(listOf(null to "p", "p/p" to "util", null to "q", "q/q" to "util", null to "util"), asked)
    }

    // 見えないもの・失敗は、import の名前で報告する（ID は resolver の中だけのもの）
    @Test
    fun reportsLookupFailuresWithTheImportedName() {
        val cases = mapOf(
            ScopedModuleLookup.NotVisible to DiagnosticCode.MODULE_NOT_VISIBLE,
            ScopedModuleLookup.Failed(ModuleLookup.NotFound) to DiagnosticCode.MODULE_NOT_FOUND,
            ScopedModuleLookup.Failed(ModuleLookup.Ambiguous) to DiagnosticCode.MODULE_AMBIGUOUS,
            ScopedModuleLookup.Failed(ModuleLookup.OutsideProject) to DiagnosticCode.MODULE_OUTSIDE_PROJECT,
            ScopedModuleLookup.Failed(ModuleLookup.Unreadable("broken")) to DiagnosticCode.MODULE_UNREADABLE,
        )
        for ((lookup, code) in cases) {
            val result = compile("import thing\nprintln(thing.f())\n", { _, _ -> lookup })
            assertNull(result.program)
            val diagnostic = result.diagnostics.single()
            assertEquals(code, diagnostic.diagnostic.code, lookup.toString())
            assertEquals("thing", (diagnostic.diagnostic.arguments.first() as DiagnosticArgument.StringArgument).value)
            assertEquals(1 to 8, diagnostic.diagnostic.position.line to diagnostic.diagnostic.position.column)
            assertNull(diagnostic.module)
        }
        // 組み込み関数の名前は、resolver を呼ぶ前に CS_MODULE_BUILTIN_NAME
        asked.clear()
        assertEquals(listOf(DiagnosticCode.MODULE_BUILTIN_NAME), compile("import println\n").diagnostics.map { it.diagnostic.code })
        assertEquals(emptyList<Pair<String?, String>>(), asked)
    }

    // 循環は ID で確かめる。エントリーと同じ名前の、パッケージの中のモジュール（別の ID）は循環ではない（S8.2 の L-1）
    @Test
    fun detectsCyclesByIdentifier() {
        val sources = mapOf(
            "g/g" to "import main\nfun g(): Int {\n    return main.m()\n}\n",
            "g/main" to "fun m(): Int {\n    return 3\n}\n",
            "loop" to "import main\nfun l(): Int {\n    return 0\n}\n",
            "c/a" to "import b\nfun a(): Int {\n    return 1\n}\n",
            "c/b" to "import a\nfun b(): Int {\n    return 2\n}\n",
        )
        val resolver = ScopedModuleResolver { importer, name ->
            val owner = importer?.substringBefore('/', "").orEmpty()
            val id = when {
                owner.isNotEmpty() -> "$owner/$name"
                name == "g" -> "g/g"
                name == "a" -> "c/a"
                else -> name
            }
            sources[id]?.let { ScopedModuleLookup.Found(id, it) } ?: if (id == "main") ScopedModuleLookup.Found("main", "println(0)\n") else ScopedModuleLookup.Failed(ModuleLookup.NotFound)
        }
        assertEquals(listOf("3"), run(compile("import g\nprintln(g.g())\n", resolver)))
        // ローカルのモジュールからエントリーへの import は循環
        val local = compile("import loop\nprintln(loop.l())\n", resolver)
        assertEquals(listOf(DiagnosticCode.MODULE_CYCLE), local.diagnostics.map { it.diagnostic.code })
        assertEquals(listOf(DiagnosticArgument.StringArgument("main -> loop -> main")), local.diagnostics.single().diagnostic.arguments)
        assertEquals("loop", local.diagnostics.single().module)
        // パッケージの中の循環（経路は ID で示す）
        val inPackage = compile("import a\nprintln(a.a())\n", resolver)
        assertEquals(listOf(DiagnosticCode.MODULE_CYCLE), inPackage.diagnostics.map { it.diagnostic.code })
        assertEquals(listOf(DiagnosticArgument.StringArgument("c/a -> c/b -> c/a")), inPackage.diagnostics.single().diagnostic.arguments)
        assertEquals("c/b", inPackage.diagnostics.single().module)
    }

    // ID の形は Core が確かめる（"." は関数の名前の区切りなので使えない）
    @Test
    fun validatesIdentifiers() {
        for (valid in listOf("util", "vector-math/vm_internal", "a:b@c+d", "_x", "9")) assertTrue(ScopedModuleLookup.isValidId(valid), valid)
        for (invalid in listOf("", "a.b", "a b", "/a", "-a", "é", "x".repeat(129))) {
            assertFalse(ScopedModuleLookup.isValidId(invalid), invalid)
            assertThrows(IllegalArgumentException::class.java) { ScopedModuleLookup.Found(invalid, "") }
        }
        assertThrows(IllegalArgumentException::class.java) { ScopedModuleLookup.Failed(ModuleLookup.Found("")) }
        assertThrows(IllegalArgumentException::class.java) { compile("println(1)\n", entry = "a.b") }
    }

    // 名前をそのまま ID にする resolver では、既存の compile と同じ命令・診断・モジュール・ハッシュになる
    @Test
    fun identityIdentifiersMatchTheExistingCompile() {
        val sources = mapOf(
            "alpha" to "import gamma\nfun a(): Int {\n    return gamma.g()\n}\n",
            "beta" to "import gamma\nimport alpha\nfun b(): Int {\n    return gamma.g() + alpha.a()\n}\n",
            "gamma" to "fun g(): Int {\n    return 1\n}\n",
            "cyc" to "import main\nfun c(): Int {\n    return 1\n}\n",
            "bad" to "fun broken(: Int {\n}\n",
            "typo" to "fun t(): Int {\n    return missing()\n}\n",
        )
        val plain = ModuleResolver { name -> sources[name]?.let { ModuleLookup.Found(it) } ?: ModuleLookup.NotFound }
        // （エントリーのファイル自身は、ID が entryModule の Found を返す。ホストの resolver はエントリーのファイルも探せる: 既存の compile は、
        // エントリーの名前の import を、resolver に問い合わせる前に循環として報告する）
        fun identity(entry: String?) = ScopedModuleResolver { _, name ->
            if (name == entry) return@ScopedModuleResolver ScopedModuleLookup.Found(name, "println(0)\n")
            sources[name]?.let { ScopedModuleLookup.Found(name, it) } ?: ScopedModuleLookup.Failed(ModuleLookup.NotFound)
        }
        val programs = listOf(
            "import alpha\nimport beta\nprintln(alpha.a() + beta.b())\n",
            "import missing\nprintln(1)\n",
            "import alpha\nimport alpha\nprintln(alpha.a())\n",
            "import cyc\nprintln(cyc.c())\n",
            "import bad\nprintln(1)\n",
            "import typo\nprintln(typo.t())\n",
            "import gamma\nprintln(gamma.nothing())\n",
            "println(1)\n",
            "fun f(): Int {\n    return 2\n}\nprintln(f())\n",
        )
        for (source in programs) {
            for (entry in listOf("main", null)) {
                val existing = CreateScript.compile(source, plain, entry, CompileOptions.STANDALONE)
                val viaScoped = CreateScript.compileScoped(source, identity(entry), entry, CompileOptions.STANDALONE)
                assertEquals(existing.diagnostics, viaScoped.diagnostics, source)
                assertEquals(existing.diagnosticsTruncated, viaScoped.diagnosticsTruncated, source)
                assertEquals(existing.modules, viaScoped.modules, source)
                assertArrayEquals(existing.sourceFingerprint, viaScoped.sourceFingerprint, source)
                assertEquals(existing.program, viaScoped.program, source)
                existing.program?.let { assertArrayEquals(ProgramFingerprint.ofProgram(it), ProgramFingerprint.ofProgram(viaScoped.program!!), source) }
            }
        }
    }
}
