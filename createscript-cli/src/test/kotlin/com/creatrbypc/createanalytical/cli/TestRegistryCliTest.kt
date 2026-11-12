package com.creatrbypc.createanalytical.cli

import com.creatrbypc.createanalytical.install.ArchiveCache
import com.creatrbypc.createanalytical.install.FetchFailure
import com.creatrbypc.createanalytical.install.GitHubReleases
import com.creatrbypc.createanalytical.install.IndexStore
import com.creatrbypc.createanalytical.install.PackageFetcher
import com.creatrbypc.createanalytical.install.RegistryTrust
import com.creatrbypc.createanalytical.install.ReleaseStore
import com.creatrbypc.createanalytical.project.PackageArchives
import com.creatrbypc.createanalytical.project.registry.IndexPackage
import com.creatrbypc.createanalytical.project.registry.IndexSignature
import com.creatrbypc.createanalytical.project.registry.IndexSignatures
import com.creatrbypc.createanalytical.project.registry.IndexVersion
import com.creatrbypc.createanalytical.project.registry.RegistryIndex
import com.creatrbypc.createanalytical.project.registry.RegistryIndexes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Duration
import java.time.Instant
import java.util.Base64

// S8.3 Phase 10A（W7）: テスト専用のクライアント（TestRegistryCli）だけが、テスト用のレジストリとテスト用の鍵を使える。本番の CLI は使わない
class TestRegistryCliTest {
    @TempDir
    lateinit var directory: Path

    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val keyId = "test-only-integration-1"
    private val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded.copyOfRange(pair.public.encoded.size - 32, pair.public.encoded.size))
    private val repository = "example/CreateScript-Registry-Test"

    private class Result(val code: Int, val out: String, val err: String)

    private val archive = PackageArchives.write(
        mapOf(
            "createscript.json" to """{"schemaVersion": 2, "kind": "library", "name": "numbers", "version": "3.0.0", "modules": ["numbers"]}""".toByteArray(),
            "modules/numbers.cscr" to "fun one(): Int {\n    return 1\n}\n".toByteArray(),
        ),
    )

    private val releases = object : ReleaseStore {
        override fun download(source: String, version: String): ByteArray = if (source == "github:owner/numbers" && version == "3.0.0") archive else throw FetchFailure.NotFound(URI("https://github.com/x"))

        override fun releaseInfo(source: String, version: String) = GitHubReleases.ReleaseInfo(GitHubReleases.sha256Hex(archive), true)
    }

    // テスト用の鍵で署名した index
    private val indexStore: IndexStore = run {
        val generated = Instant.ofEpochSecond(Instant.now().minus(Duration.ofHours(1)).epochSecond)
        val packages = mapOf("numbers" to IndexPackage("github:owner/numbers", 11, "numbers", "MIT", null, mapOf("3.0.0" to IndexVersion(GitHubReleases.sha256Hex(archive), emptyMap(), 7))))
        val index = RegistryIndexes.write(RegistryIndex(1, generated, generated.plus(Duration.ofDays(30)), packages))
        val signed = Signature.getInstance("Ed25519").run {
            initSign(pair.private)
            update(IndexSignatures.message(keyId, index))
            sign()
        }
        val signature = IndexSignatures.write(listOf(IndexSignature(keyId, signed)))
        IndexStore { name, _ -> if (name == "index.json") index else signature }
    }

    private fun project(name: String): Path {
        val root = Files.createDirectories(directory.resolve(name))
        Files.writeString(root.resolve("createscript.json"), """{"schemaVersion": 1, "name": "$name", "version": "0.1.0", "entry": "src/main.cscr"}""")
        return root
    }

    private fun capture(block: (PrintStream, PrintStream) -> Int): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = PrintStream(out, true, Charsets.UTF_8).use { o -> PrintStream(err, true, Charsets.UTF_8).use { e -> block(o, e) } }
        return Result(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    private fun options(cache: Path = directory.resolve("test-cache"), repo: String = repository, key: String = keyId) =
        listOf("--test-registry", repo, "--test-key-id", key, "--test-public-key", publicKey, "--test-cache-dir", cache.toString())

    private fun testBuild(vararg args: String, extra: List<String> = options()): Result =
        capture { o, e -> TestRegistryCli.run(extra + args.toList(), o, e, indexStore to releases, defaultCacheRoot = directory.resolve("default-cache")) }

    @Test
    fun onlyTheTestBuildUsesTheTestRegistry() {
        val root = project("app")
        val test = testBuild("install", "numbers@3.0.0", root.toString())
        assertEquals(ExitCode.OK, test.code, test.err)
        assertTrue(test.err.startsWith(TestRegistryCli.BANNER), test.err)
        assertTrue(test.err.contains("test registry $repository, test key $keyId"), test.err)
        assertTrue(test.out.contains("registry: reviewed (pull request #7; index serial 1)"), test.out)
        // テスト用のキャッシュだけを使う（既定の置き場所には何も作らない）
        assertTrue(Files.isDirectory(directory.resolve("test-cache/registry/example/createscript-registry-test")))
        assertFalse(Files.exists(directory.resolve("default-cache")))
        // 本番の CLI（本番の信頼の設定）は、同じ index・同じパッケージでも使わない（本番の鍵が無いので、通信せずに 69）
        val other = project("other")
        val production = capture { o, e -> InstallCommands(o, e, fetcher = { PackageFetcher(releases, ArchiveCache(directory.resolve("prod-cache"))) to null }).install(listOf("numbers@3.0.0", other.toString())) }
        assertEquals(ExitCode.UNAVAILABLE, production.code)
        assertTrue(production.err.contains("has not been published yet"), production.err)
        assertFalse(production.err.contains(TestRegistryCli.BANNER))
        // 本番の信頼の設定は変わっていない（鍵は空、テスト用の鍵は入れられない）
        assertFalse(RegistryTrust.official().configured)
    }

    @Test
    fun theTestBuildRefusesUnsafeSettings() {
        val root = project("app").toString()
        for ((extra, message) in listOf(
            options(repo = RegistryTrust.OFFICIAL_REPOSITORY) to "cannot use the official registry",
            options(repo = "DOG7DOG/createscript-registry") to "cannot use the official registry",
            options(key = "registry-key-1") to "must start with test-only-",
            options(cache = directory.resolve("default-cache")) to "must not be the default cache",
            options(cache = Paths.get("relative")) to "must be an absolute path",
            options().dropLast(2) to "--test-cache-dir is required",
            options() + listOf("--test-extra", "x") to "unknown option",
        )) {
            val result = testBuild("install", "numbers@3.0.0", root, extra = extra)
            assertEquals(ExitCode.USAGE, result.code, "$extra")
            assertTrue(result.err.contains(message), "$extra: ${result.err}")
            assertTrue(result.err.startsWith(TestRegistryCli.BANNER))
        }
        assertTrue(testBuild("run", root).err.contains("only runs install"))
    }

    // テスト専用のクライアントは、本番の出力（main・installDist）に入らない。本番のソースは、テスト用の信頼の設定を参照しない
    @Test
    fun theTestBuildIsNotInTheProductionOutput() {
        val location = Class.forName("com.creatrbypc.createanalytical.cli.TestRegistryCliKt").protectionDomain.codeSource.location.path
        assertTrue(location.contains("/test"), location)
        val main = Paths.get("build/classes/kotlin/main")
        assertTrue(Files.isDirectory(main))
        Files.walk(main).use { s -> assertFalse(s.anyMatch { it.fileName.toString().startsWith("TestRegistryCli") }) }
        for (module in listOf("src/main/kotlin", "../createscript-install/src/main/kotlin")) {
            Files.walk(Paths.get(module)).use { s ->
                for (file in s.filter { Files.isRegularFile(it) }.toList()) {
                    val text = Files.readString(file)
                    assertFalse(text.contains("TestRegistryCli") || text.contains("--test-registry") || text.contains("TEST-REGISTRY"), "$file")
                }
            }
        }
        // 本番の CLI のヘルプに、レジストリ・鍵を変えるオプションは無い
        val help = capture { o, e -> CreateScriptCli(o, e).execute(listOf("--help")) }.out
        assertFalse(help.contains("--test-") || help.contains("public-key") || help.contains("registry-url"), help)
    }
}
