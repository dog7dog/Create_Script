package com.creatrbypc.createanalytical.script.module

import com.creatrbypc.createanalytical.script.api.CreateScript
import com.creatrbypc.createanalytical.script.api.RunOptions
import com.creatrbypc.createanalytical.script.api.ScriptRun
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticArgument
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticCode
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticSeverity
import com.creatrbypc.createanalytical.script.vm.ProgramFingerprint
import com.creatrbypc.createanalytical.script.vm.VmLimits
import com.creatrbypc.createanalytical.script.vm.VmState
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// 第8.0段階: ローカル import。モジュールのソースはテストの中の Map から渡す（Core はファイルを読まない）
class ModuleTest {
    // 名前 → ソース。呼ばれた名前を順に記録する
    private class MapResolver(private val sources: Map<String, String>) : ModuleResolver {
        val requested = ArrayList<String>()

        override fun resolve(name: String): ModuleLookup {
            requested.add(name)
            return sources[name]?.let { ModuleLookup.Found(it) } ?: ModuleLookup.NotFound
        }
    }

    private fun compile(main: String, modules: Map<String, String>, entry: String? = "main") = CreateScript.compile(main, MapResolver(modules), entry)

    private fun finish(run: ScriptRun, maxTicks: Int = 100_000): Int {
        var ticks = 0
        while (!run.isFinished && ticks < maxTicks) {
            run.tick()
            ticks++
        }
        return ticks
    }

    private fun output(main: String, modules: Map<String, String>): List<String> {
        val result = compile(main, modules)
        val program = result.program ?: error("compile failed: ${result.diagnostics}")
        val printed = ArrayList<String>()
        val run = CreateScript.start(program, RunOptions(128, 0L)) { printed.add(it) }
        finish(run)
        assertEquals(VmState.HALTED, run.state, "error: ${run.error}")
        return printed
    }

    // エラーの診断（モジュール名、コード、行、列）
    private fun errors(main: String, modules: Map<String, String>, entry: String? = "main"): List<Pair<String?, Triple<DiagnosticCode, Int, Int>>> {
        val result = compile(main, modules, entry)
        assertNull(result.program)
        assertTrue(result.hasErrors)
        return result.diagnostics.filter { it.diagnostic.severity == DiagnosticSeverity.ERROR }
            .map { it.module to Triple(it.diagnostic.code, it.diagnostic.position.line, it.diagnostic.position.column) }
    }

    private val geometry = "fun square(x: Int): Int {\n    return x * x\n}\n"

    // README の Core の例と同じ
    @Test
    fun readmeExample() {
        val modules = mapOf("geometry" to "fun square(x: Int): Int {\n  return x * x\n}\n")
        val result = CreateScript.compile("import geometry\nprintln(geometry.square(5))", { name ->
            modules[name]?.let { ModuleLookup.Found(it) } ?: ModuleLookup.NotFound
        })
        assertEquals(emptyList<ModuleDiagnostic>(), result.diagnostics)
        val printed = ArrayList<String>()
        finish(CreateScript.start(result.program!!, RunOptions(128, 0L)) { printed.add(it) })
        assertEquals(listOf("25"), printed)
    }

    // ---- 正常系 ----

    @Test
    fun importsOneModule() {
        assertEquals(listOf("25"), output("import geometry\n\nprintln(geometry.square(5))\n", mapOf("geometry" to geometry)))
    }

    @Test
    fun importsSeveralModulesAndModulesImportModules() {
        val modules = mapOf(
            "geometry" to "import numbers\nfun area(w: Int, h: Int): Int {\n    return numbers.times(w, h)\n}\nfun cube(x: Int): Int {\n    return x * square(x)\n}\nfun square(x: Int): Int {\n    return x * x\n}\n",
            "numbers" to "fun times(a: Int, b: Int): Int {\n    return a * b\n}\n",
            "text" to "fun greet(name: String): String {\n    return name\n}\n",
        )
        val main = "import geometry\nimport text\nprintln(geometry.area(3, 4))\nprintln(geometry.cube(3))\nprintln(text.greet(\"modules\"))\n"
        assertEquals(listOf("12", "27", "modules"), output(main, modules))
        // import されるモジュールが先に並ぶ（決まった順）
        assertEquals(listOf("numbers", "geometry", "text"), compile(main, modules).modules)
    }

    // 同じファイルの同じ import は警告で 1 つにまとめる。複数のモジュールから import されたモジュールも 1 回だけ解決する
    @Test
    fun resolvesEachModuleOnce() {
        val modules = mapOf(
            "a" to "import shared\nfun f(): Int {\n    return shared.one() + 1\n}\n",
            "b" to "import shared\nfun f(): Int {\n    return shared.one() + 2\n}\n",
            "shared" to "fun one(): Int {\n    return 1\n}\n",
        )
        val resolver = MapResolver(modules)
        val result = CreateScript.compile("import a\nimport b\nimport a\nprintln(a.f() + b.f())\n", resolver, "main")
        assertNotNull(result.program)
        assertEquals(listOf("a", "shared", "b"), resolver.requested)
        val warning = result.diagnostics.single()
        assertEquals(DiagnosticCode.PARSE_DUPLICATE_IMPORT, warning.diagnostic.code)
        assertEquals(null to (3 to 8), warning.module to (warning.diagnostic.position.line to warning.diagnostic.position.column))
        assertEquals(listOf("5"), output("import a\nimport b\nprintln(a.f() + b.f())\n", modules))
    }

    // 同じ名前の関数がエントリーのファイルとモジュールにあっても別の関数。モジュールの中の名前だけの呼び出し・再帰は同じモジュールの関数
    @Test
    fun keepsNamesApart() {
        val modules = mapOf("m" to "fun f(n: Int): Int {\n    if (n <= 1) {\n        return 1\n    }\n    return n * f(n - 1)\n}\nfun g(): Int {\n    return f(4)\n}\n")
        assertEquals(listOf("24", "7", "24"), output("import m\nfun f(n: Int): Int {\n    return n + 1\n}\nprintln(m.f(4))\nprintln(f(6))\nprintln(m.g())\n", modules))
    }

    // import の無いプログラムは、CreateScript.compile(source) と同じ命令・同じハッシュになる。resolver は呼ばない
    @Test
    fun programWithoutImportsIsUnchanged() {
        val source = "fun add(a: Int, b: Int): Int {\n    return a + b\n}\nclass Box {\n    var v = 1\n}\nval box = Box()\nbox.v = add(2, 3)\nprintln(box.v)\n"
        val resolver = MapResolver(emptyMap())
        val withResolver = CreateScript.compile(source, resolver, "main").program!!
        val plain = CreateScript.compile(source).program!!
        assertEquals(plain, withResolver)
        assertArrayEquals(ProgramFingerprint.ofProgram(plain), ProgramFingerprint.ofProgram(withResolver))
        assertEquals(emptyList<String>(), resolver.requested)
    }

    // 複数のファイルから 1 つのプログラムを作る。モジュールの関数はエントリーのファイルの関数の後。命令の位置は、ファイルごとの位置に直せる
    @Test
    fun buildsOneProgramFromSeveralFiles() {
        val main = "import geometry\nfun twice(x: Int): Int {\n    return 2 * x\n}\nprintln(twice(geometry.square(3)))\n"
        val result = compile(main, mapOf("geometry" to geometry))
        val program = result.program!!
        assertEquals(2, program.functions.size)
        // 2 つ目の関数（geometry.square）の最初の命令の位置は geometry.cscr の 2 行目（return x * x の x）
        val entry = program.instructions[program.functions[1].entry]
        val located = result.locate(com.creatrbypc.createanalytical.script.diagnostic.Diagnostic(DiagnosticCode.RUN_INTEGER_OVERFLOW, DiagnosticSeverity.ERROR, com.creatrbypc.createanalytical.script.diagnostic.DiagnosticPhase.RUNTIME, entry.position))
        assertEquals("geometry", located.module)
        assertEquals(2, located.diagnostic.position.line)
        assertEquals(geometry.indexOf("x * x"), located.diagnostic.position.offset)
        // 最初の関数（twice）はエントリーのファイル
        assertNull(result.locate(com.creatrbypc.createanalytical.script.diagnostic.Diagnostic(DiagnosticCode.RUN_INTEGER_OVERFLOW, DiagnosticSeverity.ERROR, com.creatrbypc.createanalytical.script.diagnostic.DiagnosticPhase.RUNTIME, program.instructions[program.functions[0].entry].position)).module)
        assertEquals(listOf("18"), output(main, mapOf("geometry" to geometry)))
    }

    // import は予約語ではない（これまでどおり変数名などに使える）
    @Test
    fun importIsStillAnIdentifier() {
        assertEquals(listOf("3", "6"), output("val import = 3\nprintln(import)\nprintln(import * 2)\n", emptyMap()))
        assertNotNull(CreateScript.compile("var import = 1\nimport = import + 1\nprintln(import)").program)
    }

    // ---- 型検査 ----

    @Test
    fun checksArgumentAndReturnTypes() {
        val modules = mapOf("geometry" to geometry)
        assertEquals(listOf(null to Triple(DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH, 2, 25)), errors("import geometry\nprintln(geometry.square(\"5\"))\n", modules))
        assertEquals(listOf(null to Triple(DiagnosticCode.SEM_TYPE_MISMATCH, 2, 17)), errors("import geometry\nval s: String = geometry.square(5)\n", modules))
        assertEquals(listOf(null to Triple(DiagnosticCode.SEM_WRONG_ARGUMENT_COUNT, 2, 9)), errors("import geometry\nprintln(geometry.square(1, 2))\n", modules))
        assertEquals(listOf(null to Triple(DiagnosticCode.SEM_UNKNOWN_FUNCTION, 2, 9)), errors("import geometry\nprintln(geometry.cube(2))\n", modules))
    }

    // モジュールの中のエラーは、そのモジュールのファイルの行・列で報告する
    @Test
    fun reportsErrorsInsideModules() {
        val modules = mapOf("bad" to "fun f(): Int {\n    return \"text\"\n}\n")
        assertEquals(listOf("bad" to Triple(DiagnosticCode.SEM_TYPE_MISMATCH, 2, 12)), errors("import bad\nprintln(bad.f())\n", modules))
        val syntax = mapOf("bad" to "fun f(): Int {\n    return (1\n}\n")
        assertEquals("bad", errors("import bad\nprintln(bad.f())\n", syntax).single().first)
    }

    // ---- 名前解決とモジュールの分離（第8.0.1段階） ----

    // import していないモジュールの関数は、名前付きでも名前だけでも呼べない（エントリーが両方を import していても）
    @Test
    fun modulesSeeOnlyWhatTheyImport() {
        val b = "fun g(): Int {\n    return 2\n}\n"
        val main = "import a\nimport b\nprintln(a.f())\nprintln(b.g())\n"
        assertEquals(listOf("a" to Triple(DiagnosticCode.SEM_UNDECLARED_VARIABLE, 2, 12)), errors(main, mapOf("a" to "fun f(): Int {\n    return b.g()\n}\n", "b" to b)))
        assertEquals(listOf("a" to Triple(DiagnosticCode.SEM_UNKNOWN_FUNCTION, 2, 12)), errors(main, mapOf("a" to "fun f(): Int {\n    return g()\n}\n", "b" to b)))
        // エントリーは、モジュールが import したモジュールを使えない
        assertEquals(listOf(null to Triple(DiagnosticCode.SEM_UNDECLARED_VARIABLE, 2, 9)), errors("import a\nprintln(b.g())\n", mapOf("a" to "import b\nfun f(): Int {\n    return b.g()\n}\n", "b" to b)))
    }

    // 同じ名前の関数が、エントリー・2 つのモジュールにあっても、それぞれが呼ぶのは自分の（または名前で指定した）関数
    @Test
    fun sameNamesCallTheRightFunction() {
        val modules = mapOf(
            "a" to "import b\nfun f(): Int {\n    return 10\n}\nfun viaB(): Int {\n    return b.f()\n}\nfun own(): Int {\n    return f()\n}\n",
            "b" to "fun f(): Int {\n    return 20\n}\n",
        )
        val main = "import a\nimport b\nfun f(): Int {\n    return 30\n}\nprintln(a.f())\nprintln(b.f())\nprintln(f())\nprintln(a.viaB())\nprintln(a.own())\n"
        assertEquals(listOf("10", "20", "30", "20", "10"), output(main, modules))
        // モジュールの中では、エントリーの関数と同じ名前の変数を宣言できる（エントリーの関数は見えないため衝突しない）
        assertEquals(listOf("3"), output("import m\nfun helper(): Int {\n    return 2\n}\nprintln(m.f())\n", mapOf("m" to "fun f(): Int {\n    val helper = 3\n    return helper\n}\n")))
        // メソッドの中からもモジュールの関数を呼べる
        assertEquals(listOf("4"), output("import geometry\nclass C {\n    fun m(): Int {\n        return geometry.square(2)\n    }\n}\nprintln(C().m())\n", mapOf("geometry" to geometry)))
    }

    // 重複した import・複数のモジュールからの import でも、モジュールの関数は 1 回だけプログラムに入る
    @Test
    fun duplicateImportsDoNotDuplicateCode() {
        val modules = mapOf(
            "a" to "import shared\nfun f(): Int {\n    return shared.one()\n}\n",
            "b" to "import shared\nfun g(): Int {\n    return shared.one()\n}\n",
            "shared" to "fun one(): Int {\n    return 1\n}\nfun two(): Int {\n    return 2\n}\n",
        )
        val once = compile("import a\nprintln(a.f())\n", modules).program!!
        val twice = compile("import a\nimport a\nprintln(a.f())\n", modules).program!!
        assertEquals(once.functions.size, twice.functions.size)
        assertEquals(once.instructions.map { it.opcode to it.operand }, twice.instructions.map { it.opcode to it.operand })
        // a（1）と b（1）と shared（2）の 4 つ
        assertEquals(4, compile("import a\nimport b\nimport b\nprintln(a.f() + b.g())\n", modules).program!!.functions.size)
    }

    // 長い循環も、循環を含まない共有（菱形）と区別して検出する
    @Test
    fun detectsLongCycles() {
        val modules = mapOf(
            "a" to "import b\nfun f() {\n}\n",
            "b" to "import shared\nimport c\nfun f() {\n}\n",
            "c" to "import shared\nimport a\nfun f() {\n}\n",
            "shared" to "fun f() {\n}\n",
        )
        val result = compile("import shared\nimport a\n", modules)
        val cycle = result.diagnostics.single()
        assertEquals(DiagnosticCode.MODULE_CYCLE, cycle.diagnostic.code)
        assertEquals("c" to listOf(DiagnosticArgument.StringArgument("a -> b -> c -> a")), cycle.module to cycle.diagnostic.arguments)
    }

    // モジュールどうしの呼び出しも型を検査する。リストなどの型も渡せる
    @Test
    fun checksTypesBetweenModules() {
        val b = "fun half(x: Double): Double {\n    return x / 2.0\n}\nfun items(): List<Int> {\n    return listOf(1, 2, 3)\n}\n"
        assertEquals(listOf("a" to Triple(DiagnosticCode.SEM_ARGUMENT_TYPE_MISMATCH, 3, 19)), errors("import a\n", mapOf("a" to "import b\nfun f(): Double {\n    return b.half(1)\n}\n", "b" to b)))
        assertEquals(listOf("a" to Triple(DiagnosticCode.SEM_TYPE_MISMATCH, 3, 12)), errors("import a\n", mapOf("a" to "import b\nfun f(): Int {\n    return b.half(1.0)\n}\n", "b" to b)))
        assertEquals(listOf("2", "0.5"), output("import b\nprintln(b.items()[1])\nprintln(b.half(1.0))\n", mapOf("b" to b)))
    }

    // ---- 異常系 ----

    @Test
    fun reportsMissingModules() {
        assertEquals(listOf(null to Triple(DiagnosticCode.MODULE_NOT_FOUND, 2, 8)), errors("// first\nimport geometry\nprintln(geometry.square(5))\n", emptyMap()))
        assertEquals(listOf("a" to Triple(DiagnosticCode.MODULE_NOT_FOUND, 1, 8)), errors("import a\nprintln(a.f())\n", mapOf("a" to "import missing\nfun f(): Int {\n    return 1\n}\n")))
    }

    @Test
    fun reportsCyclicImports() {
        val modules = mapOf(
            "a" to "import b\nfun f(): Int {\n    return b.g()\n}\n",
            "b" to "import a\nfun g(): Int {\n    return 1\n}\n",
            "self" to "import self\nfun h(): Int {\n    return 1\n}\n",
            "back" to "import main\nfun k(): Int {\n    return 1\n}\n",
        )
        val result = compile("import a\nprintln(a.f())\n", modules)
        val cycle = result.diagnostics.single()
        assertEquals(DiagnosticCode.MODULE_CYCLE, cycle.diagnostic.code)
        assertEquals("b", cycle.module)
        assertEquals(listOf(DiagnosticArgument.StringArgument("a -> b -> a")), cycle.diagnostic.arguments)
        assertEquals(listOf("self" to Triple(DiagnosticCode.MODULE_CYCLE, 1, 8)), errors("import self\nprintln(self.h())\n", modules))
        // エントリーのファイル自身の import
        assertEquals(listOf("back" to Triple(DiagnosticCode.MODULE_CYCLE, 1, 8)), errors("import back\nprintln(back.k())\n", modules))
        assertEquals(listOf(null to Triple(DiagnosticCode.MODULE_CYCLE, 1, 8)), errors("import main\nprintln(1)\n", modules))
    }

    @Test
    fun reportsNameConflicts() {
        val modules = mapOf("geometry" to geometry, "println" to geometry)
        val conflict = Triple(DiagnosticCode.PARSE_IMPORT_NAME_CONFLICT, 2, 5)
        assertEquals(listOf(null to conflict), errors("import geometry\nval geometry = 1\n", modules))
        assertEquals(listOf(null to conflict), errors("import geometry\nfun geometry(): Int {\n    return 1\n}\n", modules))
        assertEquals(listOf(null to Triple(DiagnosticCode.PARSE_IMPORT_NAME_CONFLICT, 2, 7)), errors("import geometry\nclass geometry\n", modules))
        assertEquals(listOf(null to Triple(DiagnosticCode.PARSE_IMPORT_NAME_CONFLICT, 2, 7)), errors("import geometry\nfun f(geometry: Int) {\n}\n", modules))
        assertEquals(listOf(null to Triple(DiagnosticCode.PARSE_IMPORT_NAME_CONFLICT, 2, 6)), errors("import geometry\nfor (geometry in 1..2) {\n}\n", modules))
        assertEquals(listOf(null to Triple(DiagnosticCode.MODULE_BUILTIN_NAME, 1, 8)), errors("import println\n", modules))
        // 同じモジュールの中の同じ名前の関数
        assertEquals(listOf("d" to Triple(DiagnosticCode.SEM_DUPLICATE_FUNCTION, 4, 5)), errors("import d\n", mapOf("d" to "fun f() {\n}\n\nfun f() {\n}\n")))
    }

    // モジュールの関数から、エントリーのファイルの変数・関数・クラスは見えない
    @Test
    fun modulesDoNotSeeTheEntryFile() {
        val main = "import m\nval secret = 1\nfun helper(): Int {\n    return 2\n}\nclass Box\nprintln(m.f())\n"
        assertEquals(listOf("m" to Triple(DiagnosticCode.SEM_UNDECLARED_VARIABLE, 2, 12)), errors(main, mapOf("m" to "fun f(): Int {\n    return secret\n}\n")))
        assertEquals(listOf("m" to Triple(DiagnosticCode.SEM_UNKNOWN_FUNCTION, 2, 12)), errors(main, mapOf("m" to "fun f(): Int {\n    return helper()\n}\n")))
        assertEquals(listOf("m" to Triple(DiagnosticCode.SEM_UNKNOWN_FUNCTION, 2, 13)), errors(main, mapOf("m" to "fun f(): Int {\n    val b = Box()\n    return 1\n}\n")))
        assertEquals(listOf("m" to Triple(DiagnosticCode.SEM_UNKNOWN_TYPE, 1, 10)), errors("import m\nclass Box\n", mapOf("m" to "fun f(b: Box) {\n}\n")))
    }

    @Test
    fun rejectsInvalidImportSyntax() {
        val modules = mapOf("geometry" to geometry)
        assertEquals(listOf(null to Triple(DiagnosticCode.PARSE_INVALID_IMPORT, 1, 16)), errors("import geometry.square\n", modules))
        assertEquals(listOf(null to Triple(DiagnosticCode.PARSE_INVALID_IMPORT, 1, 17)), errors("import geometry as g\n", modules))
        assertEquals(listOf(null to Triple(DiagnosticCode.PARSE_INVALID_IMPORT, 1, 16)), errors("import geometry.*\n", modules))
        assertEquals(listOf(null to Triple(DiagnosticCode.PARSE_INVALID_IMPORT, 1, 8)), errors("import \"../geometry\"\n", modules))
        assertEquals(listOf(null to Triple(DiagnosticCode.PARSE_MISPLACED_IMPORT, 2, 1)), errors("println(1)\nimport geometry\n", modules))
        assertEquals(listOf(null to Triple(DiagnosticCode.PARSE_MISPLACED_IMPORT, 2, 5)), errors("if (true) {\n    import geometry\n}\n", modules))
        assertEquals(listOf(null to Triple(DiagnosticCode.PARSE_INVALID_MODULE_REFERENCE, 2, 9)), errors("import geometry\nprintln(geometry)\n", modules))
        assertEquals(listOf(null to Triple(DiagnosticCode.PARSE_INVALID_MODULE_REFERENCE, 2, 9)), errors("import geometry\nprintln(geometry.PI)\n", modules))
    }

    // モジュール名は識別子 1 つのため、パス（.. や /）を書く方法が無い。ホストの resolver には識別子だけが渡る
    @Test
    fun passesOnlyIdentifiersToTheResolver() {
        val resolver = MapResolver(emptyMap())
        for (source in listOf("import \"/etc/passwd\"\n", "import \"../x\"\n", "import geometry.x\n", "import a/b\n", "import ../x\n", "import /x\n")) {
            assertNull(CreateScript.compile(source, resolver, "main").program, source)
        }
        assertEquals(emptyList<String>(), resolver.requested)
    }

    @Test
    fun rejectsStatementsAndClassesInModules() {
        assertEquals(listOf("m" to Triple(DiagnosticCode.MODULE_TOP_LEVEL_STATEMENT, 1, 1)), errors("import m\n", mapOf("m" to "println(1)\nfun f() {\n}\n")))
        assertEquals(listOf("m" to Triple(DiagnosticCode.MODULE_CLASS_UNSUPPORTED, 1, 1)), errors("import m\n", mapOf("m" to "class Point\n")))
    }

    // ホストが報告した問題（曖昧・ルートの外・読めない）
    @Test
    fun reportsResolverProblems() {
        val resolver = ModuleResolver { name ->
            when (name) {
                "twice" -> ModuleLookup.Ambiguous
                "outside" -> ModuleLookup.OutsideProject
                else -> ModuleLookup.Unreadable("not valid UTF-8")
            }
        }
        val result = CreateScript.compile("import twice\nimport outside\nimport broken\n", resolver, "main")
        assertNull(result.program)
        assertEquals(listOf(DiagnosticCode.MODULE_AMBIGUOUS, DiagnosticCode.MODULE_OUTSIDE_PROJECT, DiagnosticCode.MODULE_UNREADABLE), result.diagnostics.map { it.diagnostic.code })
        assertEquals(listOf(DiagnosticArgument.StringArgument("broken"), DiagnosticArgument.StringArgument("not valid UTF-8")), result.diagnostics[2].diagnostic.arguments)
    }

    // モジュールの解決方法を渡さないコンパイル（これまでの API・対話モード）では import を使えない
    @Test
    fun singleSourceCompileRejectsImports() {
        val result = CreateScript.compile("import geometry\nprintln(geometry.square(5))\n")
        assertNull(result.program)
        assertEquals(listOf(DiagnosticCode.MODULE_IMPORT_UNAVAILABLE), result.diagnostics.map { it.code })
    }

    // ---- 既存の VM の上限 ----

    @Test
    fun limitsTheNumberOfModules() {
        val count = ModuleLimits.MAX_MODULES + 1
        val modules = (1..count).associate { "m$it" to "fun f(): Int {\n    return $it\n}\n" }
        val main = (1..count).joinToString("") { "import m$it\n" }
        assertEquals(listOf(null to Triple(DiagnosticCode.MODULE_TOO_MANY, count, 8)), errors(main, modules))
    }

    // 命令数の上限はプログラム全体に適用する（ファイルごとの上限の合計にはならない）
    @Test
    fun limitsInstructionsOfTheWholeProgram() {
        val body = "fun f(): Int {\n    var x = 0\n" + "    x = x + 1\n".repeat(280) + "    return x\n}\n"
        val single = compile("import m1\nprintln(m1.f())\n", mapOf("m1" to body))
        assertNotNull(single.program)
        assertTrue(single.program!!.instructions.size < VmLimits.MAX_INSTRUCTIONS)
        val modules = (1..4).associate { "m$it" to body }
        val result = compile("import m1\nimport m2\nimport m3\nimport m4\nprintln(m1.f() + m2.f() + m3.f() + m4.f())\n", modules)
        assertNull(result.program)
        assertEquals(listOf(DiagnosticCode.COMP_TOO_MANY_INSTRUCTIONS), result.diagnostics.map { it.diagnostic.code })
    }

    // 使わない関数も命令に入り、4096 命令（コンパイル時のプログラムの大きさの上限）に数える（第8.0.1段階の確認。不要な関数の除去は未実装）
    @Test
    fun unusedFunctionsCountTowardsTheProgramSize() {
        val small = "fun used(): Int {\n    return 1\n}\n"
        val withUnused = small + "fun unused(): Int {\n    var x = 0\n" + "    x = x + 1\n".repeat(100) + "    return x\n}\n"
        val main = "import m\nprintln(m.used())\n"
        val before = compile(main, mapOf("m" to small)).program!!
        val after = compile(main, mapOf("m" to withUnused)).program!!
        assertTrue(after.instructions.size >= before.instructions.size + 400, "${before.instructions.size} -> ${after.instructions.size}")
        assertEquals(before.functions.size + 1, after.functions.size)
    }

    // モジュールの関数も 1 tick の命令数の上限・呼び出しの深さの上限・整数の範囲の検査を受ける。実行時エラーはモジュールのファイルの位置に直せる
    @Test
    fun keepsRuntimeLimits() {
        val modules = mapOf(
            "loop" to "fun forever() {\n    while (true) {\n    }\n}\n",
            "deep" to "fun down(n: Int): Int {\n    return down(n + 1)\n}\n",
            "big" to "fun grow(): Int {\n    var x = 2147483647\n    x = x + 1\n    return x\n}\n",
        )
        val loop = compile("import loop\nloop.forever()\n", modules)
        val run = CreateScript.start(loop.program!!, RunOptions(16, 0L))
        repeat(100) { assertTrue(run.tick().executed <= 16) }
        assertEquals(false, run.isFinished)

        val deep = compile("import deep\nprintln(deep.down(0))\n", modules)
        val deepRun = CreateScript.start(deep.program!!, RunOptions(128, 0L))
        finish(deepRun)
        assertEquals(VmState.ERROR, deepRun.state)
        val depth = deep.locate(deepRun.error!!)
        assertEquals(DiagnosticCode.RUN_CALL_DEPTH_EXCEEDED, depth.diagnostic.code)
        assertEquals("deep" to 2, depth.module to depth.diagnostic.position.line)

        val big = compile("import big\nprintln(big.grow())\n", modules)
        val bigRun = CreateScript.start(big.program!!, RunOptions(128, 0L))
        finish(bigRun)
        val overflow = big.locate(bigRun.error!!)
        assertEquals(DiagnosticCode.RUN_INTEGER_OVERFLOW, overflow.diagnostic.code)
        assertEquals("big" to 3, overflow.module to overflow.diagnostic.position.line)
    }

    // 乱数は VM の状態で、モジュールの関数から使っても同じ種なら同じ値になる
    @Test
    fun randomIsDeterministicAcrossModules() {
        val modules = mapOf("dice" to "fun roll(): Int {\n    return randomInt(1, 7)\n}\n")
        val main = "import dice\nrandomSeed(7)\nprintln(dice.roll())\nprintln(dice.roll())\n"
        val plain = CreateScript.compile("randomSeed(7)\nprintln(randomInt(1, 7))\nprintln(randomInt(1, 7))\n").program!!
        val expected = ArrayList<String>()
        finish(CreateScript.start(plain, RunOptions(128, 0L)) { expected.add(it) })
        assertEquals(expected, output(main, modules))
    }
}
