package com.creatrbypc.createanalytical.project.registry

import com.creatrbypc.createanalytical.project.InternalCreateScriptProjectApi
import com.creatrbypc.createanalytical.project.ProjectDiagnostic
import com.creatrbypc.createanalytical.project.ProjectDiagnosticCode
import com.creatrbypc.createanalytical.project.ProjectDiagnostics
import java.time.Duration
import java.time.Instant

// 受け入れた index（署名と検査を通ったもの）。bytes は index.json のバイト列そのもの。クライアントは、次の index と比べるためにこれを保存する（Phase 3）
@InternalCreateScriptProjectApi
public class AcceptedIndex internal constructor(bytes: ByteArray, public val index: RegistryIndex, public val sha256: String, public val keyIds: List<String>) {
    private val content = bytes.copyOf()

    public val bytes: ByteArray get() = content.copyOf()
}

// verify の結果。accepted は受け入れたときだけ。unchanged は以前と同じ index（同じ通し番号・同じバイト列）
@InternalCreateScriptProjectApi
public class IndexVerification(public val accepted: AcceptedIndex?, public val diagnostics: List<ProjectDiagnostic>, public val unchanged: Boolean = false)

// index.json と index.json.sig を確かめる（S8.3 Phase 1。docs/registry.md の「Accepting an index」）。順に:
// 1. 大きさ（RegistryLimits.MAX_INDEX_BYTES）
// 2. 署名（index の JSON を読む前に。信頼する鍵で、index.json のバイト列そのものに対して）
// 3. index の読み取りと検査（正規形・値・パッケージの間の規則）
// 4. 日時: 期限切れ（expires <= now）・生成日時が未来（generated > now + clockSkew）は拒否。allowExpired は、オフラインでキャッシュの index を表示に使うときだけ
// 5. 以前に受け入れた index（previous）との比較（IndexHistory.compare）
@InternalCreateScriptProjectApi
public class IndexVerifier(private val keys: RegistryKeys, private val clockSkew: Duration = Duration.ofDays(1)) {
    public fun verify(indexBytes: ByteArray, signatureBytes: ByteArray?, previous: AcceptedIndex?, now: Instant, allowExpired: Boolean = false): IndexVerification {
        if (indexBytes.size > RegistryLimits.MAX_INDEX_BYTES) return failed(ProjectDiagnosticCode.FILE_TOO_LARGE, RegistryLimits.MAX_INDEX_BYTES.toString())
        val signed = IndexSignatures.verify(indexBytes, signatureBytes, keys)
        val keyIds = signed.value ?: return IndexVerification(null, signed.diagnostics)
        val parsed = RegistryIndexes.parse(indexBytes)
        val index = parsed.value ?: return IndexVerification(null, parsed.diagnostics)
        if (index.generated.isAfter(now.plus(clockSkew))) return failed(ProjectDiagnosticCode.INDEX_NOT_YET_VALID, RegistryRules.formatTimestamp(index.generated))
        if (!allowExpired && !now.isBefore(index.expires)) return failed(ProjectDiagnosticCode.INDEX_EXPIRED, RegistryRules.formatTimestamp(index.expires))
        val sha256 = RegistryIndexes.sha256(indexBytes)
        if (previous != null) {
            val problems = IndexHistory.compare(previous.index, previous.sha256, index, sha256)
            if (problems.isNotEmpty()) return IndexVerification(null, problems)
        }
        return IndexVerification(AcceptedIndex(indexBytes, index, sha256, keyIds), emptyList(), unchanged = previous?.sha256 == sha256)
    }

    private fun failed(code: ProjectDiagnosticCode, vararg arguments: String) = IndexVerification(null, listOf(ProjectDiagnostic(code, 1, 1, arguments.toList())))
}

// 以前の index・リポジトリのファイルとの比較（S8.3 Phase 1）。1 つの index だけでは、審査済みの SHA-256 が変わっていないことは分からないため、
// 以前に受け入れたものと比べる:
// - 通し番号は減らない。同じ通し番号なら、同じバイト列（SHA-256）でなければならない
// - 生成日時は戻らない
// - パッケージ・版は消えない（取り下げ・削除は yanked・removed で表す。消してから別の中身で登録し直すことを防ぐ）
// - 版の SHA-256・依存は変わらない。パッケージのリポジトリの ID は変わらない（取得元の名前の変更は、同じ ID なら許す）
@InternalCreateScriptProjectApi
public object IndexHistory {
    public fun compare(previous: RegistryIndex, previousSha256: String, current: RegistryIndex, currentSha256: String): List<ProjectDiagnostic> {
        val diagnostics = ProjectDiagnostics()
        when {
            current.serial < previous.serial -> diagnostics.add(problem(ProjectDiagnosticCode.INDEX_ROLLBACK, previous.serial.toString(), current.serial.toString()))
            current.serial == previous.serial && currentSha256 != previousSha256 -> diagnostics.add(problem(ProjectDiagnosticCode.INDEX_SERIAL_REUSED, current.serial.toString()))
        }
        if (!diagnostics.isEmpty) return diagnostics.list
        if (current.generated.isBefore(previous.generated)) {
            diagnostics.add(problem(ProjectDiagnosticCode.INDEX_TIME_ROLLBACK, RegistryRules.formatTimestamp(previous.generated), RegistryRules.formatTimestamp(current.generated)))
        }
        comparePackages(previous.packages, current.packages, diagnostics)
        return diagnostics.list
    }

    // リポジトリのファイル同士（PR の前後。Phase 2 の検証のワークフローが使う）
    public fun compareEntries(previous: List<PackageEntry>, current: List<PackageEntry>): List<ProjectDiagnostic> {
        val diagnostics = ProjectDiagnostics()
        comparePackages(previous.associate { it.name to it.toIndexPackage() }, current.associate { it.name to it.toIndexPackage() }, diagnostics)
        return diagnostics.list
    }

    private fun comparePackages(previous: Map<String, IndexPackage>, current: Map<String, IndexPackage>, diagnostics: ProjectDiagnostics) {
        for ((name, before) in previous.toSortedMap()) {
            val after = current[name]
            if (after == null) {
                diagnostics.add(problem(ProjectDiagnosticCode.PACKAGE_DISAPPEARED, name))
                continue
            }
            if (after.repositoryId != before.repositoryId) diagnostics.add(problem(ProjectDiagnosticCode.REPOSITORY_CHANGED, name, before.repositoryId.toString(), after.repositoryId.toString()))
            for ((version, old) in before.versions.toSortedMap()) {
                val new = after.versions[version]
                when {
                    new == null -> diagnostics.add(problem(ProjectDiagnosticCode.VERSION_DISAPPEARED, "$name@$version"))
                    new.sha256 != old.sha256 -> diagnostics.add(problem(ProjectDiagnosticCode.SHA256_CHANGED, "$name@$version", old.sha256, new.sha256))
                    new.dependencies != old.dependencies -> diagnostics.add(
                        problem(ProjectDiagnosticCode.DEPENDENCIES_CHANGED, "$name@$version", describe(old.dependencies), describe(new.dependencies)),
                    )
                }
            }
        }
    }

    private fun describe(dependencies: Map<String, String>) = dependencies.toSortedMap().entries.joinToString(", ") { "${it.key}@${it.value}" }.ifEmpty { "none" }

    private fun problem(code: ProjectDiagnosticCode, vararg arguments: String) = ProjectDiagnostic(code, 1, 1, arguments.toList())
}
