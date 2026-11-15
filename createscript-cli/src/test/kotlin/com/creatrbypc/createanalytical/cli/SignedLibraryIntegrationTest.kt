package com.creatrbypc.createanalytical.cli

import com.creatrbypc.createanalytical.install.FetchFailure
import com.creatrbypc.createanalytical.install.GitHubReleases
import com.creatrbypc.createanalytical.install.IndexStore
import com.creatrbypc.createanalytical.install.ReleaseStore
import com.creatrbypc.createanalytical.project.registry.IndexPackage
import com.creatrbypc.createanalytical.project.registry.IndexSignature
import com.creatrbypc.createanalytical.project.registry.IndexSignatures
import com.creatrbypc.createanalytical.project.registry.IndexVersion
import com.creatrbypc.createanalytical.project.registry.RegistryIndex
import com.creatrbypc.createanalytical.project.registry.RegistryIndexes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Duration
import java.time.Instant
import java.util.Base64

// S8.3 統合試験（ローカル、GitHub に接続しない）: 署名付きライブラリの 作成（pack）→ index の作成と署名 → 署名の検証を伴う install →
// import → 実行、と、署名・index・パッケージの改ざんの拒否。
// 通る製品のコード: CreateScriptCli（pack・init・run・本番の install の拒否）、InstallCommands・RegistryClient（Ed25519・期限・履歴）・
// RegistryCache・PackageFetcher（署名された SHA-256・digest）・ArchiveCache・インストールとプロジェクトの更新、run の前の確かめ直し。
// 差し替えるのは、テスト専用の入口 TestRegistryCli に渡す、GitHub から取得する 2 つの部分（IndexStore・ReleaseStore）だけ。
// index は registry-tool の build-index・sign と同じ形（RegistryIndexes・IndexSignatures）で、メモリーの中の一時的な鍵（test-only-）で署名する。
// registry-tool の build-index・sign・publish-check の経路は、registry-tool の試験（TagRulesetsTest など）で確かめている
class SignedLibraryIntegrationTest {
    @TempDir
    lateinit var directory: Path

    private val keyId = "test-only-integration-local-1"
    private val pair: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded.copyOfRange(pair.public.encoded.size - 32, pair.public.encoded.size))
    private val repository = "example/CreateScript-Registry-Local"
    private val source = "github:example-owner/greetings"

    private class Result(val code: Int, val out: String, val err: String)

    private fun capture(block: (PrintStream, PrintStream) -> Int): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = PrintStream(out, true, Charsets.UTF_8).use { o -> PrintStream(err, true, Charsets.UTF_8).use { e -> block(o, e) } }
        return Result(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    private fun cli(vararg args: String) = capture { o, e -> CreateScriptCli(o, e).execute(args.toList()) }

    // ライブラリを作り、本番の CLI の pack で正規形の ZIP にする
    private fun pack(name: String, twice: String, hello: String): ByteArray {
        val lib = Files.createDirectories(directory.resolve("lib-$name-${System.nanoTime()}/modules")).parent
        Files.writeString(lib.resolve("createscript.json"), """{"schemaVersion": 2, "kind": "library", "name": "greetings", "version": "1.0.0", "license": "MIT", "modules": ["greetings"]}""")
        Files.writeString(lib.resolve("modules/greetings.cscr"), "fun twice(n: Int): Int {\n    return $twice\n}\n\nfun hello(): String {\n    return \"$hello\"\n}\n")
        Files.writeString(lib.resolve("LICENSE"), "MIT License\n")
        val zip = directory.resolve("$name-${System.nanoTime()}.zip")
        val packed = cli("pack", lib.toString(), "--output", zip.toString())
        assertEquals(0, packed.code, packed.err)
        return Files.readAllBytes(zip)
    }

    private val good by lazy { pack("good", "n * 2", "hello from greetings") }
    private val evil by lazy { pack("evil", "666", "evil") }

    private fun index(sha256: String, serial: Long = 2, generated: Instant = Instant.ofEpochSecond(Instant.now().minus(Duration.ofHours(1)).epochSecond), days: Long = 30): ByteArray =
        RegistryIndexes.write(RegistryIndex(serial, generated, generated.plus(Duration.ofDays(days)),
            mapOf("greetings" to IndexPackage(source, 1001, "Local integration test library", "MIT", null, mapOf("1.0.0" to IndexVersion(sha256, emptyMap(), 1))))))

    private fun sign(bytes: ByteArray, key: KeyPair = pair, id: String = keyId): ByteArray = IndexSignatures.write(listOf(IndexSignature(id, Signature.getInstance("Ed25519").run {
        initSign(key.private)
        update(IndexSignatures.message(id, bytes))
        sign()
    })))

    private fun stores(indexBytes: ByteArray, signature: ByteArray, archive: ByteArray): Pair<IndexStore, ReleaseStore> =
        IndexStore { name, _ -> if (name == "index.json") indexBytes else signature } to object : ReleaseStore {
            override fun download(source: String, version: String): ByteArray =
                if (source == this@SignedLibraryIntegrationTest.source && version == "1.0.0") archive else throw FetchFailure.NotFound(URI("https://github.com/x"))

            // GitHub が報告する digest は、配信しているファイルのもの（差し替えられた資産でも、GitHub はその資産の digest を返す）
            override fun releaseInfo(source: String, version: String) = GitHubReleases.ReleaseInfo(GitHubReleases.sha256Hex(archive), true)
        }

    private fun app(): Path {
        val app = directory.resolve("app-${System.nanoTime()}")
        assertEquals(0, cli("init", app.toString(), "--name", "app").code)
        Files.writeString(app.resolve("src/main.cscr"), "import greetings\n\nprintln(greetings.twice(21))\nprintln(greetings.hello())\n")
        return app
    }

    private fun install(app: Path, stores: Pair<IndexStore, ReleaseStore>, cache: Path = directory.resolve("cache-${System.nanoTime()}")): Result = capture { o, e ->
        TestRegistryCli.run(listOf("--test-registry", repository, "--test-key-id", keyId, "--test-public-key", publicKey, "--test-cache-dir", cache.toString(), "install", "greetings@1.0.0", app.toString()),
            o, e, stores, defaultCacheRoot = directory.resolve("not-the-default-cache"))
    }

    private fun assertRefused(app: Path, result: Result, expected: String) {
        val manifest = Files.readString(app.resolve("createscript.json"))
        assertNotEquals(0, result.code, result.err)
        assertTrue(result.err.contains(expected), result.err)
        assertFalse(Files.exists(app.resolve(".createscript/packages/greetings")), "nothing installed")
        assertFalse(Files.exists(app.resolve("createscript.lock")), "no lock written")
        assertFalse(manifest.contains("greetings"), "manifest unchanged")
    }

    @Test
    fun aSignedLibraryIsInstalledImportedAndRun() {
        val sha = GitHubReleases.sha256Hex(good)
        val app = app()
        val installed = install(app, stores(index(sha), sign(index(sha)), good))
        assertEquals(0, installed.code, installed.err)
        assertTrue(installed.out.contains("registry: reviewed (pull request #1; index serial 2)"), installed.out)
        assertTrue(Files.readString(app.resolve("createscript.lock")).contains(sha))
        assertEquals(0, cli("lock", "check", app.toString()).code)
        val run = cli("run", app.toString())
        assertEquals(0, run.code, run.err)
        assertEquals("42\nhello from greetings\n", run.out)
        // インストールの後のモジュールの改変は、run の前の確かめ直しで止まる
        Files.writeString(app.resolve(".createscript/packages/greetings/1.0.0/modules/greetings.cscr"), "fun twice(n: Int): Int {\n    return 0\n}\n\nfun hello(): String {\n    return \"x\"\n}\n")
        val tampered = cli("run", app.toString())
        assertNotEquals(0, tampered.code)
        assertTrue(tampered.err.contains("was changed"), tampered.err)
    }

    @Test
    fun tamperedSignaturesAndIndexesAreRefused() {
        val sha = GitHubReleases.sha256Hex(good)
        val signature = sign(index(sha))
        // 署名の 1 ビット
        val flipped = IndexSignatures.parse(signature).value!!.single().let { s -> IndexSignatures.write(listOf(IndexSignature(s.keyId, s.signature.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }))) }
        var app = app()
        assertRefused(app, install(app, stores(index(sha), flipped, good)), "CS_REG_SIGNATURE_INVALID")
        // index の改ざん（説明・SHA-256 を悪意のあるパッケージのものへ）
        app = app()
        assertRefused(app, install(app, stores(String(index(sha)).replace("Local integration test library", "Local integration test LIBRARY").toByteArray(), signature, good)), "CS_REG_SIGNATURE_INVALID")
        app = app()
        assertRefused(app, install(app, stores(String(index(sha)).replace(sha, GitHubReleases.sha256Hex(evil)).toByteArray(), signature, evil)), "CS_REG_SIGNATURE_INVALID")
        // 別の鍵（同じ鍵の ID を名乗る）で署名した index
        app = app()
        val other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        assertRefused(app, install(app, stores(index(sha), sign(index(sha), other), good)), "CS_REG_SIGNATURE_INVALID")
        // 期限の切れた index
        app = app()
        val old = index(sha, generated = Instant.ofEpochSecond(Instant.now().minus(Duration.ofDays(40)).epochSecond))
        assertRefused(app, install(app, stores(old, sign(old), good)), "CS_REG_INDEX_EXPIRED")
    }

    @Test
    fun tamperedPackagesAreRefused() {
        val sha = GitHubReleases.sha256Hex(good)
        val signedIndex = stores(index(sha), sign(index(sha)), good).first
        fun serving(archive: ByteArray) = signedIndex to stores(index(sha), sign(index(sha)), archive).second
        var app = app()
        assertRefused(app, install(app, serving(evil)), "does not match the SHA-256 reviewed in the CreateScript-Registry")
        app = app()
        val corrupted = good.copyOf().also { it[100] = (it[100].toInt() xor 0xff).toByte() }
        assertRefused(app, install(app, serving(corrupted)), "does not match the SHA-256 reviewed in the CreateScript-Registry")
    }

    // ロールバックへの耐性: 一度受け入れた index より古い（serial の小さい）署名付きの index は使わず、受け入れ済みの新しい index を使い続ける
    @Test
    fun anOlderIndexIsNotUsedAfterANewerOne() {
        val sha = GitHubReleases.sha256Hex(good)
        val cache = directory.resolve("shared-cache")
        val newer = index(sha, serial = 5)
        assertEquals(0, install(app(), stores(newer, sign(newer), good), cache).code)
        val older = index(sha, serial = 4)
        val result = install(app(), stores(older, sign(older), good), cache)
        assertEquals(0, result.code, result.err)
        assertTrue(result.err.contains("the registry sent an older index than the one accepted before; the one accepted before is used (serial 5"), result.err)
        assertTrue(result.out.contains("index serial 5"), result.out)
    }

    @Test
    fun theProductionClientDoesNotUseTheTestRegistry() {
        val app = app()
        val result = cli("install", "greetings@1.0.0", app.toString(), "--offline")
        assertEquals(ExitCode.UNAVAILABLE, result.code, result.err)
        assertTrue(result.err.contains("has not been published yet"), result.err)
        assertFalse(Files.exists(app.resolve(".createscript/packages")))
    }
}
