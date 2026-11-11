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
                if (body.contains("secrets.")) {
                    assertFalse(body.contains("actions/checkout") || body.contains("gradlew") || body.contains("\$TOOL") || body.contains("head/"), "$name/$job uses a secret and touches code or data")
                    assertTrue(body.contains("environment: registry-checks"), "$name/$job")
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
        val tags = JsonParser.parse(read("github-settings/index-tags-ruleset.json").toByteArray(), 1 shl 20).value as JsonObject
        assertEquals(0, (tags.members.getValue("bypass_actors") as JsonArray).elements.size)
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
