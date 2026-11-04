package com.creatrbypc.createanalytical.cli

import com.creatrbypc.createanalytical.script.api.CreateScript
import com.creatrbypc.createanalytical.script.api.RunOptions
import com.creatrbypc.createanalytical.script.diagnostic.Diagnostic
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticArgument
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticSeverity
import com.creatrbypc.createanalytical.script.lexer.LexerLimits
import com.creatrbypc.createanalytical.script.module.ModuleDiagnostic
import com.creatrbypc.createanalytical.script.vm.VmState
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.PrintStream
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

    // projectRoot は --project-root の値（省略時は null。ファイルのあるディレクトリ: 第8.0段階）
    private class RunCommand(val file: String, val seed: Long, val instructionsPerTick: Int, val maxTicks: Int, val projectRoot: String?)

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
            values["--project-root"],
        )
    }

    private fun positive(values: Map<String, String>, option: String, default: Int, max: Int): Int {
        val text = values[option] ?: return default
        val value = text.toIntOrNull()
        if (value == null || value < 1 || value > max) throw UsageError("$option must be an integer between 1 and $max: $text")
        return value
    }

    // import（第8.0段階）のモジュールは、プロジェクトのルートの直下の モジュール名.cscr から読む（ProjectModules）。
    // import の無いプログラムの命令・診断は CreateScript.compile(source) と同じ
    private fun run(command: RunCommand): Int {
        val source = readSource(command.file)
        val project = openProject(command)
        val compiled = CreateScript.compile(source, project, project.entryModule)
        fun show(located: ModuleDiagnostic) = format(located.module?.let(project::displayName) ?: command.file, located.diagnostic)
        compiled.diagnostics.forEach { err.println(show(it)) }
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
            run.error?.let { err.println(show(compiled.locate(it))) }
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
        return when (val read = SourceFiles.read(path)) {
            is SourceFiles.Read.Text -> read.text
            SourceFiles.Read.TooLarge -> fail(ExitCode.INVALID_INPUT, "file too large: $file (at most ${LexerLimits.MAX_SOURCE_LENGTH} characters)")
            SourceFiles.Read.NotUtf8 -> fail(ExitCode.INVALID_INPUT, "file is not valid UTF-8: $file")
            SourceFiles.Read.Failed -> fail(ExitCode.NO_INPUT, "cannot read file: $file")
        }
    }

    // プロジェクトのルート（第8.0段階）。--project-root か、省略時はファイルのあるディレクトリ。ファイルはルートの中になければならない
    private fun openProject(command: RunCommand): ProjectModules {
        val file = Paths.get(command.file)
        val shown = command.projectRoot ?: file.parent?.toString()
        val root = try {
            (command.projectRoot?.let { Paths.get(it) } ?: file.toAbsolutePath().parent).toRealPath()
        } catch (error: InvalidPathException) {
            throw InputError(ExitCode.USAGE, "invalid project root: ${command.projectRoot}")
        } catch (error: IOException) {
            throw InputError(ExitCode.NO_INPUT, "project root not found: ${command.projectRoot}")
        }
        if (!Files.isDirectory(root)) throw InputError(ExitCode.NO_INPUT, "project root is not a directory: ${command.projectRoot}")
        // ファイルのあるディレクトリ（ファイル自身がシンボリックリンクでも、リンクの置き場所）。--project-root を指定したときは、その中でなければならない
        val directory = try {
            file.toAbsolutePath().parent.toRealPath()
        } catch (error: IOException) {
            throw InputError(ExitCode.NO_INPUT, "cannot read file: ${command.file}")
        }
        if (!directory.startsWith(root)) throw InputError(ExitCode.USAGE, "${command.file} is not inside the project root ${command.projectRoot}")
        return ProjectModules(root, shown, if (directory == root) file.fileName.toString().removeSuffix(EXTENSION) else null)
    }

    companion object {
        private const val EXTENSION = CliLimits.EXTENSION
        private val OPTIONS = setOf("--seed", "--instructions-per-tick", "--max-ticks", "--project-root")

        private val USAGE = """
            |Usage:
            |  createscript                      start the interactive mode
            |  createscript run <file$EXTENSION> [options]
            |  createscript --help
            |  createscript --version
            |
            |Runs a CreateScript program ($EXTENSION). println output goes to standard output;
            |diagnostics go to standard error. waitTicks counts logical ticks and does not sleep.
            |
            |Options for run:
            |  --seed <integer>                  initial random seed (default ${CliLimits.DEFAULT_SEED})
            |  --instructions-per-tick <1-${CliLimits.MAX_INSTRUCTIONS_PER_TICK}>  instructions per logical tick (default ${CliLimits.DEFAULT_INSTRUCTIONS_PER_TICK})
            |  --max-ticks <1-${CliLimits.MAX_MAX_TICKS}>           stop with an error after this many ticks (default ${CliLimits.DEFAULT_MAX_TICKS})
            |  --project-root <directory>        where "import name" finds name$EXTENSION (default: the directory of the file)
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
