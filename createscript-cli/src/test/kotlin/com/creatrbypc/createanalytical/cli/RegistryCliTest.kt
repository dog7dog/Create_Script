package com.creatrbypc.createanalytical.cli

import com.creatrbypc.createanalytical.install.ArchiveCache
import com.creatrbypc.createanalytical.install.FetchFailure
import com.creatrbypc.createanalytical.install.GitHubReleases
import com.creatrbypc.createanalytical.install.IndexStore
import com.creatrbypc.createanalytical.install.PackageFetcher
import com.creatrbypc.createanalytical.install.RegistryCache
import com.creatrbypc.createanalytical.install.RegistryClient
import com.creatrbypc.createanalytical.install.RegistryTrust
import com.creatrbypc.createanalytical.install.ReleaseStore
import com.creatrbypc.createanalytical.project.PackageArchives
import com.creatrbypc.createanalytical.project.registry.IndexPackage
import com.creatrbypc.createanalytical.project.registry.IndexSignature
import com.creatrbypc.createanalytical.project.registry.IndexSignatures
import com.creatrbypc.createanalytical.project.registry.IndexVersion
import com.creatrbypc.createanalytical.project.registry.RegistryIndex
import com.creatrbypc.createanalytical.project.registry.RegistryIndexes
import com.creatrbypc.createanalytical.project.registry.TrustedKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Duration
import java.time.Instant

// S8.3 Phase 3: createscript install <名前>@<版>（テスト専用の鍵で署名したメモリーの中の index。本番の URL・鍵は使わない）
class RegistryCliTest {
    @TempDir
    lateinit var directory: Path

    private class Result(val code: Int, val out: String, val err: String)

    private class Releases : ReleaseStore {
        val assets = HashMap<String, ByteArray>()
        var network = true

        override fun download(source: String, version: String): ByteArray {
            if (!network) error("the network was used")
            return assets["$source@$version"] ?: throw FetchFailure.NotFound(URI("https://github.com/x"))
        }

        override fun releaseInfo(source: String, version: String): GitHubReleases.ReleaseInfo {
            if (!network) error("the network was used")
            return GitHubReleases.ReleaseInfo(assets["$source@$version"]?.let(GitHubReleases::sha256Hex), true)
        }
    }

    // テスト専用の鍵（実行のたびにメモリーの中で作り、保存しない）
    private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val keyId = "test-only-cli-key"
    private val trust = RegistryTrust("example/test-registry", listOf(TrustedKey(keyId, pair.public.encoded.copyOfRange(pair.public.encoded.size - 32, pair.public.encoded.size))))
    private val generated = Instant.now().minus(Duration.ofHours(1)).let { Instant.ofEpochSecond(it.epochSecond) }

    private val releases = Releases()
    private var index: ByteArray? = null
    private var signature: ByteArray? = null
    private var indexNetwork = true
    private lateinit var project: Path
    private lateinit var numbers: ByteArray
    private lateinit var vector: ByteArray

    private fun library(name: String, version: String, dependencies: String = "", body: String = "fun one(): Int {\n    return 1\n}\n"): ByteArray {
        val module = name.replace('-', '_')
        val manifest = """{"schemaVersion": 2, "kind": "library", "name": "$name", "version": "$version", "modules": ["$module"], "dependencies": {$dependencies}}"""
        return PackageArchives.write(mapOf("createscript.json" to manifest.toByteArray(), "modules/$module.cscr" to body.toByteArray()))
    }

    private fun publishIndex(packages: Map<String, IndexPackage>, serial: Long) {
        val bytes = RegistryIndexes.write(RegistryIndex(serial, generated.plusSeconds(serial), generated.plus(Duration.ofDays(30)), packages))
        val signed = Signature.getInstance("Ed25519").run {
            initSign(pair.private)
            update(IndexSignatures.message(keyId, bytes))
            sign()
        }
        index = bytes
        signature = IndexSignatures.write(listOf(IndexSignature(keyId, signed)))
    }

    private fun registered(vectorYanked: Boolean = false) = mapOf(
        "numbers" to IndexPackage("github:owner/numbers", 11, "numbers", "MIT", null, mapOf("3.0.0" to IndexVersion(GitHubReleases.sha256Hex(numbers), emptyMap(), 7))),
        "vector-math" to IndexPackage(
            "github:owner/vector-math", 12, "vectors", "MIT", null,
            mapOf("1.2.0" to IndexVersion(GitHubReleases.sha256Hex(vector), mapOf("numbers" to "3.0.0"), 12, if (vectorYanked) com.creatrbypc.createanalytical.project.registry.Withdrawal("broken", java.time.LocalDate.parse("2026-11-01")) else null)),
        ),
    )

    @BeforeEach
    fun setUp() {
        project = Files.createDirectories(directory.resolve("app"))
        Files.writeString(project.resolve("createscript.json"), """{"schemaVersion": 1, "name": "app", "version": "0.1.0", "entry": "src/main.cscr"}""")
        Files.createDirectories(project.resolve("src"))
        Files.writeString(project.resolve("src/main.cscr"), "import vector_math\nprintln(vector_math.one() + 24)\n")
        numbers = library("numbers", "3.0.0")
        vector = library("vector-math", "1.2.0", "\"numbers\": {\"version\": \"3.0.0\", \"source\": \"github:owner/numbers\"}")
        releases.assets["github:owner/numbers@3.0.0"] = numbers
        releases.assets["github:owner/vector-math@1.2.0"] = vector
        publishIndex(registered(), 1)
    }

    private fun capture(block: (PrintStream, PrintStream) -> Int): Result {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = PrintStream(out, true, Charsets.UTF_8).use { o -> PrintStream(err, true, Charsets.UTF_8).use { e -> block(o, e) } }
        return Result(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }

    private val store = IndexStore { name, _ ->
        if (!indexNetwork) throw FetchFailure.Network("offline (test)")
        (if (name == "index.json") index else signature) ?: throw FetchFailure.NotFound(URI("https://github.com/x"))
    }

    private fun install(vararg args: String): Result = capture { o, e ->
        InstallCommands(
            o, e,
            registry = { RegistryClient(trust, store, RegistryCache(directory.resolve("cache"), trust.repository)) to null },
            fetcher = { PackageFetcher(releases, ArchiveCache(directory.resolve("cache"))) to null },
        ).install(args.toList())
    }

    private fun cli(vararg args: String): Result = capture { o, e -> CreateScriptCli(o, e).execute(args.toList()) }

    @Test
    fun installsByNameAndRuns() {
        val result = install("vector-math@1.2.0", project.toString())
        assertEquals(ExitCode.OK, result.code, result.err)
        assertTrue(result.out.contains("Installed vector-math 1.2.0 from github:owner/vector-math -> .createscript/packages/vector-math/1.2.0\n"), result.out)
        assertTrue(result.out.contains("  registry: reviewed (pull request #12; index serial 1)\n"), result.out)
        assertTrue(result.out.contains("Updated createscript.json (dependency vector-math 1.2.0 from github:owner/vector-math)"), result.out)
        assertEquals("", result.err)
        // run は通信しない（Release も index も使えない状態で動く）
        releases.network = false
        indexNetwork = false
        assertEquals("25\n", cli("run", project.toString()).out)
        // オフラインでも、保存した index とキャッシュのアーカイブで、別のプロジェクトに入れられる
        val second = Files.createDirectories(directory.resolve("second"))
        Files.writeString(second.resolve("createscript.json"), """{"schemaVersion": 1, "name": "second", "version": "0.1.0", "entry": "src/main.cscr"}""")
        val offline = install("vector-math@1.2.0", second.toString(), "--offline")
        assertEquals(ExitCode.OK, offline.code, offline.err)
        assertTrue(offline.err.startsWith("note: using the registry index saved before"), offline.err)
    }

    @Test
    fun refusalsAndExitCodes() {
        val manifest = Files.readString(project.resolve("createscript.json"))
        // 65: 未登録・取り下げ
        val unknown = install("vectors@1.0.0", project.toString())
        assertEquals(ExitCode.INVALID_INPUT, unknown.code)
        assertTrue(unknown.err.contains("vectors is not in the CreateScript-Registry"), unknown.err)
        publishIndex(registered(vectorYanked = true), 2)
        val yanked = install("vector-math@1.2.0", project.toString())
        assertEquals(ExitCode.INVALID_INPUT, yanked.code)
        assertTrue(yanked.err.contains("vector-math 1.2.0 was yanked"), yanked.err)
        // 65: 署名が無い・不正な index
        signature = null
        val unsigned = install("numbers@3.0.0", project.toString())
        assertEquals(ExitCode.INVALID_INPUT, unsigned.code)
        assertTrue(unsigned.err.contains("CS_REG_SIGNATURE_MISSING"), unsigned.err)
        // 69: オフラインで、アーカイブがキャッシュに無い（保存した index は serial 2）
        val offline = install("numbers@3.0.0", project.toString(), "--offline")
        assertEquals(ExitCode.UNAVAILABLE, offline.code, offline.err)
        assertTrue(offline.err.contains("not in the cache"), offline.err)
        // 何も変えていない
        assertEquals(manifest, Files.readString(project.resolve("createscript.json")))
        assertFalse(Files.exists(project.resolve("createscript.lock")))
    }

    // 19. 本番の設定（鍵が無い）: 名前@版は、通信せずに 69 で拒否する。直接の指定は今までどおり入れて「確かめられない」と示す
    @Test
    fun productionRefusesUntilTheRegistryIsPublished() {
        val noNetwork = object : ReleaseStore {
            override fun download(source: String, version: String): ByteArray = error("the network was used")

            override fun releaseInfo(source: String, version: String): GitHubReleases.ReleaseInfo = error("the network was used")
        }
        val result = capture { o, e -> InstallCommands(o, e) { PackageFetcher(noNetwork, ArchiveCache(directory.resolve("cache"))) to null }.install(listOf("vector-math@1.2.0", project.toString())) }
        assertEquals(ExitCode.UNAVAILABLE, result.code)
        assertEquals(
            "createscript: cannot install vector-math@1.2.0: the official CreateScript-Registry has not been published yet: no registry signing key is built into this createscript. " +
                "Nothing was changed. Packages can still be installed from GitHub with github:<owner>/<repo>@v<version>\n",
            result.err,
        )
        assertFalse(Files.exists(project.resolve(".createscript")))
        // 本番の設定は、公式のリポジトリで、テスト専用の鍵を含まない
        val (client, closeable) = InstallCommands.productionRegistry()
        assertEquals(null, closeable)
        assertEquals(RegistryTrust.OFFICIAL_REPOSITORY, client.trust.repository)
        assertFalse(client.trust.configured)
    }

    @Test
    fun usage() {
        for ((args, message) in listOf(
            listOf("install", "vector-math@^1.2.0") to "invalid package: vector-math@^1.2.0 (expected <name>@<version> with an exact version",
            listOf("install", "vector-math@1.2") to "invalid package",
            listOf("install", "vector-math@v1.2.0") to "invalid package",
            listOf("install", "Vector@1.0.0") to "invalid package",
            listOf("install", "alpha@1.0.0", "beta@1.0.0") to "only one package can be given",
            listOf("install", "alpha@1.0.0", "github:c/d@v1.0.0") to "only one package can be given",
        )) {
            val result = cli(*args.toTypedArray())
            assertEquals(ExitCode.USAGE, result.code, "$args")
            assertTrue(result.err.contains(message), "$args: ${result.err}")
        }
        // @ を含むディレクトリは ./ を付ければディレクトリ
        val odd = Files.createDirectories(directory.resolve("odd@1.0.0"))
        Files.copy(project.resolve("createscript.json"), odd.resolve("createscript.json"))
        assertEquals(ExitCode.OK, install(odd.toString()).code)
        assertTrue(cli("--help").out.contains("createscript install <name>@<version> [directory] [--offline]"))
    }

    // 18. 実行の経路のコードは、レジストリのクラスも使わない
    @Test
    fun runDoesNotUseTheRegistry() {
        for (file in listOf(
            "src/main/kotlin/com/creatrbypc/createanalytical/cli/ProjectProgram.kt",
            "src/main/kotlin/com/creatrbypc/createanalytical/cli/ProjectModules.kt",
            "../createscript-install/src/main/kotlin/com/creatrbypc/createanalytical/install/InstalledPackages.kt",
        )) {
            val code = File(file).readText().lines().filterNot { it.trim().startsWith("//") }
            for (forbidden in listOf("Registry", "IndexStore", "GitHubReleases", "HttpClient")) {
                assertFalse(code.any { it.contains(forbidden) }, "$file uses $forbidden")
            }
        }
    }
}
