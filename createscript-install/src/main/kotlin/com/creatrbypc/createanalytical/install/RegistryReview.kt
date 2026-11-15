package com.creatrbypc.createanalytical.install

import com.creatrbypc.createanalytical.project.LockedPackage
import com.creatrbypc.createanalytical.project.Lockfile
import com.creatrbypc.createanalytical.project.ProjectRules
import com.creatrbypc.createanalytical.project.registry.RegistryRules
import com.creatrbypc.createanalytical.project.registry.RegistryStatus
import com.creatrbypc.createanalytical.project.registry.Withdrawal

// install <名前>@<版>（S8.3 Phase 3）。版は厳密な版だけ（範囲は S8.4）。v は付けない
class RegistryRequest(val name: String, val version: String) {
    override fun toString() = "$name@$version"

    companion object {
        fun parse(text: String): RegistryRequest? {
            val at = text.indexOf('@')
            if (at <= 0 || at != text.lastIndexOf('@')) return null
            val name = text.substring(0, at)
            val version = text.substring(at + 1)
            if (!ProjectRules.isValidName(name) || !ProjectRules.isValidVersion(version)) return null
            return RegistryRequest(name, version)
        }
    }
}

// index から決めたインストールの内容: 取得元と版、審査済みの依存をすべて含むパッケージ（S8.2 のロックファイルの形）
internal class RegistryPlan(val request: RegistryRequest, val spec: PackageSpec, val closure: List<LockedPackage>, val serial: Long) {
    // 取得元（大文字小文字を区別しない）@版 → 審査済みの SHA-256
    val pins: Map<String, String> = closure.associate { key(it.source, it.version) to it.sha256 }

    companion object {
        fun key(source: String, version: String) = "${source.lowercase()}@$version"
    }
}

// パッケージの審査の状態（表示用）
class PackageReview(val name: String, val status: RegistryStatus?, val text: String)

// レジストリによる確認（S8.3 Phase 3。docs/registry.md の「The client」）
internal class RegistryReview(private val lookup: RegistryLookup?, private val plan: RegistryPlan?) {
    private fun invalid(message: String): Nothing = throw InstallFailure(InstallFailure.Kind.INVALID, message)

    companion object {
        private fun withdrawn(w: Withdrawal) = "${w.date}: ${w.reason}"

        // name@version を index から決める。インストールを始める前に呼ぶ（失敗すれば、何も変えない）
        fun plan(available: RegistryLookup.Available, request: RegistryRequest): RegistryPlan {
            fun invalid(message: String): Nothing = throw InstallFailure(InstallFailure.Kind.INVALID, message)
            val index = available.accepted.index
            val where = "the CreateScript-Registry (index serial ${index.serial})"
            val registered = index.packages[request.name] ?: run {
                val similar = RegistryRules.similarNames(request.name, index.packages.keys).sorted().take(3)
                invalid("${request.name} is not in $where" + (if (similar.isEmpty()) "" else "; similar names: ${similar.joinToString(", ")}") + ". To install a package that is not reviewed, give its source: github:<owner>/<repo>@v<version>")
            }
            registered.removed?.let { invalid("${request.name} was removed from $where (${withdrawn(it)}); it cannot be installed") }
            val version = registered.versions[request.version] ?: run {
                val reviewed = registered.versions.filterValues { it.yanked == null }.keys.toList().takeLast(5)
                invalid("${request.name} has no reviewed version ${request.version} in $where" + if (reviewed.isEmpty()) "" else "; reviewed versions include ${reviewed.joinToString(", ")}")
            }
            version.yanked?.let { invalid("${request.name} ${request.version} was yanked in $where (${withdrawn(it)}); it is not installed") }
            val closure = index.lockedClosure(request.name, request.version) ?: invalid("the dependencies of ${request.name} ${request.version} are not complete in $where")
            for (p in closure) {
                when (index.status(p)) {
                    RegistryStatus.REVIEWED -> {}
                    RegistryStatus.YANKED -> invalid("${request.name} ${request.version} depends on ${p.name} ${p.version}, which was yanked in $where; it is not installed")
                    RegistryStatus.REMOVED -> invalid("${request.name} ${request.version} depends on ${p.name}, which was removed from $where; it is not installed")
                    else -> invalid("${request.name} ${request.version} depends on ${p.name} ${p.version}, which is not reviewed in $where; it is not installed")
                }
            }
            val spec = PackageSpec(registered.source, request.version)
            return RegistryPlan(request, spec, closure, index.serial)
        }
    }

    // 解決したパッケージを確かめる（プロジェクトのファイルを変える前）。拒否するものがあれば INVALID。結果は表示と警告
    fun check(packages: List<ResolvedPackage>, lock: Lockfile?, direct: PackageSpec?): Pair<List<PackageReview>, List<String>> {
        val warnings = ArrayList<String>()
        val available = lookup as? RegistryLookup.Available
        if (available == null) {
            val reason = when (lookup) {
                is RegistryLookup.Unavailable -> lookup.message
                is RegistryLookup.Refused -> lookup.message
                else -> null
            }
            // 直接指定したパッケージは必ず知らせる。復元は、レジストリが設定されているときだけ（設定されていなければ S8.2 と同じ表示）
            val configured = (lookup as? RegistryLookup.Unavailable)?.reason != RegistryLookup.Reason.NOT_CONFIGURED
            if (reason != null && direct != null) {
                warnings += "the review status of $direct could not be checked: $reason"
            } else if (reason != null && configured && packages.isNotEmpty()) {
                warnings += "the review status of the packages could not be checked: $reason"
            }
            return emptyList<PackageReview>() to warnings
        }
        val index = available.accepted.index
        val where = "the CreateScript-Registry (index serial ${index.serial})"
        val reviews = ArrayList<PackageReview>()
        for (p in packages) {
            val locked = lock?.packages?.any { it.name == p.name && it.version == p.version && it.source == p.source && it.sha256 == p.sha256 } == true
            val registered = index.packages.entries.firstOrNull { RegistryRules.sameSource(it.value.source, p.source) }
            val version = registered?.value?.versions?.get(p.version)
            val label = "${p.name} ${p.version} from ${p.source}"
            val text = when (val status = index.status(p.name, p.version, p.source, p.sha256)) {
                RegistryStatus.SHA256_MISMATCH -> invalid(
                    "$label has the SHA-256 ${p.sha256}, but $where reviewed ${version?.sha256} for it; it was not installed. The release asset may have been replaced",
                )
                RegistryStatus.NAME_MISMATCH -> invalid("$label is named ${p.name}, but $where registers ${p.source} as ${registered?.key}; it was not installed")
                RegistryStatus.REMOVED -> invalid(
                    "$label was removed from $where (${registered?.value?.removed?.let(::withdrawn)}); it is neither installed nor restored. Remove it from createscript.json",
                )
                RegistryStatus.YANKED -> {
                    val reason = version?.yanked?.let(::withdrawn)
                    if (!locked) invalid("$label was yanked in $where ($reason); it is not installed")
                    warnings += "$label was yanked in $where ($reason); it was restored from createscript.lock, but consider replacing it"
                    "yanked ($reason)"
                }
                RegistryStatus.REVIEWED -> "reviewed (pull request #${version?.pullRequest}; index serial ${index.serial})"
                RegistryStatus.NAME_USED_BY_ANOTHER_SOURCE -> {
                    if (!locked) warnings += "$label is not reviewed, and the registry has another package named ${p.name} (from ${index.packages[p.name]?.source}); check that this is the package you want"
                    "not reviewed; the registry has another package with this name"
                }
                RegistryStatus.VERSION_NOT_REVIEWED, RegistryStatus.NOT_IN_REGISTRY -> {
                    if (!locked) warnings += "$label is not reviewed by $where" + if (status == RegistryStatus.VERSION_NOT_REVIEWED) " (other versions are)" else ""
                    if (status == RegistryStatus.VERSION_NOT_REVIEWED) "not reviewed (other versions are)" else "not reviewed (not in the registry)"
                }
            }
            reviews += PackageReview(p.name, index.status(p.name, p.version, p.source, p.sha256), text)
        }
        // name@version: 解決したものが、index の審査済みのパッケージと完全に同じであること（依存も審査済みであること）
        plan?.let { plan ->
            val byName = packages.associateBy { it.name }
            for (expected in plan.closure) {
                val p = byName[expected.name] ?: invalid("${plan.request} needs ${expected.name} ${expected.version}, but it was not resolved")
                if (p.version != expected.version || !RegistryRules.sameSource(p.source, expected.source) || p.sha256 != expected.sha256 || p.dependencies != expected.dependencies) {
                    invalid("${p.name} ${p.version} from ${p.source} does not match ${expected.name} ${expected.version} reviewed in $where; it was not installed")
                }
            }
        }
        return reviews to warnings
    }
}
