package com.creatrbypc.createanalytical.registrytool

import com.creatrbypc.createanalytical.install.GitHubReleases
import com.creatrbypc.createanalytical.project.registry.Owner
import com.creatrbypc.createanalytical.registrytool.ToolFixtures.entry
import com.creatrbypc.createanalytical.registrytool.ToolFixtures.library
import com.creatrbypc.createanalytical.registrytool.ToolFixtures.tree
import com.creatrbypc.createanalytical.registrytool.ToolFixtures.version
import com.creatrbypc.createanalytical.registrytool.ToolFixtures.with
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

// S8.3 Phase 2: コマンドとしての流れ（一時ディレクトリの中だけ。GitHub はメモリーの中の代わり）
class RegistryToolTest {
    @TempDir
    lateinit var directory: Path

    private val github = FakeGitHub()
    private val key = TestOnlyKey()
    private val clock = Instant.parse("2026-11-20T12:00:00Z")

    private fun run(vararg args: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = PrintStream(out, true, Charsets.UTF_8).use { o -> PrintStream(err, true, Charsets.UTF_8).use { e -> RegistryTool(o, e, { clock }, { github }).run(args.toList()) } }
        return Triple(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    private fun write(root: Path, tree: RegistryTree): Path {
        for ((path, bytes) in tree.files) {
            val file = root.resolve(path)
            Files.createDirectories(file.parent)
            Files.write(file, bytes)
        }
        return root
    }

    @Test
    fun registrationToPublicationWithTestKeys() {
        val zip = library("numbers", "3.0.0")
        github.repository("github:olga/numbers", 900, "olga", 2001)
        github.publish("github:olga/numbers", "3.0.0", zip)
        val numbers = entry("numbers", Owner("olga", 2001), 900, mapOf("3.0.0" to version(GitHubReleases.sha256Hex(zip), pullRequest = 7)))
        val base = write(directory.resolve("base"), tree(keys = listOf(key.trusted)))
        val head = write(directory.resolve("head"), tree(listOf(numbers), keys = listOf(key.trusted)))
        // PR の検証と承認
        val validated = run("validate-pr", "--base", "$base", "--head", "$head", "--pr", "7", "--author", "olga", "--author-id", "2001")
        assertEquals(0, validated.first, validated.second)
        assertTrue(validated.second.endsWith("OK\n"))
        val reviews = Files.writeString(directory.resolve("reviews.json"), """[{"user": {"login": "bea", "id": 2}, "state": "APPROVED", "commit_id": "${"a".repeat(40)}"}]""")
        val approvals = run("check-approvals", "--base", "$base", "--head", "$head", "--pr", "7", "--author", "olga", "--author-id", "2001", "--head-sha", "a".repeat(40), "--reviews", "$reviews")
        assertEquals(1, approvals.first)
        assertTrue(approvals.second.contains("error: 1 of the 2 approvals"), approvals.second)
        // 署名する人の手元: 作る → 署名する → 確かめる（テスト専用の鍵。ファイルは一時ディレクトリの中だけ）
        Files.createDirectories(head.resolve("signed"))
        val index = head.resolve("signed/index.json")
        val built = run("build-index", "--repo", "$head", "--out", "$index", "--generated", "2026-11-20T10:00:00Z")
        assertEquals(0, built.first, built.second)
        assertTrue(built.second.contains("notice: the first index: 1 packages, 1 versions"), built.second)
        val keyFile = Files.write(directory.resolve("test-only-key.pk8"), key.pair.private.encoded)
        val signed = run("sign", "--repo", "$head", "--index", "$index", "--key", "$keyFile", "--key-id", key.keyId, "--out", "${head.resolve("signed/index.json.sig")}")
        assertEquals(0, signed.first, signed.second)
        val verified = run("verify-index", "--repo", "$head", "--index", "$index", "--sig", "${head.resolve("signed/index.json.sig")}")
        assertEquals(0, verified.first, verified.second)
        assertTrue(verified.second.contains("serial 1"), verified.second)
        // 公開のワークフローの確認は、テスト専用の鍵を受け入れない
        val check = run("publish-check", "--repo", "$head")
        assertEquals(1, check.first)
        assertTrue(check.second.contains("a public index is never signed with a test key"), check.second)
        // 既にあるファイルは上書きしない
        assertEquals(2, run("build-index", "--repo", "$head", "--out", "$index").first)
        // 本番の鍵の ID では署名しない
        val production = run("sign", "--repo", "$head", "--index", "$index", "--key", "$keyFile", "--key-id", "createscript-registry-key-1", "--out", "${directory.resolve("x.sig")}")
        assertEquals(1, production.first)
        assertFalse(Files.exists(directory.resolve("x.sig")))
    }

    @Test
    fun usageAndOutput() {
        assertEquals(2, run().first)
        assertEquals(0, run("--help").first)
        assertEquals(2, run("unknown").first)
        assertEquals(2, run("check-repo").first)
        assertEquals(2, run("check-repo", "--repo", "${directory.resolve("missing")}").first)
        // テンプレートは、閉じた正しいレジストリ
        val template = run("check-repo", "--repo", "../registry-template")
        assertEquals(0, template.first, template.second)
        assertTrue(template.second.contains("notice: the registry is closed: it has 0 of the 3 maintainers"))
        // 出力の行は改行・制御文字を含まない（ワークフローコマンドとして読まれない）
        assertEquals("a\\u000a::set-output name=x::y\\u001b[31m", Report.safe("a\n::set-output name=x::y\u001b[31m"))
        val report = Report().also { it.error("x\n::error::forged") }
        assertEquals(listOf("error: x\\u000a::error::forged"), report.lines())
    }

    // S8.3 Phase 5（P4-M4）: GitHub の API の失敗（エラーの応答・空・壊れた JSON）は、承認の検査を失敗させる（成功にしない）
    @Test
    fun apiErrorsFailTheApprovalCheck() {
        val base = write(directory.resolve("base"), tree())
        val head = write(directory.resolve("head"), tree().with("README.md", "x\n".toByteArray()))
        for ((name, body) in listOf(
            "rate-limit" to """{"message": "API rate limit exceeded", "documentation_url": "https://docs.github.com"}""",
            "empty" to "",
            "broken" to "[{\"user\": ",
            "no-user" to """[{"state": "APPROVED", "commit_id": "${"a".repeat(40)}"}]""",
        )) {
            val reviews = Files.writeString(directory.resolve("$name.json"), body)
            val result = run("check-approvals", "--base", "$base", "--head", "$head", "--pr", "9", "--author", "dog7dog", "--author-id", "1", "--head-sha", "a".repeat(40), "--reviews", "$reviews")
            assertTrue(result.first != 0, "$name: ${result.second}")
            assertFalse(result.second.endsWith("OK\n"), name)
        }
        // --from-fork は true か false だけ
        assertEquals(2, run("validate-pr", "--base", "$base", "--head", "$head", "--pr", "9", "--author", "dog7dog", "--author-id", "1", "--from-fork", "yes").first)
        // check-settings: 雛形の ruleset は、App の ID がまだ無いことを示して失敗する
        val settings = run(
            "check-settings", "--ruleset", "../registry-template/github-settings/main-ruleset.json",
            "--tag-creation-ruleset", "../registry-template/github-settings/index-tags-creation-ruleset.json",
            "--tag-protection-ruleset", "../registry-template/github-settings/index-tags-protection-ruleset.json",
        )
        assertEquals(1, settings.first)
        assertTrue(settings.second.contains("integration_id is not set yet") && settings.second.contains("Publisher App ID (actor_id) is not set yet"), settings.second)
        // タグの ruleset を渡さなければ、使い方の誤り（A と B の両方が要る）
        assertEquals(2, run("check-settings", "--ruleset", "../registry-template/github-settings/main-ruleset.json").first)
        assertEquals(2, run("check-live-rulesets", "--repo", "$base", "--live", "$base", "--checks-app-id", "0", "--publisher-app-id", "2").first)
    }

    @Test
    fun refusesSymbolicLinksInTheTree() {
        val root = write(directory.resolve("repo"), tree())
        Files.createSymbolicLink(root.resolve("packages-link"), directory)
        val result = run("check-repo", "--repo", "$root")
        assertEquals(2, result.first)
        assertTrue(result.third.contains("is a symbolic link or not a regular file"), result.third)
    }
}
