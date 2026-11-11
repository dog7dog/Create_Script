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

    @Test
    fun workflowsFollowTheSafetyRules() {
        for (name in listOf("validate.yml", "publish.yml")) {
            val text = read(".github/workflows/$name")
            // 外部のアクションは、すべてコミットの SHA で固定
            val uses = Regex("uses:\\s*(\\S+)").findAll(text).map { it.groupValues[1] }.toList()
            assertTrue(uses.isNotEmpty())
            for (action in uses) assertTrue(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+@[0-9a-f]{40}").matches(action), "$name: $action is not pinned to a commit SHA")
            // 既定の権限は無し。秘密情報を使わない。チェックアウトは資格情報を残さない
            assertTrue(text.contains("\npermissions: {}\n"), name)
            assertFalse(text.contains("secrets."), "$name uses secrets")
            assertEquals(Regex("uses: actions/checkout@").findAll(text).count(), Regex("persist-credentials: false").findAll(text).count(), name)
            // ツールは固定のコミットから作る。設定されるまで（すべて 0 の間）は、必ず失敗する
            assertTrue(text.contains("TOOL_COMMIT: \"0000000000000000000000000000000000000000\""), name)
            assertTrue(text.contains("if [ \"\$TOOL_COMMIT\" = \"0000000000000000000000000000000000000000\" ]"), name)
            // ツールの出力を、ワークフローコマンドとして解釈させない
            assertTrue(text.contains("::stop-commands::"), name)
            // GitHub の式を run の中に直接書かない（値は env で渡す）
            for (block in Regex("run: \\|\\n((?:\\s{10,}.*\\n)+)").findAll(text)) assertFalse(block.groupValues[1].contains("\${{"), "$name: an expression inside run")
            // 秘密鍵・署名の手順は、ワークフローに無い
            assertFalse(text.contains(" sign "), "$name signs")
            assertFalse(text.contains("PRIVATE KEY"), name)
        }
        val validate = read(".github/workflows/validate.yml")
        // PR の検証は、main のワークフローで動き（pull_request_target）、PR のファイルはデータとしてだけ使う。書き込みの権限は無い
        assertTrue(validate.contains("pull_request_target:"))
        assertFalse(Regex("^\\s*pull_request:", RegexOption.MULTILINE).containsMatchIn(validate))
        assertFalse(validate.contains("contents: write"))
        assertFalse(validate.contains("GH_TOKEN"))
        assertFalse(Regex("working-directory: head|head/[^\\s]*\\.sh|bash head|\\./head").containsMatchIn(validate), "nothing from the pull request is run")
        val publish = read(".github/workflows/publish.yml")
        assertTrue(publish.contains("environment: publish"))
        assertTrue(publish.contains("publish-check --repo registry"))
        assertTrue(publish.contains("check-release"))
        assertTrue(publish.indexOf("publish-check") < publish.indexOf("gh release create"), "the check comes before the release")
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
