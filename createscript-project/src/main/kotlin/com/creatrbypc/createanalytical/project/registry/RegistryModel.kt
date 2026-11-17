package com.creatrbypc.createanalytical.project.registry

import com.creatrbypc.createanalytical.project.InternalCreateScriptProjectApi
import com.creatrbypc.createanalytical.project.LockedPackage
import com.creatrbypc.createanalytical.project.ProjectDiagnostic
import java.time.Instant
import java.time.LocalDate

// CreateScript-Registry の値（S8.3 Phase 1。docs/registry.md）。依存の Map のキーはパッケージ名、値は厳密な版（範囲は S8.4）

// 取り下げ（版の yanked）・削除（パッケージの removed）の理由と日付
@InternalCreateScriptProjectApi
public data class Withdrawal(val reason: String, val date: LocalDate)

// パッケージの持ち主（GitHub のユーザー名と、名前を変えても変わらない数値 ID）
@InternalCreateScriptProjectApi
public data class Owner(val login: String, val id: Long)

// 審査の記録（登録した PR の番号、審査した人、日付）
@InternalCreateScriptProjectApi
public data class Review(val pullRequest: Long, val reviewers: List<String>, val date: LocalDate)

// ---- レジストリのリポジトリのファイル ----

// registry.json: index の有効期間（日）と、index に署名する鍵の ID
@InternalCreateScriptProjectApi
public data class RegistryConfig(val indexValidityDays: Int, val signingKeyIds: List<String>)

// reserved-names.json: 予約名と理由
@InternalCreateScriptProjectApi
public data class ReservedNames(val names: Map<String, String>)

@InternalCreateScriptProjectApi
public data class EntryVersion(val sha256: String, val dependencies: Map<String, String>, val review: Review, val yanked: Withdrawal? = null)

// packages/<頭文字>/<名前>.json
@InternalCreateScriptProjectApi
public data class PackageEntry(
    val name: String,
    val source: String,
    val repositoryId: Long,
    val owners: List<Owner>,
    val description: String,
    val license: String,
    val removed: Withdrawal?,
    val versions: Map<String, EntryVersion>,
) {
    internal fun toIndexPackage() = IndexPackage(
        source, repositoryId, description, license, removed,
        versions.mapValues { (_, version) -> IndexVersion(version.sha256, version.dependencies, version.review.pullRequest, version.yanked) },
    )
}

// ---- 配布する index.json ----

@InternalCreateScriptProjectApi
public data class IndexVersion(val sha256: String, val dependencies: Map<String, String>, val pullRequest: Long, val yanked: Withdrawal? = null)

@InternalCreateScriptProjectApi
public data class IndexPackage(
    val source: String,
    val repositoryId: Long,
    val description: String,
    val license: String,
    val removed: Withdrawal?,
    val versions: Map<String, IndexVersion>,
)

// 取得元と版・SHA-256 を、index に照らした結果（クライアントの表示・判断は Phase 3）
@InternalCreateScriptProjectApi
public enum class RegistryStatus {
    // 審査済み
    REVIEWED,
    // 審査済みだが取り下げられた版
    YANKED,
    // レジストリから削除されたパッケージ（セキュリティ上の理由など）
    REMOVED,
    // パッケージはあるが、この版は審査されていない
    VERSION_NOT_REVIEWED,
    // 取得元がレジストリに無い（未審査）
    NOT_IN_REGISTRY,
    // 取得元はレジストリに無いが、同じ名前を別の取得元のパッケージが使っている（取り違えの恐れ）
    NAME_USED_BY_ANOTHER_SOURCE,
    // 審査済みの版と SHA-256 が違う（必ず拒否する）
    SHA256_MISMATCH,
    // 取得元はレジストリにあるが、別の名前で登録されている（必ず拒否する）
    NAME_MISMATCH,
}

@InternalCreateScriptProjectApi
public data class RegistryIndex(val serial: Long, val generated: Instant, val expires: Instant, val packages: Map<String, IndexPackage>) {
    // name・version・source のパッケージのアーカイブ（SHA-256）の審査の状態
    public fun status(name: String, version: String, source: String, sha256: String): RegistryStatus {
        val registered = packages.entries.firstOrNull { RegistryRules.sameSource(it.value.source, source) }
            ?: return if (name in packages) RegistryStatus.NAME_USED_BY_ANOTHER_SOURCE else RegistryStatus.NOT_IN_REGISTRY
        if (registered.key != name) return RegistryStatus.NAME_MISMATCH
        val entry = registered.value.versions[version] ?: return if (registered.value.removed != null) RegistryStatus.REMOVED else RegistryStatus.VERSION_NOT_REVIEWED
        return when {
            entry.sha256 != sha256 -> RegistryStatus.SHA256_MISMATCH
            registered.value.removed != null -> RegistryStatus.REMOVED
            entry.yanked != null -> RegistryStatus.YANKED
            else -> RegistryStatus.REVIEWED
        }
    }

    public fun status(locked: LockedPackage): RegistryStatus = status(locked.name, locked.version, locked.source, locked.sha256)

    // name@version と、その依存をすべて含む、S8.2 のロックファイルの形のパッケージ（名前の順）。index に無ければ null。
    // index の検査（依存がそろっている・循環が無い・同じ名前の別の版が無い）を通った index なら、Lockfile.of にそのまま渡せる
    public fun lockedClosure(name: String, version: String): List<LockedPackage>? {
        if (packages[name]?.versions?.get(version) == null) return null
        val result = LinkedHashMap<String, LockedPackage>()
        val pending = arrayListOf(name to version)
        while (pending.isNotEmpty()) {
            val (current, currentVersion) = pending.removeAt(pending.lastIndex)
            if (current in result) continue
            val registered = packages[current] ?: return null
            val entry = registered.versions[currentVersion] ?: return null
            result[current] = LockedPackage(current, currentVersion, registered.source, entry.sha256, entry.dependencies.keys.sorted())
            for ((dependency, dependencyVersion) in entry.dependencies) pending.add(dependency to dependencyVersion)
        }
        return result.values.sortedBy { it.name }
    }
}

// 読み取りの結果。value はエラーが無いときだけ
@InternalCreateScriptProjectApi
public class RegistryResult<T>(public val value: T?, public val diagnostics: List<ProjectDiagnostic>, internal val truncated: Boolean = false)
