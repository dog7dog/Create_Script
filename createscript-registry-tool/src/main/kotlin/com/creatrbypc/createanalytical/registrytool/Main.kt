package com.creatrbypc.createanalytical.registrytool

import com.creatrbypc.createanalytical.install.GitHubReleases
import com.creatrbypc.createanalytical.project.JsonObject
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

// allowTestKeys は、テスト専用のビルド（src/test の TestRegistryTool、S8.3 Phase 10A の W7）だけが true にする。本番の main は渡さない
class RegistryTool(
    private val out: PrintStream,
    private val err: PrintStream,
    private val clock: () -> Instant,
    private val github: () -> RegistryGitHub,
    private val allowTestKeys: Boolean = false,
    // T3（S8.3 U-1）: 設定の証明用の鍵の、ツールに固定した指紋。本番の main は既定（SettingsKeyPins.OFFICIAL、今は空）のまま
    private val settingsKeyPins: Map<String, String> = SettingsKeyPins.OFFICIAL,
) {
    private val usage = """
        usage: createscript-registry-tool <command> [options]
          check-repo --repo DIR
          validate-pr --base DIR --head DIR --pr N --author LOGIN --author-id ID [--previous-index FILE --previous-sig FILE] [--from-fork true|false]
          check-approvals --base DIR --head DIR --pr N --author LOGIN --author-id ID --head-sha SHA --reviews FILE
          build-index --repo DIR --out FILE [--previous-index FILE --previous-sig FILE] [--generated YYYY-MM-DDTHH:MM:SSZ]
          sign --index FILE --key FILE --key-id ID --repo DIR --out FILE [--existing-sig FILE]
          verify-index --index FILE --sig FILE --repo DIR [--previous-index FILE --previous-sig FILE]
          publish-check --repo DIR [--previous-index FILE --previous-sig FILE]
          check-release --release FILE --index FILE --sig FILE
          check-settings --ruleset FILE --tag-creation-ruleset FILE --tag-protection-ruleset FILE [--checks-app-id ID --publisher-app-id ID]
          check-live-rulesets --repo DIR --live DIR --checks-app-id ID --publisher-app-id ID
          record-ruleset-snapshot --repo DIR --live DIR --repository-info FILE --repository OWNER/NAME --recorded-by LOGIN --checks-app-id ID --publisher-app-id ID --out FILE [--valid-days 1..180]
          sign-ruleset-snapshot --repo DIR --snapshot FILE --key FILE --key-id ID --repository OWNER/NAME --checks-app-id ID --publisher-app-id ID --out FILE [--existing-sig FILE]
          check-ruleset-snapshot --repo DIR --live DIR --repository-info FILE --repository OWNER/NAME --checks-app-id ID --publisher-app-id ID --actor workflow|publisher
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
                "check-settings" -> checkSettings(options)
                "check-live-rulesets" -> checkLiveRulesets(options)
                "record-ruleset-snapshot" -> recordRulesetSnapshot(options)
                "check-ruleset-snapshot" -> checkRulesetSnapshot(options)
                "sign-ruleset-snapshot" -> signRulesetSnapshot(options)
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
            .validate(RegistryTree.read(path(options, "base")), RegistryTree.read(path(options, "head")), pullRequest(options), previous(options), fromFork = fromFork(options))
    }

    // --from-fork true|false（PR の head のリポジトリが、レジストリのリポジトリと違うか。ワークフローが GitHub のイベントから渡す）
    private fun fromFork(options: Map<String, String>): Boolean = when (options["from-fork"]) {
        null, "false" -> false
        "true" -> true
        else -> throw Usage("--from-fork must be true or false")
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
        return IndexPublishing.publishCheck(repository, repository.signedIndex, repository.signedSignature, previous(options), clock(), allowTestKeys = allowTestKeys)
    }

    // GitHub App の ID（正の整数）。無ければ null
    private fun appId(options: Map<String, String>, name: String): Long? {
        val text = options[name] ?: return null
        return text.toLongOrNull()?.takeIf { it > 0 } ?: throw Usage("--$name must be a positive number")
    }

    // ruleset の案のファイル（S8.3 Phase 5・10A）: main の ruleset と、index-* のタグの ruleset A（作成）・B（更新・削除）。
    // App の ID を渡せば、必須チェックの App（Checks）と、A の回避者（Publisher）を照合する
    private fun checkSettings(options: Map<String, String>): Report {
        val report = Report()
        val main = file(options, "ruleset")
        GitHubSettings.rulesetProblems(main).forEach(report::error)
        val checks = appId(options, "checks-app-id")
        val publisher = appId(options, "publisher-app-id")
        if (checks != null) {
            val parsed = TagRulesets.parse(main)
            if (parsed != null) {
                for (check in GitHubSettings.requiredChecks(parsed)) {
                    if (check.integrationId != checks) report.error("the required check ${check.context} comes from App ${check.integrationId}, not the Registry Checks App ($checks)")
                }
            }
        }
        TagRulesets.fileProblems(file(options, "tag-creation-ruleset"), file(options, "tag-protection-ruleset"), publisher, checks).forEach(report::error)
        return report
    }

    // 公開の前の、リポジトリの実際の ruleset の確認（S8.3 Phase 10A、W3）。--live は、GitHub の API の応答
    // （GET /repos/{owner}/{repo}/rulesets/{id}）を ruleset-<id>.json として保存したディレクトリ。--repo のリポジトリの案のファイルと比べる。
    // 確かめられない項目（bypass_actors が返されない、など）は失敗にする
    private fun checkLiveRulesets(options: Map<String, String>): Report {
        val report = Report()
        val checks = appId(options, "checks-app-id") ?: throw Usage("--checks-app-id is required")
        val publisher = appId(options, "publisher-app-id") ?: throw Usage("--publisher-app-id is required")
        val repo = path(options, "repo")
        val objects = liveRulesets(options)
        if (objects.isEmpty()) report.error("no ruleset of the repository could be read, so the tag protection cannot be verified")
        TagRulesets.liveProblems(readFile(repo.resolve(TagRulesets.CREATION_FILE)), readFile(repo.resolve(TagRulesets.PROTECTION_FILE)), objects, publisher, checks).forEach(report::error)
        if (report.ok) report.notice("the tag rulesets match ${TagRulesets.CREATION_FILE} and ${TagRulesets.PROTECTION_FILE}")
        return report
    }

    // --live: GitHub の API の応答（GET /repos/{owner}/{repo}/rulesets/{id}）を ruleset-<id>.json として保存したディレクトリ
    private fun liveRulesets(options: Map<String, String>): List<JsonObject> {
        val live = path(options, "live")
        if (!Files.isDirectory(live, LinkOption.NOFOLLOW_LINKS)) throw ToolFailure("$live is not a directory")
        val files = Files.list(live).use { s -> s.filter { Regex("ruleset-[0-9]+\\.json").matches(it.fileName.toString()) }.sorted().toList() }
        if (files.size > 100) throw ToolFailure("more than 100 rulesets")
        return files.map { f -> TagRulesets.parse(readFile(f)) ?: throw ToolFailure("${f.fileName} is not a JSON object") }
    }

    // --repository-info: GET /repos/{owner}/{repo} の応答（既定のブランチの照合に使う）
    private fun repositoryInfo(options: Map<String, String>): JsonObject? = TagRulesets.parse(file(options, "repository-info"))

    private fun repositoryName(options: Map<String, String>): String =
        required(options, "repository").takeIf { Regex("[A-Za-z0-9-]{1,39}/[A-Za-z0-9._-]{1,100}").matches(it) } ?: throw Usage("--repository must be owner/name")

    // 案 B'（S8.3 Phase 10B-2A）: 管理者が自分のトークンで取得した ruleset（bypass_actors を含む）から、スナップショットを作る。
    // 署名はこのコマンドでは行わない（設定の証明用の鍵の運用は未決定。テストでは src/test の一時的な鍵で署名する）
    private fun recordRulesetSnapshot(options: Map<String, String>): Report {
        val report = Report()
        val checks = appId(options, "checks-app-id") ?: throw Usage("--checks-app-id is required")
        val publisher = appId(options, "publisher-app-id") ?: throw Usage("--publisher-app-id is required")
        val repo = path(options, "repo")
        val recordedBy = required(options, "recorded-by")
        val days = options["valid-days"]?.let { it.toLongOrNull() ?: throw Usage("--valid-days must be a number") } ?: RulesetSnapshots.MAX_VALIDITY_DAYS
        val result = RulesetSnapshots.record(
            readFile(repo.resolve(TagRulesets.CREATION_FILE)), readFile(repo.resolve(TagRulesets.PROTECTION_FILE)), readFile(repo.resolve(RulesetSnapshots.MAIN_FILE)),
            liveRulesets(options), repositoryInfo(options), repositoryName(options), recordedBy, clock(), publisher, checks, days,
        )
        result.problems.forEach(report::error)
        val bytes = result.value ?: return report
        write(path(options, "out"), bytes)
        report.notice("wrote ${options["out"]}; sign it with the settings key and record it in ${RulesetSnapshots.FILE} through a reviewed pull request")
        return report
    }

    // 案 B': 公開の前に、読み取りのトークンで取得した ruleset を、署名されたスナップショットと照合する。
    // --actor は確認を行うトークンの主体（workflow: ジョブの GITHUB_TOKEN、publisher: Registry Publisher App）
    private fun checkRulesetSnapshot(options: Map<String, String>): Report {
        val report = Report()
        val checks = appId(options, "checks-app-id") ?: throw Usage("--checks-app-id is required")
        val publisher = appId(options, "publisher-app-id") ?: throw Usage("--publisher-app-id is required")
        val actor = when (required(options, "actor")) {
            "workflow" -> RulesetSnapshots.Actor.WORKFLOW
            "publisher" -> RulesetSnapshots.Actor.PUBLISHER
            else -> throw Usage("--actor must be workflow or publisher")
        }
        val repo = path(options, "repo")
        fun optional(relative: String): ByteArray? = repo.resolve(relative).takeIf { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }?.let(::readFile)
        val snapshot = optional(RulesetSnapshots.FILE) ?: run {
            report.error("${RulesetSnapshots.FILE} is missing, so the tag rulesets cannot be verified")
            return report
        }
        RulesetSnapshots.signatureProblems(snapshot, optional(RulesetSnapshots.SIGNATURE_FILE), optional(RulesetSnapshots.KEYS_FILE), allowTestKeys, settingsKeyPins).forEach(report::error)
        val live = liveRulesets(options)
        if (live.isEmpty()) report.error("no ruleset of the repository could be read, so the tag protection cannot be verified")
        RulesetSnapshots.checkProblems(
            readFile(repo.resolve(TagRulesets.CREATION_FILE)), readFile(repo.resolve(TagRulesets.PROTECTION_FILE)), readFile(repo.resolve(RulesetSnapshots.MAIN_FILE)),
            snapshot, live, repositoryInfo(options), repositoryName(options), publisher, checks, actor, clock(),
        ).forEach(report::error)
        if (report.ok) report.notice("the tag rulesets match the signed snapshot ${RulesetSnapshots.FILE} (updated_at unchanged since it was recorded)")
        return report
    }

    // U-1: スナップショットへの署名（オフラインの機械で行う）。署名の前に、方針・期限を検査し、要約（対象・時刻・鍵の ID）を表示する。
    // 秘密鍵の内容は表示しない。本番の鍵は、設定の鍵の作成の儀式が承認されるまで使えない（RulesetSnapshots.PRODUCTION_SIGNING_ENABLED）
    private fun signRulesetSnapshot(options: Map<String, String>): Report {
        val report = Report()
        val checks = appId(options, "checks-app-id") ?: throw Usage("--checks-app-id is required")
        val publisher = appId(options, "publisher-app-id") ?: throw Usage("--publisher-app-id is required")
        val keyId = required(options, "key-id")
        if (!RulesetSnapshots.PRODUCTION_SIGNING_ENABLED && !keyId.startsWith(TrustedKeysFile.TEST_KEY_PREFIX)) {
            // 本番の鍵のファイルは、読みもしない
            report.error("signing with the key $keyId is refused: only test keys (${TrustedKeysFile.TEST_KEY_PREFIX}…) may sign until the settings key ceremony is approved (docs/registry-settings-key.md)")
            return report
        }
        val repo = path(options, "repo")
        val snapshot = file(options, "snapshot")
        val (problems, _) = RulesetSnapshots.snapshotProblems(
            readFile(repo.resolve(TagRulesets.CREATION_FILE)), readFile(repo.resolve(TagRulesets.PROTECTION_FILE)), readFile(repo.resolve(RulesetSnapshots.MAIN_FILE)), snapshot,
            repositoryName(options), publisher, checks, clock(),
        )
        RulesetSnapshots.summary(snapshot, keyId).forEach(report::notice)
        problems.forEach(report::error)
        if (!report.ok) return report
        val keysFile = repo.resolve(RulesetSnapshots.KEYS_FILE).takeIf { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }?.let(::readFile)
        val existing = options["existing-sig"]?.let { readFile(Paths.get(it)) }
        // 鍵のファイルの内容は、秘密鍵の形に読み取ったら、このバイト列からは消す（JVM の中のほかの写しまでは消せない）
        val keyBytes = file(options, "key")
        val privateKey = try {
            IndexPublishing.readPrivateKey(keyBytes)
        } finally {
            keyBytes.fill(0)
        }
        val result = RulesetSnapshots.sign(snapshot, privateKey, keyId, keysFile, existing, allowTestKeys, settingsKeyPins)
        result.problems.forEach(report::error)
        val bytes = result.value ?: return report
        write(path(options, "out"), bytes)
        report.notice("wrote ${options["out"]}; add it with the snapshot as ${RulesetSnapshots.SIGNATURE_FILE} through a reviewed pull request")
        return report
    }
}
