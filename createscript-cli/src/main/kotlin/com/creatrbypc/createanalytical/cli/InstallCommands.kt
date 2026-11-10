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

        fun exitCode(kind: InstallFailure.Kind): Int = when (kind) {
            InstallFailure.Kind.NETWORK -> ExitCode.UNAVAILABLE
            InstallFailure.Kind.INVALID -> ExitCode.INVALID_INPUT
            InstallFailure.Kind.NO_PROJECT -> ExitCode.NO_INPUT
            InstallFailure.Kind.UNSAFE -> ExitCode.CANNOT_CREATE
            InstallFailure.Kind.BUSY -> ExitCode.TEMPORARY_FAILURE
        }

        private val IGNORE_LINES = setOf(".createscript", ".createscript/", "/.createscript", "/.createscript/")
    }

    // createscript install [github:持ち主/リポジトリ@v版] [ディレクトリ] [--offline]
    fun install(args: List<String>): Int {
        var spec: PackageSpec? = null
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
                    if (spec != null) throw Usage("only one package can be given: $argument")
                    spec = PackageSpec.parse(argument)
                        ?: throw Usage("invalid package: $argument (expected github:owner/repository@v1.2.0 with an exact version)")
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
        try {
            val result = Installer(root, fetcher) { err.println("createscript: $it") }.install(spec, offline)
            result.recovery?.let { out.println("Recovered: $it") }
            for (p in result.packages) {
                out.println((if (p.installed) "Installed" else "Already installed") + " ${p.name} ${p.version} from ${p.source}" + if (p.installed) " -> ${p.directory}" else "")
                out.println("  sha256 ${p.sha256}")
                out.println("  " + trust(p.fetch))
                if (p.fetch.immutable == false) err.println("warning: ${p.name} ${p.version}: the release is not immutable; its owner can still replace its assets")
            }
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
