package com.creatrbypc.createanalytical.cli

import com.creatrbypc.createanalytical.install.InstallFailure
import com.creatrbypc.createanalytical.install.InstallLayout
import com.creatrbypc.createanalytical.install.InstalledPackages
import com.creatrbypc.createanalytical.install.VerifiedPackage
import com.creatrbypc.createanalytical.script.api.CreateScript
import com.creatrbypc.createanalytical.script.api.RunOptions
import com.creatrbypc.createanalytical.script.compiler.CompileOptions
import com.creatrbypc.createanalytical.script.diagnostic.Diagnostic
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticArgument
import com.creatrbypc.createanalytical.script.diagnostic.DiagnosticSeverity
import com.creatrbypc.createanalytical.script.module.ModuleCompileResult
import com.creatrbypc.createanalytical.script.module.ModuleDiagnostic
import com.creatrbypc.createanalytical.script.vm.VmState
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
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

    // 通信できない（sysexits.h の EX_UNAVAILABLE。createscript install: S8.2）
    const val UNAVAILABLE = 69

    // ファイルを作れない（既にある・書けない: sysexits.h の EX_CANTCREAT。createscript init: S8.1）
    const val CANNOT_CREATE = 73

    // ほかのインストールが動いている（sysexits.h の EX_TEMPFAIL。createscript install: S8.2）
    const val TEMPORARY_FAILURE = 75
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

    // コンパイルの設定（第8.0.1段階）。独立版のコマンドなので、Core の CompileOptions.STANDALONE（命令数の上限なし・広い入力の上限）を使う
    val COMPILE_OPTIONS = CompileOptions.STANDALONE

    // 1 つのファイルのソースは最大 COMPILE_OPTIONS.maxSourceLength UTF-16 単位。UTF-8 では 1 単位あたり最大 3 バイトなので、それを超えるファイルは読まずに拒否する
    val MAX_SOURCE_LENGTH = COMPILE_OPTIONS.maxSourceLength
    val MAX_SOURCE_BYTES = MAX_SOURCE_LENGTH * 3
}

// input は対話モード（引数なし）で読む標準入力
class CreateScriptCli(private val out: PrintStream, private val err: PrintStream, private val input: InputStream = System.`in`) {
    // run のプロジェクトモードで install.lock の共有ロックを試す方法（S8.2 L-8。テストでは、ロックを扱えない場合を渡す）
    internal var runLocker: RunLock.Locker = RunLock.DEFAULT

    // install が動いているときに run が待つ時間（S8.2 L-8。テストでは短くする）
    internal var runLockWait: RunLock.Wait = RunLock.Wait.RUN

    private class UsageError(message: String) : Exception(message)

    // 入力のファイルを読めない（code は終了コード）
    private class InputError(val code: Int, message: String) : Exception(message)

    // projectRoot は --project-root の値（省略時は null。ファイルのあるディレクトリ: 第8.0段階）。
    // file が null かディレクトリなら、プロジェクトモード（S8.2 Phase 4）
    private class RunCommand(val file: String?, val seed: Long, val instructionsPerTick: Int, val maxTicks: Int, val projectRoot: String?)

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
                "init", "manifest", "lock", "pack" -> return project(args)
                "install" -> return try {
                    InstallCommands(out, err).install(args.drop(1))
                } catch (error: InstallCommands.Usage) {
                    throw UsageError(error.message ?: "invalid arguments")
                }
                else -> throw UsageError(if (args[0].startsWith("-")) "unknown option: ${args[0]}" else "unknown command: ${args[0]}")
            }
        } catch (error: UsageError) {
            err.println("createscript: ${error.message}")
            err.println("Try 'createscript --help' for more information.")
            return ExitCode.USAGE
        } catch (error: InputError) {
            err.println("createscript: ${error.message}")
            return error.code
        } catch (error: ProjectCommands.Failure) {
            err.println("createscript: ${error.message}")
            return error.code
        }
    }

    // プロジェクトのファイルのコマンド（S8.1）: init [ディレクトリ] [--name 名前]、manifest check [ディレクトリ]、lock check [ディレクトリ]
    private fun project(args: List<String>): Int {
        val commands = ProjectCommands(out, err)
        val rest = args.drop(1)
        return when (args[0]) {
            "pack" -> {
                var directory: String? = null
                var output: String? = null
                var index = 0
                while (index < rest.size) {
                    val argument = rest[index]
                    when {
                        argument == "--output" -> {
                            if (output != null) throw UsageError("duplicate option: --output")
                            output = rest.getOrNull(index + 1) ?: throw UsageError("missing value for --output")
                            index += 2
                        }
                        argument.startsWith("-") -> throw UsageError("unknown option: $argument")
                        directory != null -> throw UsageError("unexpected argument: $argument")
                        else -> {
                            directory = argument
                            index++
                        }
                    }
                }
                commands.pack(directory, output)
            }
            "init" -> {
                var directory: String? = null
                var name: String? = null
                var index = 0
                while (index < rest.size) {
                    val argument = rest[index]
                    when {
                        argument == "--name" -> {
                            if (name != null) throw UsageError("duplicate option: --name")
                            name = rest.getOrNull(index + 1) ?: throw UsageError("missing value for --name")
                            index += 2
                        }
                        argument.startsWith("-") -> throw UsageError("unknown option: $argument")
                        directory != null -> throw UsageError("unexpected argument: $argument")
                        else -> {
                            directory = argument
                            index++
                        }
                    }
                }
                commands.init(directory, name)
            }
            else -> {
                val subcommand = rest.firstOrNull() ?: throw UsageError("missing subcommand: createscript ${args[0]} check [directory]")
                if (subcommand != "check") throw UsageError("unknown subcommand: ${args[0]} $subcommand")
                val directory = rest.getOrNull(1)
                if (directory != null && directory.startsWith("-")) throw UsageError("unknown option: $directory")
                if (rest.size > 2) throw UsageError("unexpected argument: ${rest[2]}")
                if (args[0] == "manifest") commands.checkManifest(directory) else commands.checkLock(directory)
            }
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
            file,
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
    // import の無いプログラムの命令・診断は CreateScript.compile(source, CompileOptions.STANDALONE) と同じ。
    // 独立版のコマンドなので、プログラムの命令の数に上限を設けない（第8.0.1段階。入力の上限・実行時の上限はそのまま）
    private fun run(command: RunCommand): Int {
        val file = command.file
        if (file == null || isDirectory(file)) return runProject(command, file)
        val source = readSource(file)
        val project = openProject(command, file)
        val compiled = CreateScript.compile(source, project, project.entryModule, CliLimits.COMPILE_OPTIONS)
        return execute(command, compiled, file, project::displayName)
    }

    private fun isDirectory(file: String): Boolean = try {
        Files.isDirectory(Paths.get(file))
    } catch (error: InvalidPathException) {
        false
    }

    // createscript run [ディレクトリ]（S8.2 Phase 4）: createscript.json の entry を、確かめたインストール済みのパッケージとともに実行する。
    // 通信しない。マニフェスト・ロックファイル・インストール済みのパッケージのどれかが合わなければ、コンパイルせずに終了コード 65
    // S8.2 L-8: 確かめる間（マニフェスト・ロックファイル・インストール済みのパッケージ）、install.lock の共有ロックを持つ（RunLock）。
    // インストールが動いていれば 75。ジャーナルがあり、インストールが動いていないと分かるときだけ「中断した」（65）。ロックはコンパイルの前に外す
    private fun runProject(command: RunCommand, directory: String?): Int {
        if (command.projectRoot != null) throw UsageError("--project-root cannot be used when running a project; the modules are found from createscript.json")
        val commands = ProjectCommands(out, err)
        val root = commands.projectRoot(directory)
        val guard = try {
            RunLock.acquire(root, runLocker, runLockWait) { err.println("createscript: $it") }
        } catch (busy: RunLock.Busy) {
            err.println("createscript: ${busy.message}")
            return ExitCode.TEMPORARY_FAILURE
        }
        // 確かめた結果が合わなかったとき: ロックを持たずに確かめた（install.lock が無い・ロックを扱えない）なら、その間にインストールが始まったかもしれない
        fun failed(code: Int): Int {
            if (guard.state != RunLock.State.HELD && RunLock.looksBusy(root, runLocker)) {
                err.println("createscript: createscript install started while the project was being checked; try again when it has finished")
                return ExitCode.TEMPORARY_FAILURE
            }
            return code
        }
        val project: ProjectCommands.RunnableProject
        val packages: List<VerifiedPackage>
        guard.use {
            if (Files.exists(InstallLayout.journal(root), LinkOption.NOFOLLOW_LINKS)) {
                val shownJournal = if (root.toString().isEmpty() || root.toString() == ".") ".createscript/transaction.json" else InstallLayout.journal(root).toString()
                err.println(
                    "createscript: " + when (guard.state) {
                        RunLock.State.NOT_CHECKED ->
                            "an installation is running or was interrupted ($shownJournal exists, and createscript could not check whether createscript install is running: ${guard.reason}); " +
                                "try again later, or run createscript install to finish or undo it"
                        else -> "an installation was interrupted ($shownJournal exists); run createscript install to finish or undo it"
                    },
                )
                return ExitCode.INVALID_INPUT
            }
            project = try {
                commands.projectForRun(directory)
            } catch (failure: ProjectCommands.Failure) {
                err.println("createscript: ${failure.message}")
                return failed(failure.code)
            } ?: return failed(ExitCode.INVALID_INPUT)
            packages = try {
                project.lock?.let { InstalledPackages.verify(project.root, it) } ?: emptyList()
            } catch (failure: InstallFailure) {
                err.println("createscript: ${failure.message}")
                return failed(if (failure.kind == InstallFailure.Kind.NO_PROJECT) ExitCode.NO_INPUT else ExitCode.INVALID_INPUT)
            }
        }
        val entry = project.shownEntry
        val source = readSource(entry)
        val entryPath = Paths.get(entry)
        val localRoot = try {
            entryPath.toAbsolutePath().parent.toRealPath()
        } catch (error: IOException) {
            throw InputError(ExitCode.NO_INPUT, "cannot read file: $entry")
        }
        val local = ProjectModules(localRoot, entryPath.parent?.toString(), entryPath.fileName.toString().removeSuffix(EXTENSION))
        val program = ProjectProgram(local, packages, project.manifest.dependencies.map { it.name }.toSet(), project.shownRoot)
        val compiled = CreateScript.compileScoped(source, program, program.entryModule, CliLimits.COMPILE_OPTIONS)
        return execute(command, compiled, entry, program::displayName)
    }

    // コンパイルの結果を表示して実行する（ファイルの実行とプロジェクトモードで同じ）。file は表示するエントリーのファイルの名前
    private fun execute(command: RunCommand, compiled: ModuleCompileResult, file: String, displayName: (String) -> String): Int {
        fun show(located: ModuleDiagnostic) = format(located.module?.let(displayName) ?: file, located.diagnostic)
        compiled.diagnostics.forEach { err.println(show(it)) }
        if (compiled.diagnosticsTruncated) err.println("$file: more diagnostics were omitted")
        val program = compiled.program ?: return ExitCode.COMPILE_ERROR

        val run = CreateScript.start(program, RunOptions(command.instructionsPerTick, command.seed)) { out.println(it) }
        var ticks = 0
        while (!run.isFinished && ticks < command.maxTicks) {
            run.tick()
            ticks++
        }
        out.flush()
        if (!run.isFinished) {
            err.println("$file: stopped after ${command.maxTicks} ticks (--max-ticks); the program did not finish")
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
            SourceFiles.Read.TooLarge -> fail(ExitCode.INVALID_INPUT, "file too large: $file (at most ${CliLimits.MAX_SOURCE_LENGTH} characters)")
            SourceFiles.Read.NotUtf8 -> fail(ExitCode.INVALID_INPUT, "file is not valid UTF-8: $file")
            SourceFiles.Read.Failed -> fail(ExitCode.NO_INPUT, "cannot read file: $file")
        }
    }

    // プロジェクトのルート（第8.0段階）。--project-root か、省略時はファイルのあるディレクトリ。ファイルはルートの中になければならない
    private fun openProject(command: RunCommand, fileName: String): ProjectModules {
        val file = Paths.get(fileName)
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
            throw InputError(ExitCode.NO_INPUT, "cannot read file: $fileName")
        }
        if (!directory.startsWith(root)) throw InputError(ExitCode.USAGE, "$fileName is not inside the project root ${command.projectRoot}")
        return ProjectModules(root, shown, if (directory == root) file.fileName.toString().removeSuffix(EXTENSION) else null)
    }

    companion object {
        private const val EXTENSION = CliLimits.EXTENSION
        private val OPTIONS = setOf("--seed", "--instructions-per-tick", "--max-ticks", "--project-root")

        private val USAGE = """
            |Usage:
            |  createscript                      start the interactive mode
            |  createscript run <file$EXTENSION> [options]
            |  createscript run [directory] [options]          run the entry of createscript.json with the installed packages
            |  createscript init [directory] [--name <name>]   create createscript.json and src/main$EXTENSION
            |  createscript manifest check [directory]       check createscript.json
            |  createscript lock check [directory]           check createscript.lock against createscript.json
            |  createscript pack [directory] [--output <file>]  make createscript-package.zip from a library
            |  createscript install [github:<owner>/<repo>@v<version>] [directory] [--offline]
            |                                    install the dependencies (and add the given package to createscript.json)
            |  createscript install <name>@<version> [directory] [--offline]
            |                                    install a package reviewed in the signed CreateScript-Registry index
            |                                    (the official registry is not published yet; this refuses until it is)
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
            |  64 usage error, 65 invalid input file, 66 input file not found or unreadable,
            |  69 network unavailable (install), 73 cannot create a file or safely change one (init, pack, install),
            |  75 another install is running
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
