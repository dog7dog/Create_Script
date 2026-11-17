package com.creatrbypc.createanalytical.script.module

import com.creatrbypc.createanalytical.script.InternalCreateScriptApi
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
@InternalCreateScriptApi
public object ModuleLimits {
    // エントリーのファイルを除くモジュールの数の既定（埋め込み先）。独立版は CompileOptions.STANDALONE で広げる（第8.0.1段階）。
    // 1 つのソースの上限（LexerLimits）はファイルごとに、命令数などの上限（VmLimits）はプログラム全体に適用する
    public const val MAX_MODULES: Int = 16
}

/** What a host found for a module name; returned by [ModuleResolver] (and wrapped by [ScopedModuleLookup.Failed]). */
public sealed interface ModuleLookup {
    /**
     * The module exists.
     *
     * @property source the text of the module, read by the host from a file it checked.
     */
    public data class Found(val source: String) : ModuleLookup

    /** No such module (`CS_MODULE_NOT_FOUND`). */
    public data object NotFound : ModuleLookup

    /** More than one file could be meant, for example names that differ only in case (`CS_MODULE_AMBIGUOUS`). */
    public data object Ambiguous : ModuleLookup

    /** The file is outside the project, for example through a symbolic link (`CS_MODULE_OUTSIDE_PROJECT`). */
    public data object OutsideProject : ModuleLookup

    /**
     * The file cannot be read.
     *
     * @property reason a short explanation shown in the diagnostic (cut to 64 UTF-16 code units).
     */
    public data class Unreadable(val reason: String) : ModuleLookup
}

/**
 * Gives the source of a module to the compiler; the Core never reads files itself. Called at most once per module
 * name in one compilation, on the thread that compiles. [resolve] gets a name that is one identifier (already
 * checked by the parser). An exception it throws is passed on to the caller of `compile`.
 */
public fun interface ModuleResolver {
    /** Looks up the module [name]. */
    public fun resolve(name: String): ModuleLookup
}

/**
 * Decides which file may import which module. Asked for every `import` declaration before the resolver, also when
 * the module was already resolved for another file; `false` gives `CS_MODULE_NOT_VISIBLE`.
 */
public fun interface ModuleAccess {
    /** Whether the module [importer] (`null` for the entry file) may import [module]. */
    public fun allows(importer: String?, module: String): Boolean

    public companion object {
        /** Allows every import, as `compile` without a [ModuleAccess] does. */
        public val ALL: ModuleAccess = ModuleAccess { _, _ -> true }
    }
}

/**
 * Gives modules to `CreateScript.compileScoped`, per importing file. Called at most once per importing module and
 * name. The [ScopedModuleLookup.Found.id] it returns identifies the module: the same id is the same module (read
 * once), so modules with the same name get different ids to be used together. Cycles are found by id: returning the
 * entry file's id for its own name gives `CS_MODULE_CYCLE`. An exception it throws is passed on to the caller.
 */
public fun interface ScopedModuleResolver {
    /** Looks up [name] as written in an `import` of the module with the id [importer] (`null` for the entry file). */
    public fun resolve(importer: String?, name: String): ScopedModuleLookup
}

/** What a [ScopedModuleResolver] found. */
public sealed interface ScopedModuleLookup {
    /**
     * The module and its id. The id has 1 to 128 characters: letters, digits and `_ / : @ + -`, starting with a
     * letter, digit or `_` (no `.`, which separates module and function names).
     *
     * @property id the id of the module.
     * @property source the text of the module.
     * @throws IllegalArgumentException if [id] is not valid ([isValidId]).
     */
    public data class Found(val id: String, val source: String) : ScopedModuleLookup {
        init {
            require(isValidId(id)) { "invalid module id: $id" }
        }
    }

    /**
     * The module cannot be used.
     *
     * @property lookup the reason, as a [ModuleLookup] other than [ModuleLookup.Found].
     * @throws IllegalArgumentException if [lookup] is [ModuleLookup.Found].
     */
    public data class Failed(val lookup: ModuleLookup) : ScopedModuleLookup {
        init {
            require(lookup !is ModuleLookup.Found) { "Failed needs a failure, not Found" }
        }
    }

    /** The module exists but this file may not import it (`CS_MODULE_NOT_VISIBLE`), for example a private module. */
    public data object NotVisible : ScopedModuleLookup

    public companion object {
        private val ID = Regex("[A-Za-z0-9_][A-Za-z0-9_/:@+-]{0,127}")

        /** Whether [id] can be used as [Found.id]. */
        public fun isValidId(id: String): Boolean = ID.matches(id)
    }
}

/**
 * A diagnostic and the file it belongs to.
 *
 * @property module the module name of the file (its id with `compileScoped`), or `null` for the entry file.
 * @property diagnostic the diagnostic; its position is within that file.
 */
public data class ModuleDiagnostic(val module: String?, val diagnostic: Diagnostic)

/**
 * The result of compiling a program with `import`.
 *
 * @property program the compiled program, or `null` when there is an error.
 * @property diagnostics errors and warnings with their files, at most 16.
 * @property diagnosticsTruncated `true` when more diagnostics were dropped.
 * @property hasErrors `true` when there was an error, also one that was dropped.
 * @property modules the modules that were resolved, imported modules before the modules that import them.
 */
public class ModuleCompileResult internal constructor(
    public val program: CompiledProgram?,
    public val diagnostics: List<ModuleDiagnostic>,
    public val diagnosticsTruncated: Boolean,
    public val hasErrors: Boolean,
    public val modules: List<String>,
    private val files: List<SourceFile>,
    private val sources: SourceSet,
) {
    // コンパイルに使ったソース（エントリーと、読めたモジュール）
    internal class SourceSet(val entry: String, val modules: Map<String, String>)

    /**
     * The SHA-256 of the entry source and all modules that were read ([ProgramFingerprint.ofModules]); for a
     * program without `import` it equals [ProgramFingerprint.ofSource] of the entry source. Hosts that save runs
     * compare this and [ProgramFingerprint.ofProgram], so a state is not resumed after only a module changed.
     * A new array on each call.
     */
    public val sourceFingerprint: ByteArray get() = ProgramFingerprint.ofModules(sources.entry, sources.modules)

    // ファイルの名前と、プログラム全体での位置（offset）の始まり
    internal class SourceFile(val module: String?, val base: Int)

    /**
     * The file of a diagnostic that has a position in the whole program, such as a runtime error from
     * `ScriptRun.error`, with the offset made relative to that file (line and column are already per file).
     */
    public fun locate(diagnostic: Diagnostic): ModuleDiagnostic = locate(files, diagnostic)

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
// - compileScoped（ScopedModuleResolver）では、モジュールを ID で区別し、ファイルごとに import した名前 → ID を記録して、意味解析で読み替える。
//   ModuleResolver を使うコンパイルは、名前がそのまま ID で（読み替えなし）、命令・診断・ハッシュはこれまでと同じ
internal class ModuleCompiler private constructor(
    private val resolver: ModuleResolver,
    private val entryModule: String?,
    private val options: CompileOptions,
    private val access: ModuleAccess,
    private val scoped: ScopedModuleResolver?,
) {
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

    // compileScoped のときだけ: ファイルのモジュールの ID（エントリーは null）→ import した名前 → ID
    // （resolver の問い合わせは、各ファイルの import をたどるときに 1 回ずつ。同じファイルの同じ import は Parser が 1 つにまとめる）
    private val aliases = HashMap<String?, HashMap<String, String>>()

    companion object {
        fun compile(source: String, resolver: ModuleResolver, entryModule: String?, options: CompileOptions, access: ModuleAccess = ModuleAccess.ALL): ModuleCompileResult =
            ModuleCompiler(resolver, entryModule, options, access, null).run(source)

        fun compileScoped(source: String, resolver: ScopedModuleResolver, entryModule: String?, options: CompileOptions): ModuleCompileResult {
            require(entryModule == null || ScopedModuleLookup.isValidId(entryModule)) { "invalid entry module id: $entryModule" }
            return ModuleCompiler({ ModuleLookup.NotFound }, entryModule, options, ModuleAccess.ALL, resolver).run(source)
        }
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
            Compiler.compile(SemanticAnalyzer.analyze(ParseResult(merged, diagnostics.diagnostics, diagnostics.isTruncated, diagnostics.hasErrors), options.maxPropertyTypeChain, aliases), options)
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
            // モジュールの ID。既存の compile では名前のまま。compileScoped では、import 元ごとに resolver が決める（循環・重複の確認より前）
            val id: String
            var scopedSource: String? = null
            if (scoped == null) {
                // import できる範囲（S8.2 Phase 4。ModuleAccess.ALL では常に許すので、既存の compile の診断・順序は変わらない）
                if (!access.allows(top.unit?.module, name)) {
                    report(DiagnosticCode.MODULE_NOT_VISIBLE, declaration, name)
                    continue
                }
                id = name
            } else {
                val importer = top.unit?.module
                when (val lookup = scoped.resolve(importer, name)) {
                    is ScopedModuleLookup.Found -> {
                        id = lookup.id
                        scopedSource = lookup.source
                    }
                    ScopedModuleLookup.NotVisible -> {
                        report(DiagnosticCode.MODULE_NOT_VISIBLE, declaration, name)
                        continue
                    }
                    is ScopedModuleLookup.Failed -> {
                        reportFailure(lookup.lookup, declaration, name)
                        continue
                    }
                }
                aliases.getOrPut(importer) { HashMap() }[name] = id
            }
            val cycleStart = onPath[id]
            if (cycleStart != null) {
                report(DiagnosticCode.MODULE_CYCLE, declaration, cyclePath(path, cycleStart, id))
                continue
            }
            if (states[id] != null) continue
            if (states.size >= options.maxModules) {
                report(DiagnosticCode.MODULE_TOO_MANY, declaration, name, options.maxModules)
                continue
            }
            val source = scopedSource ?: when (val lookup = resolver.resolve(name)) {
                is ModuleLookup.Found -> lookup.source
                else -> null.also { reportFailure(lookup, declaration, name) }
            }
            if (source == null) {
                states[id] = State.FAILED
                continue
            }
            // すべてのファイルの長さの合計の上限（第8.0.1段階。1 つのファイルの上限とは別に、プログラム全体の大きさを抑える）
            if (source.length > options.maxTotalSourceLength - totalSourceLength) {
                report(DiagnosticCode.MODULE_PROGRAM_TOO_LARGE, declaration, name, "characters", options.maxTotalSourceLength)
                states[id] = State.FAILED
                continue
            }
            val unit = load(id, source)
            if (unit == null) {
                // トークン数の合計の上限（第8.0.1段階）。構文解析の前に止め、AST を作らない
                report(DiagnosticCode.MODULE_PROGRAM_TOO_LARGE, declaration, name, "tokens", options.maxTotalTokens)
                states[id] = State.FAILED
                continue
            }
            states[id] = State.RESOLVING
            moduleSources[id] = source
            val program = unit.program
            if (program == null) {
                states[id] = State.DONE
                resolved.add(unit)
                continue
            }
            checkContents(program)
            onPath[id] = path.size
            path.add(id)
            stack.add(Pending(unit, program.imports.iterator()))
        }
    }

    private fun reportFailure(lookup: ModuleLookup, declaration: ImportDeclaration, name: String) {
        when (lookup) {
            is ModuleLookup.Found -> error("not a failure")
            ModuleLookup.NotFound -> report(DiagnosticCode.MODULE_NOT_FOUND, declaration, name)
            ModuleLookup.Ambiguous -> report(DiagnosticCode.MODULE_AMBIGUOUS, declaration, name)
            ModuleLookup.OutsideProject -> report(DiagnosticCode.MODULE_OUTSIDE_PROJECT, declaration, name)
            is ModuleLookup.Unreadable -> report(DiagnosticCode.MODULE_UNREADABLE, declaration, name, lookup.reason)
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
