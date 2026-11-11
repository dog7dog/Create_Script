package com.creatrbypc.createanalytical.cli

import com.creatrbypc.createanalytical.install.ArchiveCache
import com.creatrbypc.createanalytical.install.FetchSummary
import com.creatrbypc.createanalytical.install.GitHubReleases
import com.creatrbypc.createanalytical.install.GitHubToken
import com.creatrbypc.createanalytical.install.InstallFailure
import com.creatrbypc.createanalytical.install.InstallLayout
import com.creatrbypc.createanalytical.install.Installer
import com.creatrbypc.createanalytical.install.PackageFetcher
import com.creatrbypc.createanalytical.install.PackageSpec
import com.creatrbypc.createanalytical.install.RegistryCache
import com.creatrbypc.createanalytical.install.RegistryClient
import com.creatrbypc.createanalytical.install.RegistryLookup
import com.creatrbypc.createanalytical.install.RegistryRequest
import com.creatrbypc.createanalytical.install.RegistryTrust
import com.creatrbypc.createanalytical.install.GitHubIndexStore
import com.creatrbypc.createanalytical.project.Lockfiles
import com.creatrbypc.createanalytical.project.Manifests
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths

// createscript install（S8.2 Phase 3）。インストールの手順は createscript-install の Installer が行い、ここでは引数・表示・終了コードを扱う。
// fetcher は取得の手順を作る（本番は GitHub Releases とキャッシュ。テストはメモリーの中の Release を渡す）。戻り値の 2 つ目は、終わったら閉じるもの
internal class InstallCommands(
    private val out: PrintStream,
    private val err: PrintStream,
    // レジストリの index を得る手順（S8.3 Phase 3。本番は公式の CreateScript-Registry と組み込みの鍵。テストはテスト専用の鍵とメモリーの中の index を渡す）
    private val registry: () -> Pair<RegistryClient, AutoCloseable?> = ::productionRegistry,
    private val fetcher: () -> Pair<PackageFetcher, AutoCloseable?> = ::production,
) {
    class Usage(message: String) : Exception(message)

    companion object {
        fun production(): Pair<PackageFetcher, AutoCloseable?> {
            val token = try {
                GitHubToken.fromEnvironment()
            } catch (error: IllegalArgumentException) {
                // 値は表示しない
                throw Usage("${GitHubToken.ENVIRONMENT_VARIABLE} is set but is not a valid token")
            }
            val releases = GitHubReleases(token, "createscript/${BuildInfo.VERSION}")
            return PackageFetcher(releases, ArchiveCache(ArchiveCache.defaultRoot())) to releases
        }

        // 公式のレジストリ（接続先・鍵は組み込みのもので、オプション・環境変数では変えられない）。鍵が無い間は、通信の手段も作らない
        fun productionRegistry(): Pair<RegistryClient, AutoCloseable?> {
            val trust = RegistryTrust.official()
            val cache = RegistryCache(ArchiveCache.defaultRoot(), trust.repository)
            if (!trust.configured) return RegistryClient(trust, null, cache) to null
            val token = try {
                GitHubToken.fromEnvironment()
            } catch (error: IllegalArgumentException) {
                throw Usage("${GitHubToken.ENVIRONMENT_VARIABLE} is set but is not a valid token")
            }
            val releases = GitHubReleases(token, "createscript/${BuildInfo.VERSION}")
            return RegistryClient(trust, GitHubIndexStore(releases, trust.repository), cache) to releases
        }

        fun exitCode(kind: InstallFailure.Kind): Int = when (kind) {
            InstallFailure.Kind.NETWORK -> ExitCode.UNAVAILABLE
            InstallFailure.Kind.INVALID -> ExitCode.INVALID_INPUT
            InstallFailure.Kind.NO_PROJECT -> ExitCode.NO_INPUT
            InstallFailure.Kind.UNSAFE -> ExitCode.CANNOT_CREATE
            InstallFailure.Kind.BUSY -> ExitCode.TEMPORARY_FAILURE
        }

        private val IGNORE_LINES = setOf(".createscript", ".createscript/", "/.createscript", "/.createscript/")
    }

    // createscript install [github:持ち主/リポジトリ@v版 | 名前@版] [ディレクトリ] [--offline]
    fun install(args: List<String>): Int {
        var spec: PackageSpec? = null
        var request: RegistryRequest? = null
        var directory: String? = null
        var offline = false
        for (argument in args) {
            when {
                argument == "--offline" -> {
                    if (offline) throw Usage("duplicate option: --offline")
                    offline = true
                }
                argument.startsWith("-") -> throw Usage("unknown option: $argument")
                argument.startsWith("github:") -> {
                    if (spec != null || request != null) throw Usage("only one package can be given: $argument")
                    spec = PackageSpec.parse(argument)
                        ?: throw Usage("invalid package: $argument (expected github:owner/repository@v1.2.0 with an exact version)")
                }
                // 名前@版（レジストリ）。/ と \ を含むものはディレクトリ（@ を含むディレクトリは ./ を付けて渡す）
                '@' in argument && '/' !in argument && '\\' !in argument -> {
                    if (spec != null || request != null) throw Usage("only one package can be given: $argument")
                    request = RegistryRequest.parse(argument)
                        ?: throw Usage("invalid package: $argument (expected <name>@<version> with an exact version such as vector-math@1.2.0; version ranges are not supported)")
                }
                directory != null -> throw Usage("unexpected argument: $argument")
                else -> directory = argument
            }
        }
        val root = try {
            Paths.get(directory ?: ".")
        } catch (error: InvalidPathException) {
            throw Usage("invalid path: $directory")
        }
        val (fetcher, closeable) = fetcher()
        var registryCloseable: AutoCloseable? = null
        try {
            val (client, closeRegistry) = registry()
            registryCloseable = closeRegistry
            val installer = Installer(root, fetcher) { err.println("createscript: $it") }
            val result = if (request != null) {
                // 名前@版: index を確かめられなければ、インストールを始めない
                installer.install(request, offline, client.lookup(offline))
            } else {
                // 直接の指定・復元: index は審査の状態を示すためだけに使う（得られなくても、インストールは S8.2 と同じに行う）
                val lookup = try {
                    client.lookup(offline)
                } catch (failure: InstallFailure) {
                    RegistryLookup.Unavailable(RegistryLookup.Reason.CACHE_UNUSABLE, failure.message ?: "the registry cache cannot be used")
                }
                installer.install(spec, offline, lookup)
            }
            result.recovery?.let { out.println("Recovered: $it") }
            for (p in result.packages) {
                out.println((if (p.installed) "Installed" else "Already installed") + " ${p.name} ${p.version} from ${p.source}" + if (p.installed) " -> ${p.directory}" else "")
                out.println("  sha256 ${p.sha256}")
                out.println("  " + trust(p.fetch))
                result.reviews.firstOrNull { it.name == p.name }?.let { out.println("  registry: ${it.text}") }
                if (p.fetch.immutable == false) err.println("warning: ${p.name} ${p.version}: the release is not immutable; its owner can still replace its assets")
            }
            result.notes.forEach { err.println("note: $it") }
            result.warnings.forEach { err.println("warning: $it") }
            result.added?.let { out.println("Updated ${Manifests.FILE_NAME} (dependency ${it.name} ${it.version} from ${it.source})") }
            if (result.lockChanged) out.println("Wrote ${Lockfiles.FILE_NAME} (${result.packages.size} packages)")
            if (!result.manifestChanged && !result.lockChanged && result.packages.none { it.installed }) {
                out.println(if (result.packages.isEmpty()) "Nothing to install (no dependencies)" else "Up to date (${result.packages.size} packages)")
            }
            if (result.packages.isNotEmpty() && !ignored(root)) {
                out.println("Note: add /${InstallLayout.DIRECTORY}/ to .gitignore; it holds installed packages and should not be committed")
            }
            return ExitCode.OK
        } catch (failure: InstallFailure) {
            err.println("createscript: ${failure.message}")
            return exitCode(failure.kind)
        } finally {
            registryCloseable?.close()
            closeable?.close()
        }
    }

    // 取得の根拠（docs/packages.md の「Trust」）
    private fun trust(fetch: FetchSummary): String {
        val parts = ArrayList<String>()
        parts += when {
            fetch.origin == PackageFetcher.Origin.CACHE -> "from the cache; matches ${Lockfiles.FILE_NAME}"
            fetch.locked -> "downloaded; matches ${Lockfiles.FILE_NAME}"
            else -> "downloaded; recorded on first use (trust on first use)"
        }
        when (val digest = fetch.digest) {
            PackageFetcher.DigestCheck.Matched -> parts += "GitHub digest matched"
            is PackageFetcher.DigestCheck.Unverified -> parts += "GitHub digest not checked (${digest.reason})"
            PackageFetcher.DigestCheck.NotNeeded -> {}
        }
        when (fetch.immutable) {
            true -> parts += "the release is immutable"
            false -> parts += "the release is not immutable"
            null -> if (fetch.origin == PackageFetcher.Origin.DOWNLOAD) parts += "immutability unknown"
        }
        return parts.joinToString("; ")
    }

    // .gitignore に .createscript の行があるか（読めなければ案内しない）
    private fun ignored(root: Path): Boolean {
        val path = root.resolve(".gitignore")
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false
        return try {
            SafeFiles.readLimited(path, 1024 * 1024).toString(Charsets.UTF_8).lines().any { it.trim() in IGNORE_LINES }
        } catch (error: IOException) {
            true
        }
    }
}
