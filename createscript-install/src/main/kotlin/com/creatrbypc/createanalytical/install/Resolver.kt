package com.creatrbypc.createanalytical.install

import com.creatrbypc.createanalytical.project.Dependency
import com.creatrbypc.createanalytical.project.Lockfile
import com.creatrbypc.createanalytical.project.Manifests
import com.creatrbypc.createanalytical.project.PackageArchives
import com.creatrbypc.createanalytical.project.PackageKind

// 1 回のインストールの上限（S8.2 の設計の 5）。テストは小さな値を渡す
data class InstallLimits(
    val maxPackages: Int = 256,
    val maxDepth: Int = 32,
    // 展開後の合計（全パッケージのファイルの大きさの和）
    val maxTotalBytes: Long = 128L * 1024 * 1024,
)

// 取得の結果の要約（アーカイブのバイト列は持たない）
class FetchSummary(val origin: PackageFetcher.Origin, val locked: Boolean, val digest: PackageFetcher.DigestCheck, val immutable: Boolean?, val warnings: List<String>)

// 解決したパッケージ。dependencies は直接の依存の name（名前の順）、files は検査に通ったアーカイブの中身、requiredBy は最初に要求したもの
internal class ResolvedPackage(
    val name: String,
    val version: String,
    val source: String,
    val sha256: String,
    val dependencies: List<String>,
    val files: Map<String, ByteArray>,
    val requiredBy: String,
    val fetch: FetchSummary,
)

// 依存の解決（S8.2 Phase 3）。厳密な版だけで、範囲・衝突の解決はしない（S8.4）。
// - 直接の依存から、各パッケージのマニフェストの依存を、名前の順の深さ優先でたどる（結果の順序・取得の順序は入力だけで決まる）
// - 同じ name への要求は、version と source が完全に同じでなければ衝突（INVALID）。1 つの source を 2 つの name で使うのも衝突
// - 循環・プロジェクト自身への依存・深さ・パッケージの数・展開後の合計の上限を超えるものはエラー
// - ロックファイルに同じ name・version・source があれば、その SHA-256 を必ず使う（違うアーカイブは PackageFetcher が拒否する）。
//   その場合、アーカイブのマニフェストの依存とロックファイルの dependencies が違えば、ロックファイルが書き換えられたとみなしてエラー
// - アーカイブは PackageArchives.read で検査する（library・name・version の一致・パス・ソース）。コードは実行しない
internal class Resolver(private val fetcher: PackageFetcher, private val limits: InstallLimits, private val offline: Boolean) {
    private class Fetched(val sha256: String, val archive: PackageArchives.Archive, val summary: FetchSummary)

    private val fetched = HashMap<String, Fetched>()
    private var totalBytes = 0L

    private fun invalid(message: String): Nothing = throw InstallFailure(InstallFailure.Kind.INVALID, message)

    // source の version のアーカイブを得て検査する。expectedName が null なら、名前はアーカイブのマニフェストのものを使う（install github:…@v… のとき）
    fun fetch(source: String, version: String, expectedSha256: String?, expectedName: String?): Pair<String, PackageArchives.Archive> {
        val key = "$source@$version"
        fetched[key]?.let { cached ->
            if (expectedSha256 != null && cached.sha256 != expectedSha256) throw FetchFailure.HashMismatch(expectedSha256, cached.sha256).asInstallFailure(source, version)
            if (expectedName != null && cached.archive.manifest.name != expectedName) invalid("$source $version is the package ${cached.archive.manifest.name}, not $expectedName")
            return cached.sha256 to cached.archive
        }
        val result = try {
            fetcher.fetch(source, version, expectedSha256, offline)
        } catch (failure: FetchFailure) {
            throw failure.asInstallFailure(source, version)
        }
        val checked = PackageArchives.read(result.bytes, expectedName, version)
        val archive = checked.archive ?: invalid(
            "$source $version is not a valid package:\n" + checked.diagnostics.joinToString("\n") { "  " + it.diagnostic.format(it.path ?: PackageArchives.FILE_NAME) },
        )
        if (archive.manifest.kind != PackageKind.LIBRARY) invalid("$source $version is not a library")
        fetched[key] = Fetched(result.sha256, archive, FetchSummary(result.origin, result.locked, result.digest, result.immutable, result.warnings))
        return result.sha256 to archive
    }

    // rootName はプロジェクトの name。dependencies はプロジェクトの直接の依存。結果は名前の順
    fun resolve(rootName: String, dependencies: List<Dependency>, lock: Lockfile?): List<ResolvedPackage> {
        val resolved = HashMap<String, ResolvedPackage>()
        val requirement = HashMap<String, Dependency>()
        val bySource = HashMap<String, String>()
        val path = ArrayList<String>()
        val locked = lock?.packages?.associateBy { it.name }.orEmpty()

        fun visit(dependency: Dependency, requiredBy: String, depth: Int) {
            val previous = requirement[dependency.name]
            if (previous != null) {
                if (dependency.name in path) invalid("dependency cycle: ${(path.dropWhile { it != dependency.name } + dependency.name).joinToString(" -> ")}")
                if (previous.version != dependency.version || previous.source != dependency.source) {
                    val first = resolved[dependency.name]?.requiredBy ?: "?"
                    invalid(
                        "conflicting requirements for ${dependency.name}: ${previous.version} from ${previous.source} (required by $first) " +
                            "and ${dependency.version} from ${dependency.source} (required by $requiredBy); only one version of a package can be installed",
                    )
                }
                return
            }
            if (dependency.name == rootName) invalid("$requiredBy depends on ${dependency.name}, which is this project")
            bySource[dependency.source]?.let { other ->
                invalid("${dependency.source} is required as two packages: $other and ${dependency.name} (required by $requiredBy)")
            }
            if (depth > limits.maxDepth) invalid("dependencies are nested deeper than ${limits.maxDepth} levels: ${(path + dependency.name).joinToString(" -> ")}")
            if (requirement.size + 1 > limits.maxPackages) invalid("more than ${limits.maxPackages} packages are needed")
            requirement[dependency.name] = dependency
            bySource[dependency.source] = dependency.name
            val entry = locked[dependency.name]?.takeIf { it.version == dependency.version && it.source == dependency.source }
            val (sha256, archive) = fetch(dependency.source, dependency.version, entry?.sha256, dependency.name)
            val names = archive.manifest.dependencies.map { it.name }
            if (entry != null && entry.dependencies != names) {
                invalid("${Manifests.FILE_NAME} of ${dependency.name} ${dependency.version} lists the dependencies $names, but createscript.lock lists ${entry.dependencies}; createscript.lock was changed by hand")
            }
            totalBytes += archive.files.values.sumOf { it.size.toLong() }
            if (totalBytes > limits.maxTotalBytes) invalid("the packages are larger than ${limits.maxTotalBytes} bytes in total")
            path.add(dependency.name)
            for (child in archive.manifest.dependencies) visit(child, dependency.name, depth + 1)
            path.removeAt(path.lastIndex)
            resolved[dependency.name] = ResolvedPackage(
                dependency.name, dependency.version, dependency.source, sha256, names, archive.files, requiredBy,
                fetched.getValue("${dependency.source}@${dependency.version}").summary,
            )
        }

        for (dependency in dependencies.sortedBy { it.name }) visit(dependency, Manifests.FILE_NAME, 1)
        return resolved.values.sortedBy { it.name }
    }

    private fun FetchFailure.asInstallFailure(source: String, version: String): InstallFailure = when (this) {
        is FetchFailure.HashMismatch -> InstallFailure(
            InstallFailure.Kind.INVALID,
            "$source $version does not match createscript.lock: expected SHA-256 $expected, got $actual. The release asset was replaced or damaged; it was not installed",
        )
        else -> InstallFailure(if (network) InstallFailure.Kind.NETWORK else InstallFailure.Kind.INVALID, "$source $version: $message")
    }
}
