package com.creatrbypc.createanalytical.script.module

import com.creatrbypc.createanalytical.script.ast.ImportDeclaration
import com.creatrbypc.createanalytical.script.ast.Program
import com.creatrbypc.createanalytical.script.compiler.CompileOptions
import com.creatrbypc.createanalytical.script.compiler.Compiler
import com.creatrbypc.createanalytical.script.diagnostic.Diagnostic
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticArgument
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticCode
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticCollector
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticPhase
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticSeverity
import com.creatrbypc.createanalytical.script.diagnostic.SourcePosition
import com.creatrbypc.createanalytical.script.lexer.LexResult
import com.creatrbypc.createanalytical.script.lexer.Lexer
import com.creatrbypc.createanalytical.script.parser.ParseResult
import com.creatrbypc.createanalytical.script.parser.Parser
import com.creatrbypc.createanalytical.script.semantic.BuiltinFunctions
import com.creatrbypc.createanalytical.script.semantic.SemanticAnalyzer
import com.creatrbypc.createanalytical.script.vm.CompiledProgram
import com.creatrbypc.createanalytical.script.vm.ProgramFingerprint

// ローカル import（第8.0段階）。Core はファイル・ネットワークを読まない。モジュールのソースは、ホスト（CLI など）が ModuleResolver で渡す
object ModuleLimits {
    // エントリーのファイルを除くモジュールの数。1 つのソースの上限（LexerLimits）はファイルごとに、命令数などの上限（VmLimits）はプログラム全体に適用する
    const val MAX_MODULES = 16
}

// ホストがモジュールを探した結果。Found の source は、ホストが確かめた（プロジェクトのルートの中の、読める）ファイルの内容
sealed interface ModuleLookup {
    data class Found(val source: String) : ModuleLookup

    data object NotFound : ModuleLookup

    // 同じ名前として扱うファイルが複数ある（大文字・小文字だけが違うものなど）
    data object Ambiguous : ModuleLookup

    // プロジェクトのルートの外を指す（シンボリックリンクなど）
    data object OutsideProject : ModuleLookup

    // 読めない。reason は診断に載せる短い説明（64 単位まで）
    data class Unreadable(val reason: String) : ModuleLookup
}

// モジュール名（識別子 1 つ。Parser が確かめたもの）からソースを探す。同じ名前については 1 回だけ呼ぶ
fun interface ModuleResolver {
    fun resolve(name: String): ModuleLookup
}

// module はファイルのモジュール名（エントリーのファイルは null）。diagnostic の位置は、そのファイルの中の位置
data class ModuleDiagnostic(val module: String?, val diagnostic: Diagnostic)

// program はエラーが無いときだけ返す。modules は解決したモジュール（プログラムに入れた順: import されるものが先）
class ModuleCompileResult internal constructor(
    val program: CompiledProgram?,
    val diagnostics: List<ModuleDiagnostic>,
    val diagnosticsTruncated: Boolean,
    val hasErrors: Boolean,
    val modules: List<String>,
    private val files: List<SourceFile>,
    private val sources: SourceSet,
) {
    // コンパイルに使ったソース（エントリーと、読めたモジュール）
    internal class SourceSet(val entry: String, val modules: Map<String, String>)

    // エントリーと読めたすべてのモジュールのソースのハッシュ（ProgramFingerprint.ofModules。第8.0.1段階）。保存した実行状態を復元するホストは、
    // エントリーのソースのハッシュの代わりにこれを比べる（import の無いプログラムでは ProgramFingerprint.ofSource(source) と同じ値）。
    // プログラムのハッシュ（ProgramFingerprint.ofProgram）と合わせて比べれば、モジュールだけを変えたプログラムの状態も復元しない
    val sourceFingerprint: ByteArray get() = ProgramFingerprint.ofModules(sources.entry, sources.modules)

    // ファイルの名前と、プログラム全体での位置（offset）の始まり
    internal class SourceFile(val module: String?, val base: Int)

    // プログラムの位置（実行時エラー ScriptRun.error など）を、どのファイルのどの位置かに直す。行・列はもともとファイルの中の値
    fun locate(diagnostic: Diagnostic): ModuleDiagnostic = locate(files, diagnostic)

    internal companion object {
        fun locate(files: List<SourceFile>, diagnostic: Diagnostic): ModuleDiagnostic {
            val position = diagnostic.position
            val file = files.lastOrNull { it.base <= position.offset } ?: files.first()
            return ModuleDiagnostic(file.module, diagnostic.copy(position = position.copy(offset = position.offset - file.base)))
        }
    }
}

// エントリーのファイルと、import をたどって集めたモジュールを、1 つのプログラムにまとめてコンパイルする。
// - モジュールの関数は "モジュール名.関数名" という名前の関数にして、エントリーのファイルの関数の後に並べる（命令・定数・上限はプログラム全体で 1 つ）
// - 各ファイルの位置の offset に、ファイルごとの始まり（前のファイルの長さ + 1 の合計）を足し、診断・命令の位置からファイルを決める。
//   エントリーのファイルの始まりは 0 のため、import の無いプログラムは CreateScript.compile(source) と同じ命令になる
// - 同じモジュールは 1 回だけ解決する。import の循環・見つからないモジュールなどは診断にする（解決できない import があれば意味解析しない）
internal class ModuleCompiler private constructor(private val resolver: ModuleResolver, private val entryModule: String?, private val options: CompileOptions) {
    private class SourceUnit(val module: String?, val base: Int, val program: Program?)

    private enum class State { RESOLVING, DONE, FAILED }

    private val diagnostics = DiagnosticCollector()
    private val files = ArrayList<ModuleCompileResult.SourceFile>()
    private val states = HashMap<String, State>()
    private val resolved = ArrayList<SourceUnit>()
    private val moduleSources = HashMap<String, String>()
    private var nextBase = 0

    companion object {
        fun compile(source: String, resolver: ModuleResolver, entryModule: String?, options: CompileOptions): ModuleCompileResult =
            ModuleCompiler(resolver, entryModule, options).run(source)
    }

    private fun run(source: String): ModuleCompileResult {
        val entry = load(null, source)
        entry.program?.let { visit(it.imports, listOf(entryModule ?: ENTRY)) }
        val main = entry.program
        val compiled = if (main == null || diagnostics.hasErrors) {
            null
        } else {
            val functions = main.functions + resolved.flatMap { unit -> unit.program!!.functions.map { it.copy(name = "${unit.module}.${it.name}") } }
            val merged = Program(main.statements, functions, main.classes)
            Compiler.compile(SemanticAnalyzer.analyze(ParseResult(merged, diagnostics.diagnostics, diagnostics.isTruncated, diagnostics.hasErrors)), options)
        }
        val all = compiled?.diagnostics ?: diagnostics.diagnostics
        return ModuleCompileResult(
            compiled?.program,
            all.map { ModuleCompileResult.locate(files, it) },
            compiled?.diagnosticsTruncated ?: diagnostics.isTruncated,
            compiled?.hasErrors ?: diagnostics.hasErrors,
            resolved.map { it.module!! },
            files.toList(),
            ModuleCompileResult.SourceSet(source, moduleSources.toMap()),
        )
    }

    // 1 つのファイルを字句解析・構文解析する。位置はプログラム全体での位置にずらす
    private fun load(module: String?, source: String): SourceUnit {
        val base = nextBase
        nextBase += source.length + 1
        files.add(ModuleCompileResult.SourceFile(module, base))
        val parsed = Parser.parse(shift(Lexer.tokenize(source), base))
        diagnostics.merge(parsed.diagnostics, parsed.diagnosticsTruncated, parsed.hasErrors)
        return SourceUnit(module, base, parsed.program)
    }

    private fun shift(result: LexResult, base: Int): LexResult {
        if (base == 0) return result
        fun moved(position: SourcePosition) = position.copy(offset = position.offset + base)
        return result.copy(
            tokens = result.tokens.map { it.copy(position = moved(it.position)) },
            diagnostics = result.diagnostics.map { it.copy(position = moved(it.position)) },
        )
    }

    // path は import をたどってきたモジュール（エントリーのファイルから順）。深さは MAX_MODULES + 1 まで
    private fun visit(imports: List<ImportDeclaration>, path: List<String>) {
        for (declaration in imports) {
            val name = declaration.name
            if (name in BuiltinFunctions.NAMES) {
                report(DiagnosticCode.MODULE_BUILTIN_NAME, declaration, name)
                continue
            }
            if (name == entryModule || states[name] == State.RESOLVING) {
                val start = path.indexOf(name).coerceAtLeast(0)
                report(DiagnosticCode.MODULE_CYCLE, declaration, (path.drop(start) + name).joinToString(" -> "))
                continue
            }
            if (states[name] != null) continue
            if (states.size >= ModuleLimits.MAX_MODULES) {
                report(DiagnosticCode.MODULE_TOO_MANY, declaration, name, ModuleLimits.MAX_MODULES)
                continue
            }
            val source = when (val lookup = resolver.resolve(name)) {
                is ModuleLookup.Found -> lookup.source
                ModuleLookup.NotFound -> null.also { report(DiagnosticCode.MODULE_NOT_FOUND, declaration, name) }
                ModuleLookup.Ambiguous -> null.also { report(DiagnosticCode.MODULE_AMBIGUOUS, declaration, name) }
                ModuleLookup.OutsideProject -> null.also { report(DiagnosticCode.MODULE_OUTSIDE_PROJECT, declaration, name) }
                is ModuleLookup.Unreadable -> null.also { report(DiagnosticCode.MODULE_UNREADABLE, declaration, name, lookup.reason) }
            }
            if (source == null) {
                states[name] = State.FAILED
                continue
            }
            states[name] = State.RESOLVING
            moduleSources[name] = source
            val unit = load(name, source)
            val program = unit.program
            if (program != null) {
                checkContents(program)
                visit(program.imports, path + name)
            }
            states[name] = State.DONE
            // import されるモジュールを先に並べる（決まった順にするため）
            resolved.add(unit)
        }
    }

    // モジュールに書けるのは import と関数だけ（第8.0段階）。トップレベルの文は実行の順番、クラスは型の名前の扱いが決まっていないため
    private fun checkContents(program: Program) {
        program.statements.forEach { report(DiagnosticCode.MODULE_TOP_LEVEL_STATEMENT, it.position) }
        program.classes.forEach { report(DiagnosticCode.MODULE_CLASS_UNSUPPORTED, it.position) }
    }

    private fun report(code: DiagnosticCode, declaration: ImportDeclaration, vararg arguments: Any) = report(code, declaration.namePosition, *arguments)

    private fun report(code: DiagnosticCode, position: SourcePosition, vararg arguments: Any) {
        val converted = arguments.map {
            when (it) {
                is Int -> DiagnosticArgument.IntArgument(it)
                else -> DiagnosticArgument.StringArgument.truncated(it.toString())
            }
        }
        diagnostics.report(Diagnostic(code, DiagnosticSeverity.ERROR, DiagnosticPhase.MODULE, position, converted))
    }
}

// 循環の経路に表示する、エントリーのファイルの名前（モジュール名が分からないとき）
private const val ENTRY = "<main>"
