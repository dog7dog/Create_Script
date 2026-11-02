package com.creatrbypc.createanalytical.cli

import com.creatrbypc.createanalytical.script.api.CreateScript
import com.creatrbypc.createanalytical.script.api.RunOptions
import com.creatrbypc.createanalytical.script.diagnostic.Diagnostic
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticArgument
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticSeverity
import com.creatrbypc.createanalytical.script.lexer.LexerLimits
import com.creatrbypc.createanalytical.script.vm.VmState
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

// 終了コード（README に記載）。使い方・入力の誤りは sysexits.h の EX_USAGE・EX_DATAERR・EX_NOINPUT と同じ番号
object ExitCode {
    const val OK = 0
    const val RUNTIME_ERROR = 1
    const val COMPILE_ERROR = 2
    const val MAX_TICKS_EXCEEDED = 3
    const val USAGE = 64
    const val INVALID_INPUT = 65
    const val NO_INPUT = 66
}

// CLI の設定の既定値と上限（README に記載）。Core の RunOptions とは別に、CLI だけで管理する
object CliLimits {
    const val EXTENSION = ".cscr"

    // 1 論理 tick の命令数。既定は Minecraft の FAST（1 台の上限）、上限は Minecraft のサーバー全体の 1 tick の予算と同じ
    const val DEFAULT_INSTRUCTIONS_PER_TICK = 128
    const val MAX_INSTRUCTIONS_PER_TICK = 4096

    // 論理 tick の数。上限まで進めても、命令は合計で約 41 億（4096 × 1,000,000）まで
    const val DEFAULT_MAX_TICKS = 100_000
    const val MAX_MAX_TICKS = 1_000_000

    const val DEFAULT_SEED = 0L

    // ソースは最大 4096 UTF-16 単位（Core の LexerLimits）。UTF-8 では 1 単位あたり最大 3 バイトなので、それを超えるファイルは読まずに拒否する
    const val MAX_SOURCE_BYTES = LexerLimits.MAX_SOURCE_LENGTH * 3
}

// input は対話モード（引数なし）で読む標準入力
class CreateScriptCli(private val out: PrintStream, private val err: PrintStream, private val input: InputStream = System.`in`) {
    private class UsageError(message: String) : Exception(message)

    // 入力のファイルを読めない（code は終了コード）
    private class InputError(val code: Int, message: String) : Exception(message)

    private class RunCommand(val file: String, val seed: Long, val instructionsPerTick: Int, val maxTicks: Int)

    fun execute(args: List<String>): Int {
        try {
            when (args.firstOrNull()) {
                null -> return repl()
                "--help", "-h" -> {
                    out.print(USAGE)
                    return ExitCode.OK
                }
                "--version" -> {
                    if (args.size > 1) throw UsageError("unexpected argument: ${args[1]}")
                    out.println("createscript ${BuildInfo.VERSION}")
                    return ExitCode.OK
                }
                "run" -> return run(parseRun(args.drop(1)))
                else -> throw UsageError(if (args[0].startsWith("-")) "unknown option: ${args[0]}" else "unknown command: ${args[0]}")
            }
        } catch (error: UsageError) {
            err.println("createscript: ${error.message}")
            err.println("Try 'createscript --help' for more information.")
            return ExitCode.USAGE
        } catch (error: InputError) {
            err.println("createscript: ${error.message}")
            return error.code
        }
    }

    // 標準入力が端末なら行編集（JLine）で、パイプ・ファイルなら行編集なしで読む（S5.3。README の 5.2）
    private fun repl(): Int {
        val terminal = if (input === System.`in`) TerminalLineInput.openSystem(out) else null
        (terminal ?: PlainLineInput(BufferedReader(input.reader(Charsets.UTF_8)), out)).use { return Repl(it, out, err).start() }
    }

    private fun parseRun(args: List<String>): RunCommand {
        var file: String? = null
        val values = HashMap<String, String>()
        var index = 0
        while (index < args.size) {
            val argument = args[index]
            if (argument.startsWith("-")) {
                if (argument !in OPTIONS) throw UsageError("unknown option: $argument")
                if (argument in values) throw UsageError("duplicate option: $argument")
                values[argument] = args.getOrNull(index + 1) ?: throw UsageError("missing value for $argument")
                index += 2
            } else {
                if (file != null) throw UsageError("unexpected argument: $argument")
                file = argument
                index++
            }
        }
        return RunCommand(
            file ?: throw UsageError("missing script file (expected a $EXTENSION file)"),
            values["--seed"]?.let { it.toLongOrNull() ?: throw UsageError("--seed must be an integer between ${Long.MIN_VALUE} and ${Long.MAX_VALUE}: $it") }
                ?: CliLimits.DEFAULT_SEED,
            positive(values, "--instructions-per-tick", CliLimits.DEFAULT_INSTRUCTIONS_PER_TICK, CliLimits.MAX_INSTRUCTIONS_PER_TICK),
            positive(values, "--max-ticks", CliLimits.DEFAULT_MAX_TICKS, CliLimits.MAX_MAX_TICKS),
        )
    }

    private fun positive(values: Map<String, String>, option: String, default: Int, max: Int): Int {
        val text = values[option] ?: return default
        val value = text.toIntOrNull()
        if (value == null || value < 1 || value > max) throw UsageError("$option must be an integer between 1 and $max: $text")
        return value
    }

    private fun run(command: RunCommand): Int {
        val source = readSource(command.file)
        val compiled = CreateScript.compile(source)
        compiled.diagnostics.forEach { err.println(format(command.file, it)) }
        if (compiled.diagnosticsTruncated) err.println("${command.file}: more diagnostics were omitted")
        val program = compiled.program ?: return ExitCode.COMPILE_ERROR

        val run = CreateScript.start(program, RunOptions(command.instructionsPerTick, command.seed)) { out.println(it) }
        var ticks = 0
        while (!run.isFinished && ticks < command.maxTicks) {
            run.tick()
            ticks++
        }
        out.flush()
        if (!run.isFinished) {
            err.println("${command.file}: stopped after ${command.maxTicks} ticks (--max-ticks); the program did not finish")
            return ExitCode.MAX_TICKS_EXCEEDED
        }
        if (run.state == VmState.ERROR) {
            run.error?.let { err.println(format(command.file, it)) }
            return ExitCode.RUNTIME_ERROR
        }
        return ExitCode.OK
    }

    // 拡張子はファイル名（パスの最後の要素）だけで判定する。ファイルの内容やディレクトリの名前は判定に使わない
    private fun readSource(file: String): String {
        fun fail(code: Int, message: String): Nothing = throw InputError(code, message)
        val path: Path = try {
            Paths.get(file)
        } catch (error: InvalidPathException) {
            return fail(ExitCode.USAGE, "invalid file name: $file")
        }
        val name = path.fileName?.toString().orEmpty()
        if (!name.endsWith(EXTENSION) || name.length == EXTENSION.length) {
            return fail(ExitCode.USAGE, "not a CreateScript file: $file (CreateScript files use the $EXTENSION extension)")
        }
        if (!Files.exists(path)) return fail(ExitCode.NO_INPUT, "file not found: $file")
        if (!Files.isRegularFile(path)) return fail(ExitCode.NO_INPUT, "not a regular file: $file")
        val bytes = try {
            Files.newInputStream(path).use { it.readNBytes(CliLimits.MAX_SOURCE_BYTES + 1) }
        } catch (error: IOException) {
            return fail(ExitCode.NO_INPUT, "cannot read file: $file")
        }
        if (bytes.size > CliLimits.MAX_SOURCE_BYTES) {
            return fail(ExitCode.INVALID_INPUT, "file too large: $file (at most ${LexerLimits.MAX_SOURCE_LENGTH} characters)")
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: CharacterCodingException) {
            fail(ExitCode.INVALID_INPUT, "file is not valid UTF-8: $file")
        }
    }

    companion object {
        private const val EXTENSION = CliLimits.EXTENSION
        private val OPTIONS = setOf("--seed", "--instructions-per-tick", "--max-ticks")

        private val USAGE = """
            |Usage:
            |  createscript                      start the interactive mode
            |  createscript run <file$EXTENSION> [options]
            |  createscript --help
            |  createscript --version
            |
            |Runs a CreateScript program ($EXTENSION) without Minecraft. println output goes to standard output;
            |diagnostics go to standard error. waitTicks counts logical ticks and does not sleep.
            |
            |Options for run:
            |  --seed <integer>                  initial random seed (default ${CliLimits.DEFAULT_SEED})
            |  --instructions-per-tick <1-${CliLimits.MAX_INSTRUCTIONS_PER_TICK}>  instructions per logical tick (default ${CliLimits.DEFAULT_INSTRUCTIONS_PER_TICK})
            |  --max-ticks <1-${CliLimits.MAX_MAX_TICKS}>           stop with an error after this many ticks (default ${CliLimits.DEFAULT_MAX_TICKS})
            |
            |Exit codes:
            |  0 finished, 1 runtime error, 2 compile error, 3 --max-ticks reached,
            |  64 usage error, 65 invalid input file, 66 input file not found or unreadable
            |""".trimMargin()

        // file:line:column: error CS_XXX (arguments)
        fun format(file: String, diagnostic: Diagnostic): String {
            val severity = if (diagnostic.severity == DiagnosticSeverity.ERROR) "error" else "warning"
            val arguments = diagnostic.arguments.joinToString(", ") {
                when (it) {
                    is DiagnosticArgument.IntArgument -> it.value.toString()
                    is DiagnosticArgument.StringArgument -> "\"${it.value}\""
                }
            }
            val position = diagnostic.position
            return "$file:${position.line}:${position.column}: $severity ${diagnostic.code.id}" + if (arguments.isEmpty()) "" else " ($arguments)"
        }
    }
}
