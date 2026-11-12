package com.creatrbypc.createanalytical.cli

import com.creatrbypc.createanalytical.install.ArchiveCache
import com.creatrbypc.createanalytical.install.GitHubIndexStore
import com.creatrbypc.createanalytical.install.GitHubReleases
import com.creatrbypc.createanalytical.install.GitHubToken
import com.creatrbypc.createanalytical.install.IndexStore
import com.creatrbypc.createanalytical.install.PackageFetcher
import com.creatrbypc.createanalytical.install.RegistryCache
import com.creatrbypc.createanalytical.install.RegistryClient
import com.creatrbypc.createanalytical.install.RegistryTrust
import com.creatrbypc.createanalytical.install.ReleaseStore
import com.creatrbypc.createanalytical.project.registry.TrustedKey
import java.io.PrintStream
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Base64
import kotlin.system.exitProcess

// テスト専用のクライアント（S8.3 Phase 10A、W7）。GitHub の実環境のテスト（docs/registry-github-integration-test.md）で、テスト用の
// レジストリのリポジトリとテスト用の Ed25519 の公開鍵を使って `install` を行う。
// - テストのソース（src/test）にだけあり、本番の配布物（installDist・パッケージ）には入らない。作るのは
//   `./gradlew :createscript-cli:testRegistryCli` を明示したときだけ（build/test-registry-cli/、起動スクリプトの名前は createscript-TEST-REGISTRY）
// - 本番の RegistryTrust.official() と、本番の CLI の引数・環境変数は変えない。信頼する鍵・リポジトリは、この入口の引数でだけ渡す
// - 鍵の ID は test-only- で始めること、リポジトリは公式のものでないこと、キャッシュは本番の既定の置き場所でない専用のディレクトリであることを求める
// - 実行のたびに、テスト用のビルドであることを標準エラーに出す
object TestRegistryCli {
    const val BANNER = "createscript TEST-REGISTRY BUILD (not for production)"

    class Usage(message: String) : Exception(message)

    class Config(val repository: String, val key: TrustedKey, val cacheDir: Path, val command: List<String>)

    fun parse(args: List<String>, defaultCacheRoot: Path = ArchiveCache.defaultRoot()): Config {
        val options = HashMap<String, String>()
        var i = 0
        while (i < args.size && args[i].startsWith("--test-")) {
            if (i + 1 >= args.size) throw Usage("${args[i]} needs a value")
            if (options.put(args[i], args[i + 1]) != null) throw Usage("${args[i]} is given twice")
            i += 2
        }
        fun required(name: String) = options[name] ?: throw Usage("$name is required")
        val unknown = options.keys - setOf("--test-registry", "--test-key-id", "--test-public-key", "--test-cache-dir")
        if (unknown.isNotEmpty()) throw Usage("unknown option: ${unknown.first()}")
        val repository = required("--test-registry")
        if (repository.equals(RegistryTrust.OFFICIAL_REPOSITORY, ignoreCase = true)) throw Usage("the test build cannot use the official registry")
        val keyId = required("--test-key-id")
        if (!keyId.startsWith(RegistryTrust.TEST_KEY_PREFIX)) throw Usage("the test key ID must start with ${RegistryTrust.TEST_KEY_PREFIX}")
        val raw = try {
            Base64.getDecoder().decode(required("--test-public-key"))
        } catch (error: IllegalArgumentException) {
            throw Usage("--test-public-key must be 32 bytes in standard Base64")
        }
        val key = try {
            TrustedKey(keyId, raw)
        } catch (error: IllegalArgumentException) {
            throw Usage("invalid test key: ${error.message}")
        }
        val cacheDir = Paths.get(required("--test-cache-dir"))
        if (!cacheDir.isAbsolute) throw Usage("--test-cache-dir must be an absolute path")
        if (cacheDir.normalize() == defaultCacheRoot.toAbsolutePath().normalize()) throw Usage("--test-cache-dir must not be the default cache of createscript")
        val command = args.drop(i)
        if (command.firstOrNull() != "install") throw Usage("the test build only runs install (createscript-TEST-REGISTRY --test-… install …)")
        // RegistryTrust は、公式のリポジトリの名前でテスト用の鍵を拒否する（ここでも、公式のリポジトリは上で拒否している）
        RegistryTrust(repository, listOf(key))
        return Config(repository, key, cacheDir, command)
    }

    // stores は、テストでメモリーの中の index・Release を渡す（null なら GitHub）
    fun run(args: List<String>, out: PrintStream, err: PrintStream, stores: Pair<IndexStore, ReleaseStore>? = null, defaultCacheRoot: Path = ArchiveCache.defaultRoot()): Int {
        err.println(BANNER)
        val config = try {
            parse(args, defaultCacheRoot)
        } catch (error: Usage) {
            err.println("createscript-TEST-REGISTRY: ${error.message}")
            return ExitCode.USAGE
        }
        err.println("  test registry ${config.repository}, test key ${config.key.keyId}, cache ${config.cacheDir}")
        val trust = RegistryTrust(config.repository, listOf(config.key))
        val releases = if (stores == null) GitHubReleases(GitHubToken.fromEnvironment(), "createscript-test-registry/${BuildInfo.VERSION}") else null
        val indexStore = stores?.first ?: GitHubIndexStore(releases!!, config.repository)
        val releaseStore = stores?.second ?: releases!!
        return try {
            InstallCommands(
                out, err,
                registry = { RegistryClient(trust, indexStore, RegistryCache(config.cacheDir, config.repository)) to null },
                fetcher = { PackageFetcher(releaseStore, ArchiveCache(config.cacheDir)) to null },
            ).install(config.command.drop(1))
        } catch (error: InstallCommands.Usage) {
            err.println("createscript-TEST-REGISTRY: ${error.message}")
            ExitCode.USAGE
        } finally {
            releases?.close()
        }
    }
}

fun main(args: Array<String>) {
    exitProcess(TestRegistryCli.run(args.toList(), System.out, System.err))
}
