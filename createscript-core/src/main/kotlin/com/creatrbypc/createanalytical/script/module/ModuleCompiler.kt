package com.creatrbypc.createanalytical.script.module

import com.creatrbypc.createanalytical.script.ast.ImportDeclaration
import com.creatrbypc.createanalytical.script.ast.Program
import com.creatrbypc.createanalytical.script.compiler.CompileOptions
import com.creatrbypc.createanalytical.script.compiler.Compiler
import com.creatrbypc.createanalytical.script.diagnostic.Diagnostic
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticArgument
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticCode
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticCollector
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticLimits
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
    // エントリーのファイルを除くモジュールの数の既定（埋め込み先）。独立版は CompileOptions.STANDALONE で広げる（第8.0.1段階）。
    // 1 つのソースの上限（LexerLimits）はファイルごとに、命令数などの上限（VmLimits）はプログラム全体に適用する
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
        // files は base の昇順。base が offset 以下の最後のファイル（二分探索。ファイルが多くても診断 1 つあたりの手間は小さい）
        fun locate(files: List<SourceFile>, diagnostic: Diagnostic): ModuleDiagnostic {
            val position = diagnostic.position
            val found = files.binarySearch { it.base.compareTo(position.offset) }
            val file = files[if (found >= 0) found else maxOf(0, -found - 2)]
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
    private var totalSourceLength = 0
    private var totalTokens = 0

    companion object {
        fun compile(source: String, resolver: ModuleResolver, entryModule: String?, options: CompileOptions): ModuleCompileResult =
            ModuleCompiler(resolver, entryModule, options).run(source)
    }

    private fun run(source: String): ModuleCompileResult {
        val entry = load(null, source)!!
        entry.program?.let { visitAll(it.imports) }
        val main = entry.program
        val compiled = if (main == null || diagnostics.hasErrors) {
            null
        } else {
            val functions = main.functions + resolved.flatMap { unit -> unit.program!!.functions.map { it.copy(name = "${unit.module}.${it.name}") } }
            val merged = Program(main.statements, functions, main.classes)
            Compiler.compile(SemanticAnalyzer.analyze(ParseResult(merged, diagnostics.diagnostics, diagnostics.isTruncated, diagnostics.hasErrors), options.maxPropertyTypeChain), options)
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
    // トークン数の合計が上限を超えるモジュールは null（エントリーのファイルは 1 つのファイルの上限以下で、合計の上限を超えない）
    private fun load(module: String?, source: String): SourceUnit? {
        val lexed = Lexer.tokenize(source, options.maxSourceLength, options.maxTokens)
        val tokens = lexed.tokens.size - 1
        if (module != null && tokens > options.maxTotalTokens - totalTokens) return null
        totalTokens += tokens
        val base = nextBase
        nextBase += source.length + 1
        totalSourceLength += source.length
        files.add(ModuleCompileResult.SourceFile(module, base))
        val parsed = Parser.parse(shift(lexed, base), options.maxNodes)
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

    // import を深さ優先でたどる。モジュールの数が多くても JVM のスタックを使わないよう、再帰せずに、たどっている途中のファイル（エントリーから順）を
    // 積んで処理する（第8.0.1段階。たどる順・解決の順・診断は再帰で書いた第8.0段階と同じ）。path はその名前の並び（エントリーは entryModule か "<main>"）、
    // onPath は path の中の位置（循環の検出に使う）
    private class Pending(val unit: SourceUnit?, val imports: Iterator<ImportDeclaration>)

    private fun visitAll(entryImports: List<ImportDeclaration>) {
        val stack = arrayListOf(Pending(null, entryImports.iterator()))
        val path = arrayListOf(entryModule ?: ENTRY)
        val onPath = HashMap<String, Int>().also { map -> entryModule?.let { map[it] = 0 } }
        while (stack.isNotEmpty()) {
            val top = stack.last()
            if (!top.imports.hasNext()) {
                stack.removeAt(stack.lastIndex)
                val unit = top.unit ?: continue
                onPath.remove(unit.module)
                path.removeAt(path.lastIndex)
                states[unit.module!!] = State.DONE
                // import されるモジュールを先に並べる（決まった順にするため）
                resolved.add(unit)
                continue
            }
            val declaration = top.imports.next()
            val name = declaration.name
            if (name in BuiltinFunctions.NAMES) {
                report(DiagnosticCode.MODULE_BUILTIN_NAME, declaration, name)
                continue
            }
            val cycleStart = onPath[name]
            if (cycleStart != null) {
                report(DiagnosticCode.MODULE_CYCLE, declaration, cyclePath(path, cycleStart, name))
                continue
            }
            if (states[name] != null) continue
            if (states.size >= options.maxModules) {
                report(DiagnosticCode.MODULE_TOO_MANY, declaration, name, options.maxModules)
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
            // すべてのファイルの長さの合計の上限（第8.0.1段階。1 つのファイルの上限とは別に、プログラム全体の大きさを抑える）
            if (source.length > options.maxTotalSourceLength - totalSourceLength) {
                report(DiagnosticCode.MODULE_PROGRAM_TOO_LARGE, declaration, name, "characters", options.maxTotalSourceLength)
                states[name] = State.FAILED
                continue
            }
            val unit = load(name, source)
            if (unit == null) {
                // トークン数の合計の上限（第8.0.1段階）。構文解析の前に止め、AST を作らない
                report(DiagnosticCode.MODULE_PROGRAM_TOO_LARGE, declaration, name, "tokens", options.maxTotalTokens)
                states[name] = State.FAILED
                continue
            }
            states[name] = State.RESOLVING
            moduleSources[name] = source
            val program = unit.program
            if (program == null) {
                states[name] = State.DONE
                resolved.add(unit)
                continue
            }
            checkContents(program)
            onPath[name] = path.size
            path.add(name)
            stack.add(Pending(unit, program.imports.iterator()))
        }
    }

    // 循環の経路 "a -> b -> a"。診断の引数は 64 単位までなので、長い経路は先頭から 64 単位を超えたところまでだけ作る
    private fun cyclePath(path: List<String>, start: Int, name: String): String {
        val text = StringBuilder()
        for (index in start until path.size) {
            text.append(path[index]).append(" -> ")
            if (text.length > DiagnosticLimits.MAX_STRING_ARGUMENT_LENGTH) return text.toString()
        }
        return text.append(name).toString()
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
