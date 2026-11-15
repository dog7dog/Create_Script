package com.creatrbypc.createanalytical.registrytool

import com.creatrbypc.createanalytical.project.JsonArray
import com.creatrbypc.createanalytical.project.JsonInteger
import com.creatrbypc.createanalytical.project.JsonObject
import com.creatrbypc.createanalytical.project.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

// S8.3 Phase 2: registry-template（将来の CreateScript-Registry の雛形）の中身と、ワークフローの安全の規則
class TemplateTest {
    private val repository: Path = Paths.get("..").toAbsolutePath().normalize()
    private val template: Path = repository.resolve("registry-template")

    private fun read(path: String) = Files.readString(template.resolve(path))

    @Test
    fun templateIsAValidClosedRegistry() {
        val loaded = LoadedRegistry.load(RegistryTree.read(template))
        assertEquals(emptyList<String>(), loaded.problems)
        val maintainers = loaded.maintainers!!
        assertEquals("dog7dog", maintainers.lead)
        assertEquals(2, maintainers.requiredApprovals)
        assertEquals(3, maintainers.minimumMaintainers)
        assertFalse(maintainers.open, "the registry stays closed until it has three maintainers")
        assertTrue(loaded.keys!!.keys.keys.isEmpty(), "no keys yet")
        assertTrue(loaded.entries.isEmpty())
        assertEquals(null, loaded.signedIndex)
        for (file in listOf("README.md", "POLICY.md", "SECURITY.md", "CONTRIBUTING.md", "GOVERNANCE.md", "SIGNING.md", ".github/CODEOWNERS", "packages/.gitkeep", "signed/.gitkeep")) {
            assertTrue(Files.isRegularFile(template.resolve(file)), file)
        }
    }

    // テンプレートのワークフローは、CreateScript のリポジトリでは動かない（GitHub が読むのはルートの .github/workflows だけ）
    @Test
    fun workflowsAreNotActiveInThisRepository() {
        val active = Files.list(repository.resolve(".github/workflows")).use { s -> s.map { it.fileName.toString() }.sorted().toList() }
        assertEquals(listOf("ci.yml"), active)
        assertFalse(Files.readString(repository.resolve(".github/workflows/ci.yml")).contains("registry-template"))
    }

    // ジョブごとの本文（jobs: の下の、2 字下げの名前から次の名前まで）
    private fun jobs(text: String): Map<String, String> {
        val body = text.substringAfter("\njobs:\n")
        val starts = Regex("^  ([a-z-]+):$", RegexOption.MULTILINE).findAll(body).toList()
        return starts.mapIndexed { i, m -> m.groupValues[1] to body.substring(m.range.first, if (i + 1 < starts.size) starts[i + 1].range.first else body.length) }.toMap()
    }

    @Test
    fun workflowsFollowTheSafetyRules() {
        val jarSha = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(repository.resolve("gradle/wrapper/gradle-wrapper.jar"))).joinToString("") { "%02x".format(it) }
        for (name in listOf("validate.yml", "publish.yml")) {
            val text = read(".github/workflows/$name")
            // 外部のアクションは、すべてコミットの SHA で固定
            val uses = Regex("uses:\\s*(\\S+)").findAll(text).map { it.groupValues[1] }.toList()
            assertTrue(uses.isNotEmpty())
            for (action in uses) assertTrue(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+@[0-9a-f]{40}").matches(action), "$name: $action is not pinned to a commit SHA")
            // キャッシュを使わない（キャッシュ経由の汚染を避ける。P4-M3）
            assertFalse(text.contains("actions/cache") || Regex("^\\s*cache:", RegexOption.MULTILINE).containsMatchIn(text), "$name uses a cache")
            // 既定の権限は無し。チェックアウトは資格情報を残さない
            assertTrue(text.contains("\npermissions: {}\n"), name)
            assertEquals(Regex("uses: actions/checkout@").findAll(text).count(), Regex("persist-credentials: false").findAll(text).count(), name)
            // ツールは固定のコミットから作る。設定されるまで（すべて 0 の間）は、必ず失敗する
            assertTrue(text.contains("TOOL_COMMIT: \"0000000000000000000000000000000000000000\""), name)
            assertTrue(text.contains("if [ \"\$TOOL_COMMIT\" = \"0000000000000000000000000000000000000000\" ]"), name)
            // Gradle: wrapper の SHA-256 を確かめ、依存の検証は strict、ビルドキャッシュは使わない（P4-M3）
            assertTrue(text.contains("WRAPPER_JAR_SHA256: \"$jarSha\""), "$name: WRAPPER_JAR_SHA256 is not the SHA-256 of this repository's gradle-wrapper.jar")
            val gradle = text.lines().filter { it.contains("./gradlew") }
            assertTrue(gradle.isNotEmpty())
            for (line in gradle) assertTrue(line.contains("--dependency-verification strict") && line.contains("--no-build-cache"), "$name: $line")
            assertEquals(gradle.size, Regex("gradle-wrapper.jar\" \\| sha256sum -c -").findAll(text).count(), "$name: every build checks the wrapper")
            assertEquals(gradle.size, Regex("test -f gradle/verification-metadata.xml").findAll(text).count(), name)
            // ツールの出力を、ワークフローコマンドとして解釈させない
            assertTrue(text.contains("::stop-commands::"), name)
            // GitHub の式を run の中に直接書かない（値は env で渡す）
            for (block in Regex("run: \\|\\n((?:\\s{10,}.*\\n)+)").findAll(text)) assertFalse(block.groupValues[1].contains("\${{"), "$name: an expression inside run")
            // 秘密鍵・署名の手順は、ワークフローに無い
            assertFalse(text.contains(" sign "), "$name signs")
            assertFalse(text.contains("PRIVATE KEY"), name)
            // 認証なしで GitHub の API を呼ばない（P4-M4）: curl で api.github.com を呼ぶ行は、直前の行か同じ行に Authorization がある
            val lines = text.lines()
            for ((i, line) in lines.withIndex()) {
                if (line.contains("https://api.github.com")) assertTrue((lines[i - 1] + line).contains("Authorization: Bearer"), "$name: unauthenticated API call: $line")
            }
            for ((job, body) in jobs(text)) {
                // PR のデータを扱うジョブには、秘密情報も書き込みの権限も無い
                if (body.contains("path: head")) {
                    assertFalse(body.contains("secrets.") || body.contains("vars.") || body.contains("contents: write") || body.contains("environment:"), "$name/$job reads the pull request and has secrets or write access")
                }
                // 秘密情報を使うジョブは、チェックアウトもビルドもしない（PR のデータに触れない）
                // 秘密情報（App の鍵）を使うジョブは、PR のデータに触れず、ビルドもツールの実行もしない。チェックアウトするなら、信頼する main のコミット
                // （github.sha）だけ。鍵は、そのジョブの App のものだけで、environment で守る（S8.3 Phase 5・10A）
                if (body.contains("secrets.")) {
                    assertFalse(body.contains("path: head") || body.contains("head/") || body.contains("gradlew") || body.contains("\$TOOL") || body.contains("setup-java"), "$name/$job uses a secret and touches code or data")
                    assertTrue(Regex("ref: \\\$\\{\\{ github.sha }}").findAll(body).count() == Regex("uses: actions/checkout@").findAll(body).count(), "$name/$job checks out only github.sha")
                    val secrets = Regex("secrets\\.([A-Z_]+)").findAll(body).map { it.groupValues[1] }.toSet()
                    when (name) {
                        "validate.yml" -> assertTrue(secrets == setOf("REGISTRY_CHECKS_APP_KEY") && body.contains("environment: registry-checks"), "$name/$job: $secrets")
                        else -> assertTrue(secrets == setOf("REGISTRY_PUBLISHER_APP_KEY") && body.contains("environment: publish"), "$name/$job: $secrets")
                    }
                }
                // 書き込みの権限のあるジョブは、Gradle もツールも動かさない（P4-M3）
                if (body.contains("contents: write")) {
                    assertFalse(body.contains("gradlew") || body.contains("\$TOOL") || body.contains("setup-java"), "$name/$job builds or runs code with a write token")
                    assertTrue(body.contains("environment: publish"), "$name/$job")
                }
            }
        }
        val validate = read(".github/workflows/validate.yml")
        // PR の検証は、main のワークフローで動き（pull_request_target）、PR のファイルはデータとしてだけ使う
        assertTrue(validate.contains("pull_request_target:"))
        assertFalse(Regex("^\\s*pull_request:", RegexOption.MULTILINE).containsMatchIn(validate))
        assertFalse(validate.contains("contents: write"))
        assertFalse(Regex("working-directory: head|head/[^\\s]*\\.sh|bash head|\\./head").containsMatchIn(validate), "nothing from the pull request is run")
        val validateJobs = jobs(validate)
        assertEquals(setOf("validate", "approvals", "report"), validateJobs.keys)
        // PR を読むジョブは、古い実行を拒否する（現在の head と比べる。P4-M4）
        for (job in listOf("validate", "approvals")) assertTrue(validateJobs.getValue(job).contains("current=\"\$(gh api \"repos/\$REPOSITORY/pulls/\$PR_NUMBER\" --jq .head.sha)\""), job)
        // 結果は、App のチェックとして、必須チェックの名前で head のコミットに付ける（P4-H1）
        val report = validateJobs.getValue("report")
        assertTrue(report.contains("needs: [validate, approvals]") && report.contains("if: \${{ always() }}"))
        for (context in GitHubSettings.REQUIRED_CONTEXTS) assertTrue(report.contains("post $context "), context)
        assertTrue(report.contains("--arg sha \"\$HEAD_SHA\"") && report.contains("check-runs"))
        assertTrue(report.contains("nothing is reported for"))
        // fork の PR かどうかを、ツールに渡す
        assertTrue(validateJobs.getValue("validate").contains("--from-fork \"\$fork\""))
        val publish = read(".github/workflows/publish.yml")
        val publishJobs = jobs(publish)
        assertEquals(setOf("check", "release", "verify"), publishJobs.keys)
        val check = publishJobs.getValue("check")
        assertTrue(check.contains("publish-check --repo registry") && check.contains("check-approvals") && check.contains("rev-parse \"\$PR_HEAD^{tree}\""))
        val release = publishJobs.getValue("release")
        assertTrue(release.contains("needs: check") && release.contains("environment: publish") && release.contains("sha256sum -c -") && release.contains("gh release create"))
        assertTrue(publishJobs.getValue("verify").contains("check-release"))
    }

    // S8.3 Phase 7: App のトークンの失効（P6-L1）、App の権限の文書（P6-L2）、下書きを経る公開（P6-L3）、base の取り出し（P6-L5）
    @Test
    fun phase7RulesHold() {
        val validate = read(".github/workflows/validate.yml")
        val report = jobs(validate).getValue("report")
        assertTrue(report.contains("trap cleanup EXIT") && report.contains("-X DELETE") && report.contains("https://api.github.com/installation/token"), "the App token is revoked")
        assertEquals(1, Regex("trap ").findAll(report).count(), "one cleanup for the key file and the token")
        assertTrue(report.contains("'{repositories: [\$repo], permissions: {checks: \"write\"}}'"), "the App token is limited to checks: write")
        assertTrue(report.indexOf("echo \"::add-mask::\$token\"") < report.indexOf("post registry/validate"), "masked before use")
        assertFalse(Regex(">\\s*\"?\\\$token|tee").containsMatchIn(report), "the token is not written to a file")
        val governance = read("GOVERNANCE.md")
        assertTrue(governance.contains("**Repository permissions: \"Checks: read and write\" only**"))
        assertFalse(governance.contains("\"Pull requests: read\""), "the App does not need pull requests")
        assertTrue(governance.contains("## Failed publication") && governance.contains("## Index tags and releases"))
        val publish = read(".github/workflows/publish.yml")
        val release = jobs(publish).getValue("release")
        assertTrue(release.contains("--draft") && !release.contains("--latest") && release.contains("make_latest=true"), "draft first, latest only when published")
        assertTrue(release.indexOf("already exists") < release.indexOf("gh release create"), "an existing release stops a re-run before anything is created")
        assertTrue(release.indexOf("sha256:\$INDEX_SHA256") < release.indexOf("-X PATCH"), "the draft's digests are checked before publishing")
        assertFalse(publish.contains("git -C registry archive") || publish.contains("| tar "), "the base is not made with git archive (export-ignore)")
        assertTrue(jobs(publish).getValue("check").contains("cat-file blob") && jobs(publish).getValue("check").contains("merge-base --is-ancestor"))
        assertTrue(jobs(publish).getValue("verify").contains("if: \${{ failure() }}"))
    }

    // Gradle の依存の検証（P4-M3）: メタデータがあり、すべての成果物に SHA-256 がある
    @Test
    fun dependencyVerificationIsComplete() {
        val metadata = Files.readString(repository.resolve("gradle/verification-metadata.xml"))
        assertTrue(metadata.contains("<verify-metadata>true</verify-metadata>"))
        val artifacts = Regex("<artifact name=\"[^\"]+\">\\s*<sha256 value=\"[0-9a-f]{64}\"").findAll(metadata).count()
        assertEquals(Regex("<artifact ").findAll(metadata).count(), artifacts)
        assertTrue(artifacts > 50)
        assertTrue(Files.readString(repository.resolve("gradle/wrapper/gradle-wrapper.properties")).contains("distributionSha256Sum="))
    }

    @Test
    fun rulesetsHaveNoBypass() {
        val main = JsonParser.parse(read("github-settings/main-ruleset.json").toByteArray(), 1 shl 20).value as JsonObject
        assertEquals(0, (main.members.getValue("bypass_actors") as JsonArray).elements.size)
        val rules = (main.members.getValue("rules") as JsonArray).elements.map { it as JsonObject }
        val types = rules.map { (it.members.getValue("type") as com.creatrbypc.createanalytical.project.JsonString).value }
        assertTrue(types.containsAll(listOf("deletion", "non_fast_forward", "pull_request", "required_status_checks")), types.toString())
        val pullRequest = rules.single { (it.members.getValue("type") as com.creatrbypc.createanalytical.project.JsonString).value == "pull_request" }.members.getValue("parameters") as JsonObject
        assertEquals(2L, (pullRequest.members.getValue("required_approving_review_count") as JsonInteger).value)
        // S8.3 Phase 10A（W2）: タグの ruleset A（作成。回避者は Publisher App だけ）と B（更新・削除。回避者なし）。App の ID は仮の値 0
        val problems = TagRulesets.fileProblems(read(TagRulesets.CREATION_FILE).toByteArray(), read(TagRulesets.PROTECTION_FILE).toByteArray(), null, null)
        assertEquals(listOf("the tag creation ruleset (A): the Publisher App ID (actor_id) is not set yet"), problems)
        val protection = JsonParser.parse(read(TagRulesets.PROTECTION_FILE).toByteArray(), 1 shl 20).value as JsonObject
        assertEquals(0, (protection.members.getValue("bypass_actors") as JsonArray).elements.size)
        assertFalse(Files.exists(template.resolve("github-settings/index-tags-ruleset.json")), "the single tag ruleset was split into A and B")
    }

    // S8.3 Phase 10A（W1）: 公開は Publisher App のトークンだけで行い、ジョブの GITHUB_TOKEN は読み取りだけ（書き込みの権限を持つジョブは無い）
    @Test
    fun releaseUsesThePublisherApp() {
        val publish = read(".github/workflows/publish.yml")
        // どのジョブの permissions にも contents: write が無い（文言の中の「contents: write」は数えない）
        for ((job, body) in jobs(publish)) {
            val block = Regex("\n    permissions:\n((?:      \\S.*\n)*)").find(body)?.groupValues?.get(1) ?: ""
            assertTrue(block.contains("contents: read"), "publish.yml/$job: the permissions block was not found ($block)")
            assertFalse(block.contains("contents: write"), "publish.yml/$job has a writing GITHUB_TOKEN")
        }
        val release = jobs(publish).getValue("release")
        assertTrue(release.contains("permissions:\n      contents: read\n"), release)
        assertFalse(release.contains("github.token") || release.contains("GH_TOKEN:"), "no GITHUB_TOKEN (and so no fallback to it) in the release job")
        assertTrue(release.contains("'{repositories: [\$repo], permissions: {contents: \"write\"}}'"), "the token is limited to contents: write on this repository")
        assertTrue(release.contains("trap cleanup EXIT") && release.contains("-X DELETE") && release.contains("https://api.github.com/installation/token"), "the token is revoked")
        assertTrue(release.indexOf("echo \"::add-mask::\$token\"") < release.indexOf("export GH_TOKEN=\"\$token\""), "masked before use")
        assertTrue(release.indexOf("sha256sum -c -") < release.indexOf("access_tokens"), "the token is obtained after the files are checked, just before its first use")
        assertTrue(release.indexOf("does not have exactly contents: write") < release.indexOf("gh api --paginate"), "the granted permissions are checked before any gh call")
        val check = jobs(publish).getValue("check")
        // S8.3 Phase 10B-2A（案 B'）: check ジョブは署名されたスナップショットと照合し（GITHUB_TOKEN、--actor workflow）、
        // release ジョブは Publisher App のトークンで、下書きの前と公開の直前に、check ジョブが見た ruleset から変わっていないことを確かめる
        assertTrue(check.contains("check-ruleset-snapshot --repo registry --live live-rulesets --repository-info live-rulesets/repository.json") && check.contains("--repository \"\$REPOSITORY\" --actor workflow"), "the rulesets are checked against the signed snapshot")
        // 版 3（S8.3 U-2）: ブランチの ruleset と既定のブランチも取得して照合し、release ジョブも確かめ直す
        assertTrue(check.contains("select(.target == \"tag\" or .target == \"branch\")") && check.contains("gh api \"repos/\$REPOSITORY\" > live-rulesets/repository.json"))
        assertTrue(release.contains("DEFAULT_BRANCH: \${{ needs.check.outputs.default_branch }}") && release.contains("gh api \"repos/\$REPOSITORY\" > rulesets-now/repository.json") && release.contains("\"main\": \"never\""))
        assertTrue(check.indexOf("check-ruleset-snapshot") < check.indexOf("Record the files to publish"))
        assertTrue(check.indexOf("check-ruleset-snapshot") < check.indexOf("echo \"rulesets=\$seen\""), "what was seen is recorded only after the check passed")
        assertTrue(publish.contains("rulesets: \${{ steps.rulesets.outputs.rulesets }}") && release.contains("RULESETS: \${{ needs.check.outputs.rulesets }}"))
        val draft = release.indexOf("recheck \"before creating the draft\"")
        val publishing = release.indexOf("recheck \"just before publishing\"")
        assertTrue(release.indexOf("export GH_TOKEN=\"\$token\"") < draft && draft < release.indexOf("gh release create") && release.indexOf("gh release create") < publishing && publishing < release.indexOf("-X PATCH"), "re-checked with the App token before the draft and just before publishing")
        assertFalse(release.contains("\$TOOL") || release.contains("gradlew"), "the release job still runs no tool")
    }

    // テンプレートには、鍵（秘密鍵・テスト専用の鍵）も、署名済みの index も無い
    @Test
    fun noKeysInTheTemplate() {
        Files.walk(template).use { stream ->
            for (path in stream.filter { Files.isRegularFile(it) }.toList()) {
                val text = Files.readString(path)
                assertFalse(text.contains("BEGIN PRIVATE KEY") || text.contains("test-only-key"), path.toString())
            }
        }
    }
}
