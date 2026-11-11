package com.creatrbypc.createanalytical.registrytool

import com.creatrbypc.createanalytical.install.GitHubReleases
import com.creatrbypc.createanalytical.project.registry.IndexVerifier
import com.creatrbypc.createanalytical.project.registry.RegistryIndexes
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.system.exitProcess

// createscript-registry-tool（S8.3 Phase 2）。終了コード: 0 成功、1 検査に通らない、2 使い方の誤り・読めない入力
fun main(args: Array<String>) {
    exitProcess(RegistryTool(System.out, System.err, { Instant.now() }, { LiveGitHub(GitHubReleases()) }).run(args.toList()))
}

class RegistryTool(private val out: PrintStream, private val err: PrintStream, private val clock: () -> Instant, private val github: () -> RegistryGitHub) {
    private val usage = """
        usage: createscript-registry-tool <command> [options]
          check-repo --repo DIR
          validate-pr --base DIR --head DIR --pr N --author LOGIN --author-id ID [--previous-index FILE --previous-sig FILE]
          check-approvals --base DIR --head DIR --pr N --author LOGIN --author-id ID --head-sha SHA --reviews FILE
          build-index --repo DIR --out FILE [--previous-index FILE --previous-sig FILE] [--generated YYYY-MM-DDTHH:MM:SSZ]
          sign --index FILE --key FILE --key-id ID --repo DIR --out FILE [--existing-sig FILE]
          verify-index --index FILE --sig FILE --repo DIR [--previous-index FILE --previous-sig FILE]
          publish-check --repo DIR [--previous-index FILE --previous-sig FILE]
          check-release --release FILE --index FILE --sig FILE
    """.trimIndent()

    private class Usage(message: String) : Exception(message)

    fun run(args: List<String>): Int {
        if (args.isEmpty() || args[0] == "--help") {
            out.println(usage)
            return if (args.isEmpty()) 2 else 0
        }
        return try {
            val options = options(args.drop(1))
            val report = when (args[0]) {
                "check-repo" -> checkRepo(options)
                "validate-pr" -> validatePr(options)
                "check-approvals" -> checkApprovals(options)
                "build-index" -> buildIndex(options)
                "sign" -> sign(options)
                "verify-index" -> verifyIndex(options)
                "publish-check" -> publishCheck(options)
                "check-release" -> IndexPublishing.checkRelease(file(options, "release"), file(options, "index"), file(options, "sig"))
                else -> throw Usage("unknown command ${args[0]}")
            }
            report.lines().forEach(out::println)
            out.println(if (report.ok) "OK" else "FAILED: ${report.errors.size} problem(s)")
            if (report.ok) 0 else 1
        } catch (error: Usage) {
            err.println("createscript-registry-tool: ${Report.safe(error.message ?: "")}")
            err.println(usage)
            2
        } catch (error: ToolFailure) {
            err.println("createscript-registry-tool: ${Report.safe(error.message ?: "")}")
            2
        } catch (error: java.io.IOException) {
            err.println("createscript-registry-tool: ${Report.safe(error.toString())}")
            2
        } catch (error: java.time.format.DateTimeParseException) {
            err.println("createscript-registry-tool: --generated must be YYYY-MM-DDTHH:MM:SSZ")
            2
        }
    }

    // --name value の組（同じ名前は 1 回だけ）。オプションでない引数は使い方の誤り
    private fun options(args: List<String>): Map<String, String> {
        val result = HashMap<String, String>()
        var i = 0
        while (i < args.size) {
            val name = args[i]
            if (!name.startsWith("--") || i + 1 >= args.size) throw Usage("expected --option value, got ${args[i]}")
            if (result.put(name.removePrefix("--"), args[i + 1]) != null) throw Usage("$name is given twice")
            i += 2
        }
        return result
    }

    private fun required(options: Map<String, String>, name: String) = options[name] ?: throw Usage("--$name is required")

    private fun path(options: Map<String, String>, name: String): Path = Paths.get(required(options, name))

    // 通常のファイルだけを読む（シンボリックリンクは読まない）
    private fun file(options: Map<String, String>, name: String): ByteArray = readFile(path(options, name))

    private fun readFile(path: Path): ByteArray {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw ToolFailure("$path is not a regular file")
        if (Files.size(path) > RegistryTree.MAX_FILE_BYTES) throw ToolFailure("$path is too large")
        return Files.readAllBytes(path)
    }

    private fun previous(options: Map<String, String>): PublishedIndex? {
        val index = options["previous-index"]
        val sig = options["previous-sig"]
        if ((index == null) != (sig == null)) throw Usage("--previous-index and --previous-sig go together")
        return if (index == null) null else PublishedIndex(readFile(Paths.get(index)), readFile(Paths.get(sig!!)))
    }

    private fun pullRequest(options: Map<String, String>) = PullRequest(
        required(options, "pr").toLongOrNull() ?: throw Usage("--pr must be a number"),
        required(options, "author"),
        required(options, "author-id").toLongOrNull() ?: throw Usage("--author-id must be a number"),
    )

    // 新しく書くファイル（既にあれば書かない。シンボリックリンクの先には書かない）
    private fun write(path: Path, bytes: ByteArray) {
        Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
    }

    private fun repository(options: Map<String, String>) = LoadedRegistry.load(RegistryTree.read(path(options, "repo")))

    private fun checkRepo(options: Map<String, String>): Report {
        val report = Report()
        val repository = repository(options)
        repository.problems.forEach(report::error)
        if (repository.ok) {
            RegistryIndexes.build(repository.config!!, repository.reserved!!, repository.entries, 1, clock().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)).diagnostics.forEach { report.error(it.format("packages")) }
            val maintainers = repository.maintainers!!
            report.notice(if (maintainers.open) "the registry is open (${maintainers.maintainers.size} maintainers)" else maintainers.closedReason())
        }
        return report
    }

    private fun validatePr(options: Map<String, String>): Report {
        val now = clock()
        return PullRequestValidator(github(), LocalDate.ofInstant(now, ZoneOffset.UTC), now)
            .validate(RegistryTree.read(path(options, "base")), RegistryTree.read(path(options, "head")), pullRequest(options), previous(options))
    }

    private fun checkApprovals(options: Map<String, String>): Report =
        Approvals.check(RegistryTree.read(path(options, "base")), RegistryTree.read(path(options, "head")), pullRequest(options), required(options, "head-sha"), Approvals.parseReviews(file(options, "reviews")))

    private fun buildIndex(options: Map<String, String>): Report {
        val report = Report()
        val repository = repository(options)
        val keys = repository.keys?.keys ?: run {
            repository.problems.forEach(report::error)
            return report
        }
        val previous = IndexPublishing.acceptPrevious(previous(options), keys, clock(), report)
        if (!report.ok) return report
        val generated = options["generated"]?.let { Instant.parse(it) } ?: clock()
        val built = IndexPublishing.build(repository, previous, generated, report) ?: return report
        write(path(options, "out"), built.bytes)
        report.notice("wrote ${options["out"]}: serial ${built.index.serial}, sha256 ${RegistryIndexes.sha256(built.bytes)}")
        return report
    }

    private fun sign(options: Map<String, String>): Report {
        val report = Report()
        val repository = repository(options)
        val keys = repository.keys?.keys ?: run {
            repository.problems.forEach(report::error)
            return report
        }
        val key = IndexPublishing.readPrivateKey(file(options, "key"))
        val signature = IndexPublishing.sign(file(options, "index"), key, required(options, "key-id"), keys, options["existing-sig"]?.let { readFile(Paths.get(it)) }, report) ?: return report
        write(path(options, "out"), signature)
        report.notice("wrote ${options["out"]}")
        return report
    }

    private fun verifyIndex(options: Map<String, String>): Report {
        val report = Report()
        val repository = repository(options)
        val keys = repository.keys?.keys ?: run {
            repository.problems.forEach(report::error)
            return report
        }
        val now = clock()
        val previous = IndexPublishing.acceptPrevious(previous(options), keys, now, report)
        if (!report.ok) return report
        val result = IndexVerifier(keys).verify(file(options, "index"), file(options, "sig"), previous, now)
        result.diagnostics.forEach { report.error(it.format("index.json")) }
        result.accepted?.let { report.notice("serial ${it.index.serial}, expires ${it.index.expires}, sha256 ${it.sha256}, signed by ${it.keyIds.joinToString(", ")}") }
        return report
    }

    private fun publishCheck(options: Map<String, String>): Report {
        val repository = repository(options)
        return IndexPublishing.publishCheck(repository, repository.signedIndex, repository.signedSignature, previous(options), clock(), allowTestKeys = false)
    }
}
