package com.creatrbypc.createanalytical.cli

import com.creatrbypc.createanalytical.script.api.CreateScript
import com.creatrbypc.createanalytical.script.api.RunOptions
import com.creatrbypc.createanalytical.script.api.ScriptRun
import com.creatrbypc.createanalytical.script.ast.ExpressionStatement
import com.creatrbypc.createanalytical.script.compiler.CompileResult
import com.creatrbypc.createanalytical.script.diagnostic.Diagnostic
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticSeverity
import com.creatrbypc.createanalytical.script.diagnostic.SourcePosition
import com.creatrbypc.createanalytical.script.lexer.Lexer
import com.creatrbypc.createanalytical.script.lexer.TokenType
import com.creatrbypc.createanalytical.script.parser.Parser
import com.creatrbypc.createanalytical.script.vm.CompiledProgram
import com.creatrbypc.createanalytical.script.vm.Constant
import com.creatrbypc.createanalytical.script.vm.HostFunction
import com.creatrbypc.createanalytical.script.vm.ObjectSnapshot
import com.creatrbypc.createanalytical.script.vm.Opcode
import com.creatrbypc.createanalytical.script.vm.Value
import com.creatrbypc.createanalytical.script.vm.VmSnapshot
import com.creatrbypc.createanalytical.script.vm.VmState
import java.io.PrintStream

// 対話モード（第3.12段階 S5.1）。入力を1つずつ実行し、変数・関数・クラス・オブジェクト・乱数の状態を次の入力へ引き継ぐ。
//
// 仕組み: それまでに実行した入力（セッション）の後ろに新しい入力をつないだソースをコンパイルし、前の入力の終わりで取った VM の
// スナップショットから続きを実行する（前の入力は実行し直さない）。各入力の後ろには区切りの waitTicks(0) を付け、VM がそこで
// 待機した時点のスナップショットを次の入力の開始点にする。スナップショットは、文字列の定数の番号を新しいプログラムの番号へ
// 付け替えてから、ゲームの保存・復元と同じ検証（SnapshotVerifier）を通して再開する。検証に通らない入力・エラーになった入力・
// 実行の上限に達した入力は、セッションに加えない（セッションはその入力の前の状態のまま続く）
class Repl(private val input: LineInput, private val out: PrintStream, private val err: PrintStream) {
    private val options = RunOptions(CliLimits.DEFAULT_INSTRUCTIONS_PER_TICK, CliLimits.DEFAULT_SEED)

    // セッションのソース・そのプログラム・最後の入力の区切りで待機した時点のスナップショット（最初の入力の前はすべて空）
    private var session = ""
    private var program: CompiledProgram? = null
    private var snapshot: VmSnapshot? = null

    // 直前の区切りの waitTicks の、セッションのソース上の位置
    private var markOffset = -1

    fun start(): Int {
        out.println("CreateScript ${BuildInfo.VERSION}")
        out.println("Type \"help\" for help, \"exit\" to quit.")
        val pending = StringBuilder()
        while (true) {
            val line = when (val event = input.read(if (pending.isEmpty()) ">>> " else "... ")) {
                is InputEvent.Line -> event.text
                // Ctrl+C（S5.3）。入力の途中なら、その入力を実行せずに捨て、次の ">>> " を表示する
                InputEvent.Cancel -> {
                    pending.setLength(0)
                    continue
                }
                InputEvent.End -> break
            }
            if (pending.isEmpty()) {
                when (line.trim()) {
                    "" -> continue
                    "exit", "quit" -> return ExitCode.OK
                    "help" -> {
                        out.print(HELP)
                        continue
                    }
                }
            }
            if (pending.isNotEmpty()) pending.append('\n')
            pending.append(line)
            if (isIncomplete(pending.toString())) continue
            evaluate(pending.toString())
            pending.setLength(0)
        }
        // EOF（Ctrl+D）。入力の途中なら、その入力は実行しない
        return ExitCode.OK
    }

    // 括弧（{ ( [）が閉じていなければ、入力の続きを待つ
    private fun isIncomplete(text: String): Boolean {
        var depth = 0
        for (token in Lexer.tokenize(text).tokens) {
            when (token.type) {
                TokenType.LEFT_BRACE, TokenType.LEFT_PAREN, TokenType.LEFT_BRACKET -> depth++
                TokenType.RIGHT_BRACE, TokenType.RIGHT_PAREN, TokenType.RIGHT_BRACKET -> depth--
                else -> {}
            }
        }
        return depth > 0
    }

    // 入力をセッションのソースにつないだもの。prefix は入力の前に付けた文字列（式の値を表示する println( など）
    private class Candidate(val text: String, val prefix: String, val suffix: String, val session: String) {
        val inputOffset = session.length + prefix.length
        val inputLine = session.count { it == '\n' } + prefix.count { it == '\n' }
        val markOffset = inputOffset + text.length + suffix.length + 1
        val source = session + prefix + text + suffix + "\n" + MARK + "\n"
    }

    private fun evaluate(text: String) {
        val raw = Candidate(text, "", "", session)
        // 1 つの式だけの入力は、値を表示する（Unit の式は println できないため、コンパイルできなければそのまま実行する）
        val candidates = if (isSingleExpression(text)) listOf(Candidate(text, "println(\n", "\n)", session), raw) else listOf(raw)
        var chosen: Candidate? = null
        var compiled: CompileResult? = null
        for (candidate in candidates) {
            val result = CreateScript.compile(candidate.source)
            if (result.program != null) {
                chosen = candidate
                compiled = result
                break
            }
            if (candidate === raw) compiled = result
        }
        // コンパイルできたなら、その候補の警告。できなければ、入力そのままのときの診断
        val outside = report(chosen ?: raw, compiled!!.diagnostics)
        // セッション全体の上限（ソースの長さなど）は、入力の外の位置で報告される
        if (chosen == null && outside) err.println("(the session has reached a CreateScript limit; start createscript again for a new session)")
        val candidate = chosen ?: return
        val next = compiled.program!!

        val run = startRun(candidate, next) ?: run {
            err.println("createscript: this input cannot be added to the session")
            return
        }
        val boundary = markPc(next, candidate.markOffset)
        var ticks = 0
        var skipChecks = 0
        while (!run.isFinished && ticks < CliLimits.DEFAULT_MAX_TICKS) {
            run.tick()
            ticks++
            if (run.state != VmState.WAITING) continue
            if (skipChecks > 0) {
                skipChecks--
                continue
            }
            val state = run.snapshot() ?: continue
            if (state.pc == boundary) {
                session = candidate.source
                program = next
                snapshot = state
                markOffset = candidate.markOffset
                return
            }
            // 入力の中の waitTicks(n)。残りの tick の間は区切りに着かない
            skipChecks = state.waitRemaining
        }
        out.flush()
        when {
            run.state == VmState.ERROR -> run.error?.let { report(candidate, listOf(it)) }
            !run.isFinished -> err.println("<input>: stopped after ${CliLimits.DEFAULT_MAX_TICKS} ticks; the input did not finish")
        }
        err.println("(this input was not added to the session)")
    }

    private fun isSingleExpression(text: String): Boolean {
        val parsed = Parser.parse(Lexer.tokenize(text)).program ?: return false
        return parsed.functions.isEmpty() && parsed.classes.isEmpty() && parsed.statements.singleOrNull() is ExpressionStatement
    }

    private fun startRun(candidate: Candidate, next: CompiledProgram): ScriptRun? {
        val print: (String) -> Unit = { out.println(it) }
        val previous = program ?: return CreateScript.start(next, options, print)
        val rebased = rebase(snapshot!!, previous, next) ?: return null
        return CreateScript.resume(next, rebased, options, print)
    }

    // 区切りの waitTicks の次の命令の位置（スナップショットの pc）
    private fun markPc(program: CompiledProgram, offset: Int): Int =
        program.instructions.indexOfFirst {
            it.opcode == Opcode.CALL_HOST && it.operand == HostFunction.WAIT_TICKS.id && it.position.offset in offset until offset + MARK.length
        } + 1

    // 前のプログラムで取ったスナップショットを、セッションの後ろに入力をつないだプログラムへ移す。前の区切りの位置が同じで、
    // 文字列の定数が新しいプログラムにもあることを確かめ、文字列の定数の番号を付け替え、新しい変数（未初期化）を足す
    private fun rebase(state: VmSnapshot, previous: CompiledProgram, next: CompiledProgram): VmSnapshot? {
        if (markPc(next, markOffset) != state.pc || next.variableCount < state.variables.size) return null
        val strings = HashMap<Int, Int>()
        for ((index, constant) in previous.constants.withIndex()) {
            if (constant !is Constant.StringConstant) continue
            val moved = next.constants.indexOf(constant)
            if (moved < 0) return null
            strings[index] = moved
        }
        fun move(value: Value): Value = if (value is Value.StringValue) Value.StringValue(strings[value.constantIndex] ?: -1) else value
        return state.copy(
            stack = state.stack.map(::move),
            variables = state.variables.map { it?.let(::move) } + List(next.variableCount - state.variables.size) { null },
            callStack = state.callStack.map { frame -> frame.copy(locals = frame.locals.map { it?.let(::move) }) },
            objects = state.objects.map { entry ->
                ObjectSnapshot(entry.id, entry.classId, entry.fields.map { it?.let(::move) }, entry.list?.let { it.copy(elements = it.elements.map(::move)) })
            },
        )
    }

    // 診断を入力の中の位置（<input>:行:列）で表示する。セッションの前の入力にある警告は、その入力のときに表示済みのため表示しない。
    // 入力の外の位置のエラーがあれば true
    private fun report(candidate: Candidate, diagnostics: List<Diagnostic>): Boolean {
        var outside = false
        for (diagnostic in diagnostics) {
            val offset = diagnostic.position.offset
            val inInput = offset >= candidate.inputOffset && offset <= candidate.inputOffset + candidate.text.length
            if (!inInput && diagnostic.severity == DiagnosticSeverity.WARNING) continue
            if (!inInput) outside = true
            val position = diagnostic.position
            val shown = if (inInput) diagnostic.copy(position = SourcePosition(offset - candidate.inputOffset, position.line - candidate.inputLine, position.column)) else diagnostic
            err.println(CreateScriptCli.format(if (inInput) "<input>" else "<session>", shown))
        }
        return outside
    }

    companion object {
        // 入力の区切り（S5.1。セッションのソースの中だけで使い、入力の中には書かない）
        private const val MARK = "waitTicks(0)"

        private val HELP = """
            |CreateScript interactive mode
            |  Type a statement, a declaration, or an expression. The value of an expression is printed.
            |  Variables, functions, classes, and objects are kept for later inputs.
            |  An input continues on "..." lines until its brackets { ( [ are closed.
            |  An input with an error, or that does not finish within ${CliLimits.DEFAULT_MAX_TICKS} ticks, is not kept.
            |Commands
            |  help   show this help
            |  exit   quit (also: quit, Ctrl+D)
            |""".trimMargin()
    }
}
