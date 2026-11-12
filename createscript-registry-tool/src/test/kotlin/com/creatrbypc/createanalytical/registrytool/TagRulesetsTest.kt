package com.creatrbypc.createanalytical.registrytool

import com.creatrbypc.createanalytical.install.GitHubReleases
import com.creatrbypc.createanalytical.registrytool.ToolFixtures.entry
import com.creatrbypc.createanalytical.registrytool.ToolFixtures.library
import com.creatrbypc.createanalytical.registrytool.ToolFixtures.tree
import com.creatrbypc.createanalytical.registrytool.ToolFixtures.version
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

// S8.3 Phase 10A: index-* のタグの ruleset A・B の検査（W2・W3）と、テスト専用の registry-tool（W7）。
// GitHub が実際にこれらを強制するかは、ここでは確かめていない（Phase 10B）
class TagRulesetsTest {
    @TempDir
    lateinit var directory: Path

    private val publisher = 222L
    private val checks = 111L

    private fun creation(bypass: String = """[{"actor_id": $publisher, "actor_type": "Integration", "bypass_mode": "always"}]""", rules: String = """[{"type": "creation"}]""", target: String = "tag", enforcement: String = "active", include: String = """["refs/tags/index-*"]""") =
        """{"name": "index tags: creation", "target": "$target", "enforcement": "$enforcement", "bypass_actors": $bypass, "conditions": {"ref_name": {"include": $include, "exclude": []}}, "rules": $rules}"""

    private fun protection(bypass: String? = "[]", rules: String = """[{"type": "deletion"}, {"type": "update"}, {"type": "non_fast_forward"}]""") =
        """{"name": "index tags: update and deletion", "target": "tag", "enforcement": "active"${if (bypass == null) "" else """, "bypass_actors": $bypass"""}, "conditions": {"ref_name": {"include": ["refs/tags/index-*"], "exclude": []}}, "rules": $rules}"""

    private fun problems(a: String = creation(), b: String = protection(), p: Long? = publisher, c: Long? = checks) = TagRulesets.fileProblems(a.toByteArray(), b.toByteArray(), p, c)

    @Test
    fun rulesetAAllowsOnlyThePublisherApp() {
        assertEquals(emptyList<String>(), problems())
        assertTrue(problems(a = creation(bypass = "[]")).single().contains("exactly one actor"))
        assertTrue(problems(a = creation(bypass = """[{"actor_id": $checks, "actor_type": "Integration", "bypass_mode": "always"}]""")).any { it.contains("not the Registry Publisher App") })
        assertTrue(problems(a = creation(bypass = """[{"actor_id": $checks, "actor_type": "Integration", "bypass_mode": "always"}]"""), p = null).any { it.contains("must not be the Registry Checks App") })
        assertTrue(problems(a = creation(bypass = """[{"actor_id": 15368, "actor_type": "Integration", "bypass_mode": "always"}]"""), p = null).any { it.contains("must not be GitHub Actions") })
        assertTrue(problems(a = creation(bypass = """[{"actor_id": 5, "actor_type": "RepositoryRole", "bypass_mode": "always"}]"""), p = null).any { it.contains("actor_type Integration") })
        assertTrue(problems(a = creation(bypass = """[{"actor_id": $publisher, "actor_type": "Integration", "bypass_mode": "pull_request"}]""")).any { it.contains("bypass mode must be always") })
        assertTrue(problems(a = creation(bypass = """[{"actor_id": $publisher, "actor_type": "Integration", "bypass_mode": "always"}, {"actor_type": "OrganizationAdmin", "bypass_mode": "always"}]""")).any { it.contains("exactly one actor") })
        assertTrue(problems(a = creation(rules = """[{"type": "creation"}, {"type": "update"}]""")).any { it.contains("exactly [creation]") })
        assertTrue(problems(a = creation(target = "branch")).any { it.contains("target must be tag") })
        assertTrue(problems(a = creation(enforcement = "disabled")).any { it.contains("must be active") })
        assertTrue(problems(a = creation(include = """["refs/tags/*"]""")).any { it.contains("include must be exactly") })
        assertTrue(problems(p = 111L, c = 111L).any { it.contains("must be different Apps") })
        assertTrue(problems(a = "not json").single().contains("not a JSON object"))
    }

    @Test
    fun rulesetBHasNoBypass() {
        assertTrue(problems(b = protection(bypass = """[{"actor_id": $publisher, "actor_type": "Integration", "bypass_mode": "always"}]""")).single().contains("must be empty"))
        assertTrue(problems(b = protection(bypass = """[{"actor_type": "OrganizationAdmin", "bypass_mode": "always"}]""")).single().contains("must be empty"))
        assertTrue(problems(b = protection(rules = """[{"type": "deletion"}, {"type": "update"}]""")).single().contains("exactly update, deletion and non_fast_forward"))
        // 応答に bypass_actors が無い（読めない）ときは、確かめられないので失敗する
        assertTrue(TagRulesets.protectionProblems(TagRulesets.parse(protection(bypass = null).toByteArray()), exact = false).single().contains("cannot be verified"))
        val hidden = TagRulesets.parse(creation().replace(""""bypass_actors": [{"actor_id": $publisher, "actor_type": "Integration", "bypass_mode": "always"}], """, "").toByteArray())
        assertTrue(TagRulesets.creationProblems(hidden, publisher, checks, exact = false).single().contains("not visible"))
    }

    @Test
    fun liveRulesetsAreComparedWithTheFiles() {
        val repo = Files.createDirectories(directory.resolve("repo/github-settings")).parent
        Files.writeString(repo.resolve(TagRulesets.CREATION_FILE), creation())
        Files.writeString(repo.resolve(TagRulesets.PROTECTION_FILE), protection())
        val live = Files.createDirectories(directory.resolve("live"))
        Files.writeString(live.resolve("ruleset-1.json"), creation().replace("{\"name\"", "{\"id\": 1, \"source_type\": \"Repository\", \"name\""))
        Files.writeString(live.resolve("ruleset-2.json"), protection())
        fun run(vararg extra: String): Pair<Int, String> {
            val out = ByteArrayOutputStream()
            val code = PrintStream(out, true, Charsets.UTF_8).use { o -> RegistryTool(o, o, { Instant.now() }, { FakeGitHub() }).run(listOf("check-live-rulesets", "--repo", "$repo", "--live", "$live") + extra) }
            return code to out.toString(Charsets.UTF_8)
        }
        assertEquals(0, run("--checks-app-id", "$checks", "--publisher-app-id", "$publisher").first)
        val other = run("--checks-app-id", "$checks", "--publisher-app-id", "999")
        assertEquals(1, other.first)
        assertTrue(other.second.contains("not the Registry Publisher App (999)"), other.second)
        assertEquals(2, run("--checks-app-id", "$checks").first)
        Files.writeString(live.resolve("ruleset-2.json"), protection(bypass = null))
        val hidden = run("--checks-app-id", "$checks", "--publisher-app-id", "$publisher")
        assertEquals(1, hidden.first)
        assertTrue(hidden.second.contains("cannot be verified"), hidden.second)
        Files.delete(live.resolve("ruleset-2.json"))
        Files.delete(live.resolve("ruleset-1.json"))
        assertTrue(run("--checks-app-id", "$checks", "--publisher-app-id", "$publisher").second.contains("no ruleset of the repository could be read"))
    }

    // W7: テスト専用の registry-tool だけが、publish-check でテスト用の鍵を受け入れる。本番の RegistryTool は拒否する
    @Test
    fun onlyTheTestOnlyToolAcceptsTestKeys() {
        val key = TestOnlyKey()
        val zip = library("numbers", "3.0.0")
        val numbers = entry("numbers", com.creatrbypc.createanalytical.project.registry.Owner("olga", 2001), 900, mapOf("3.0.0" to version(GitHubReleases.sha256Hex(zip), pullRequest = 7)))
        val root = directory.resolve("registry")
        for ((path, bytes) in tree(listOf(numbers), keys = listOf(key.trusted)).files) {
            Files.createDirectories(root.resolve(path).parent)
            Files.write(root.resolve(path), bytes)
        }
        Files.createDirectories(root.resolve("signed"))
        val clock = Instant.parse("2026-11-20T12:00:00Z")
        val err = ByteArrayOutputStream()
        val out = ByteArrayOutputStream()
        fun tool(allow: Boolean) = RegistryTool(PrintStream(out, true), PrintStream(err, true), { clock }, { FakeGitHub() }, allowTestKeys = allow)
        assertEquals(0, tool(false).run(listOf("build-index", "--repo", "$root", "--out", "${root.resolve("signed/index.json")}", "--generated", "2026-11-20T10:00:00Z")))
        val keyFile = Files.write(directory.resolve("test-only.pk8"), key.pair.private.encoded)
        assertEquals(0, tool(false).run(listOf("sign", "--repo", "$root", "--index", "${root.resolve("signed/index.json")}", "--key", "$keyFile", "--key-id", key.keyId, "--out", "${root.resolve("signed/index.json.sig")}")))
        out.reset()
        assertEquals(1, tool(false).run(listOf("publish-check", "--repo", "$root")), "the production tool refuses test keys")
        assertTrue(out.toString().contains("never signed with a test key"))
        val testErr = ByteArrayOutputStream()
        val code = TestRegistryTool.run(listOf("publish-check", "--repo", "$root"), "example/CreateScript-Registry-Test", PrintStream(testErr, true)) { allow -> tool(allow) }
        assertEquals(0, code, out.toString() + err.toString())
        assertTrue(testErr.toString().startsWith(TestRegistryTool.BANNER))
        // 公式のレジストリのリポジトリでは動かない
        assertEquals(2, TestRegistryTool.run(listOf("publish-check", "--repo", "$root"), "dog7dog/CreateScript-Registry", PrintStream(ByteArrayOutputStream(), true)) { allow -> tool(allow) })
    }

    // W7: テスト専用のビルドは、本番の出力（main）に入らない。本番の main は allowTestKeys を渡さない
    @Test
    fun theTestOnlyToolIsNotInTheProductionOutput() {
        val location = Class.forName("com.creatrbypc.createanalytical.registrytool.TestRegistryToolKt").protectionDomain.codeSource.location.path
        assertTrue(location.contains("/test"), location)
        val main = Paths.get("build/classes/kotlin/main")
        assertTrue(Files.isDirectory(main))
        Files.walk(main).use { s -> assertFalse(s.anyMatch { it.fileName.toString().startsWith("TestRegistryTool") }) }
        val sources = Paths.get("src/main/kotlin")
        Files.walk(sources).use { s ->
            for (file in s.filter { Files.isRegularFile(it) }.toList()) {
                val text = Files.readString(file).lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")
                assertFalse(text.contains("allowTestKeys = true"), "$file")
                assertFalse(text.contains("TestRegistryTool"), "$file")
            }
        }
    }
}
