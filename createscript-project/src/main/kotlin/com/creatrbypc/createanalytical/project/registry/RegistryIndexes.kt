package com.creatrbypc.createanalytical.project.registry

import com.creatrbypc.createanalytical.project.CanonicalJson
import com.creatrbypc.createanalytical.project.FieldReader
import com.creatrbypc.createanalytical.project.JsonObject
import com.creatrbypc.createanalytical.project.JsonParser
import com.creatrbypc.createanalytical.project.JsonValue
import com.creatrbypc.createanalytical.project.JsonWriter
import com.creatrbypc.createanalytical.project.ProjectDiagnostic
import com.creatrbypc.createanalytical.project.ProjectDiagnosticCode
import com.creatrbypc.createanalytical.project.ProjectDiagnostics
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

// index.json（indexVersion 1）の読み取り・検査・正規形の書き出しと、リポジトリのファイルからの生成（S8.3 Phase 1。docs/registry.md）。
// index.json のバイト列は CanonicalJson の正規形（1 行・キーの順・UTF-8）で、署名はこのバイト列そのものに対して行う。
// 正規形でない index は、内容が正しくても拒否する（同じ内容が必ず同じバイト列・同じ SHA-256 になるように）
object RegistryIndexes {
    const val INDEX_VERSION = 1L
    const val FILE_NAME = "index.json"

    private val FIELDS = setOf("indexVersion", "serial", "generated", "expires", "packages")
    private val PACKAGE_FIELDS = setOf("source", "repositoryId", "description", "license", "removed", "versions")
    private val VERSION_FIELDS = setOf("sha256", "dependencies", "pullRequest", "yanked")

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // 正規形のバイト列
    fun write(index: RegistryIndex): ByteArray = CanonicalJson.write(
        JsonWriter.obj(
            "indexVersion" to JsonWriter.integer(INDEX_VERSION),
            "serial" to JsonWriter.integer(index.serial),
            "generated" to JsonWriter.string(RegistryRules.formatTimestamp(index.generated)),
            "expires" to JsonWriter.string(RegistryRules.formatTimestamp(index.expires)),
            "packages" to JsonWriter.obj(
                *index.packages.map { (name, entry) ->
                    val members = arrayListOf<Pair<String, JsonValue>>(
                        "source" to JsonWriter.string(entry.source),
                        "repositoryId" to JsonWriter.integer(entry.repositoryId),
                        "description" to JsonWriter.string(entry.description),
                        "license" to JsonWriter.string(entry.license),
                        "versions" to JsonWriter.obj(
                            *entry.versions.map { (version, value) ->
                                val fields = arrayListOf<Pair<String, JsonValue>>(
                                    "sha256" to JsonWriter.string(value.sha256),
                                    "dependencies" to RegistryFiles.dependencies(value.dependencies),
                                    "pullRequest" to JsonWriter.integer(value.pullRequest),
                                )
                                value.yanked?.let { fields += "yanked" to RegistryFiles.withdrawal(it) }
                                version to JsonWriter.obj(*fields.toTypedArray())
                            }.toTypedArray(),
                        ),
                    )
                    entry.removed?.let { members += "removed" to RegistryFiles.withdrawal(it) }
                    name to JsonWriter.obj(*members.toTypedArray())
                }.toTypedArray(),
            ),
        ),
    )

    // 読み取りと、index の中だけで決まる検査（正規形・値の形・件数・日時の関係・パッケージの間の規則）。署名と、以前の index との比較は IndexVerifier が行う
    fun parse(bytes: ByteArray): RegistryResult<RegistryIndex> {
        val diagnostics = ProjectDiagnostics()
        val json = JsonParser.parse(bytes, RegistryLimits.MAX_INDEX_BYTES)
        val root = json.value ?: return RegistryResult(null, json.diagnostics)
        // 正規形（値を読む前に確かめる。正規形でないものは、どこが違っても同じ 1 つのエラー）
        val canonical = try {
            CanonicalJson.write(root)
        } catch (error: IllegalArgumentException) {
            null
        }
        if (canonical == null || !canonical.contentEquals(bytes)) {
            diagnostics.add(ProjectDiagnosticCode.REGISTRY_NOT_CANONICAL, root, "index.json must be canonical JSON (RFC 8785 for integers: no spaces, sorted keys, minimal escapes, UTF-8)")
            return RegistryResult(null, diagnostics.list)
        }
        val reader = RegistryReader(diagnostics)
        if (root !is JsonObject) {
            diagnostics.add(ProjectDiagnosticCode.NOT_AN_OBJECT, root, FILE_NAME)
            return RegistryResult(null, diagnostics.list)
        }
        val fields = FieldReader(diagnostics, root, "")
        val version = fields.integer("indexVersion") ?: return RegistryResult(null, diagnostics.list)
        if (version.value != INDEX_VERSION) {
            diagnostics.add(ProjectDiagnosticCode.UNSUPPORTED_INDEX_VERSION, version, version.value.toString(), INDEX_VERSION.toString())
            return RegistryResult(null, diagnostics.list)
        }
        fields.allowOnly(FIELDS)
        val serial = reader.id(fields.integer("serial"), "serial")
        val generatedValue = fields.string("generated")
        val generated = reader.timestamp(generatedValue, "generated")
        val expires = reader.timestamp(fields.string("expires"), "expires")
        if (generated != null && expires != null) validityProblem(generated, expires)?.let { diagnostics.add(ProjectDiagnosticCode.INVALID_VALIDITY, generatedValue, it) }
        val packages = LinkedHashMap<String, IndexPackage>()
        val positions = HashMap<String, Pair<Int, Int>>()
        fields.obj("packages")?.let { section ->
            if (section.members.size > RegistryLimits.MAX_PACKAGES) {
                diagnostics.add(ProjectDiagnosticCode.TOO_MANY_ENTRIES, section, "packages", RegistryLimits.MAX_PACKAGES.toString())
                return RegistryResult(null, diagnostics.list)
            }
            val total = section.members.values.sumOf { ((it as? JsonObject)?.members?.get("versions") as? JsonObject)?.members?.size ?: 0 }
            if (total > RegistryLimits.MAX_TOTAL_VERSIONS) {
                diagnostics.add(ProjectDiagnosticCode.TOO_MANY_ENTRIES, section, "versions of all packages", RegistryLimits.MAX_TOTAL_VERSIONS.toString())
                return RegistryResult(null, diagnostics.list)
            }
            for ((key, value) in section.members) {
                val position = section.keyPositions[key]
                val name = reader.name(key, position)
                val path = "packages.$key"
                val obj = value as? JsonObject
                if (obj == null) {
                    diagnostics.add(ProjectDiagnosticCode.WRONG_TYPE, value, path, "object")
                    continue
                }
                val entry = indexPackage(reader, obj, path, key) ?: continue
                if (name != null) {
                    packages[name] = entry
                    positions[name] = position ?: (obj.line to obj.column)
                }
            }
        }
        if (diagnostics.isEmpty) checkPackages(packages, diagnostics) { positions[it] }
        if (!diagnostics.isEmpty || serial == null || generated == null || expires == null) return RegistryResult(null, diagnostics.list, diagnostics.truncated)
        return RegistryResult(RegistryIndex(serial, generated, expires, packages), emptyList())
    }

    private fun indexPackage(reader: RegistryReader, obj: JsonObject, path: String, name: String): IndexPackage? {
        val diagnostics = reader.diagnostics
        val fields = FieldReader(diagnostics, obj, path)
        fields.allowOnly(PACKAGE_FIELDS)
        val source = reader.source(fields.string("source"))
        val repositoryId = reader.id(fields.integer("repositoryId"), "$path.repositoryId")
        val description = reader.text(fields.string("description"), "$path.description")
        val license = reader.license(fields.string("license"))
        val removed = reader.withdrawal(fields.obj("removed", required = false), "$path.removed")
        val versions = LinkedHashMap<String, IndexVersion>()
        var ok = true
        val keys = reader.versionKeys(fields.obj("versions"), "$path.versions", name)
        if (keys == null) ok = false
        keys?.forEach { (version, value) ->
            val versionPath = "$path.versions.$version"
            val versionObj = value as? JsonObject
            if (versionObj == null) {
                diagnostics.add(ProjectDiagnosticCode.WRONG_TYPE, value, versionPath, "object")
                ok = false
                return@forEach
            }
            val versionFields = FieldReader(diagnostics, versionObj, versionPath)
            versionFields.allowOnly(VERSION_FIELDS)
            val sha256 = reader.sha256(versionFields.string("sha256"))
            val dependencies = reader.dependencies(versionFields.obj("dependencies"), "$versionPath.dependencies")
            val pullRequest = reader.id(versionFields.integer("pullRequest"), "$versionPath.pullRequest")
            val yanked = reader.withdrawal(versionFields.obj("yanked", required = false), "$versionPath.yanked")
            if (sha256 != null && dependencies != null && pullRequest != null) versions[version] = IndexVersion(sha256, dependencies, pullRequest, yanked) else ok = false
        }
        if (!ok || source == null || repositoryId == null || description == null || license == null) return null
        return IndexPackage(source, repositoryId, description, license, removed, versions)
    }

    // generated・expires の関係。正しければ null
    internal fun validityProblem(generated: Instant, expires: Instant): String? = when {
        !expires.isAfter(generated) -> "expires must be after generated"
        Duration.between(generated, expires) > Duration.ofDays(RegistryLimits.MAX_VALIDITY_DAYS.toLong()) -> "an index must not be valid for more than ${RegistryLimits.MAX_VALIDITY_DAYS} days"
        else -> null
    }

    // ---- パッケージの間の規則（index の読み取りと、リポジトリのファイルからの生成で共通） ----

    // - 似た名前（RegistryRules.skeleton が同じ）のパッケージは 1 つだけ
    // - 取得元（大文字・小文字を区別しない）・リポジトリの ID は、1 つのパッケージだけ
    // - 依存はレジストリにある名前・版だけ（審査済みのパッケージは、審査済みのパッケージにだけ依存する）。自分自身には依存しない
    // - 削除したパッケージに依存できるのは、取り下げた版か、削除したパッケージの版だけ
    // - (名前, 版) の依存に循環が無い。1 つの版の依存をすべて合わせても、同じ名前の別の版が無く、MAX_CLOSURE 以下（S8.2 の 1 つのロックファイルに入る）
    internal fun checkPackages(packages: Map<String, IndexPackage>, diagnostics: ProjectDiagnostics, position: (String) -> Pair<Int, Int>?) {
        val names = packages.keys.sorted()
        val forms = HashMap<String, String>()
        val sources = HashMap<String, String>()
        val repositories = HashMap<Long, String>()
        for (name in names) {
            val entry = packages.getValue(name)
            forms.putIfAbsent(RegistryRules.skeleton(name), name)?.let { diagnostics.add(ProjectDiagnosticCode.SIMILAR_NAME, position(name), name, it) }
            sources.putIfAbsent(entry.source.lowercase(), name)?.let { diagnostics.add(ProjectDiagnosticCode.DUPLICATE_SOURCE, position(name), entry.source, it, name) }
            repositories.putIfAbsent(entry.repositoryId, name)?.let { diagnostics.add(ProjectDiagnosticCode.DUPLICATE_REPOSITORY, position(name), entry.repositoryId.toString(), it, name) }
        }
        var dependenciesOk = true
        for (name in names) {
            val entry = packages.getValue(name)
            for ((version, value) in entry.versions.toSortedMap()) {
                for ((dependency, dependencyVersion) in value.dependencies.toSortedMap()) {
                    val target = packages[dependency]
                    when {
                        dependency == name -> diagnostics.add(ProjectDiagnosticCode.DEPENDS_ON_ITSELF, position(name), "$name@$version")
                        target?.versions?.get(dependencyVersion) == null ->
                            diagnostics.add(ProjectDiagnosticCode.UNKNOWN_DEPENDENCY, position(name), "$name@$version", "$dependency@$dependencyVersion")
                        target.removed != null && value.yanked == null && entry.removed == null ->
                            diagnostics.add(ProjectDiagnosticCode.DEPENDENCY_ON_REMOVED, position(name), "$name@$version", dependency)
                        else -> continue
                    }
                    dependenciesOk = false
                }
            }
        }
        if (!dependenciesOk || !diagnostics.isEmpty) return
        cycleMember(packages)?.let { (name, version) ->
            diagnostics.add(ProjectDiagnosticCode.DEPENDENCY_CYCLE, position(name), "$name@$version")
            return
        }
        for (name in names) {
            for ((version, _) in packages.getValue(name).versions.toSortedMap()) {
                closureProblem(packages, name, version)?.let { problem ->
                    diagnostics.add(problem.first, position(name), *problem.second.toTypedArray())
                }
            }
        }
    }

    // (名前, 版) の依存の循環の上にあるもの。無ければ null。再帰せずに深さ優先でたどる
    private fun cycleMember(packages: Map<String, IndexPackage>): Pair<String, String>? {
        // 0 が無い: 未訪問、1: たどっている途中、2: 済み
        val state = HashMap<Pair<String, String>, Int>()
        for (name in packages.keys.sorted()) {
            for (version in packages.getValue(name).versions.keys.sorted()) {
                val start = name to version
                if (state[start] != null) continue
                state[start] = 1
                val stack = arrayListOf(start to packages.getValue(name).versions.getValue(version).dependencies.toList().sortedBy { it.first })
                val next = arrayListOf(0)
                while (stack.isNotEmpty()) {
                    val (node, dependencies) = stack.last()
                    val i = next.last()
                    if (i < dependencies.size) {
                        next[next.lastIndex] = i + 1
                        val dependency = dependencies[i]
                        when (state[dependency]) {
                            1 -> return dependency
                            null -> {
                                state[dependency] = 1
                                stack.add(dependency to packages.getValue(dependency.first).versions.getValue(dependency.second).dependencies.toList().sortedBy { it.first })
                                next.add(0)
                            }
                        }
                    } else {
                        state[node] = 2
                        stack.removeAt(stack.lastIndex)
                        next.removeAt(next.lastIndex)
                    }
                }
            }
        }
        return null
    }

    // name@version の依存をすべて合わせたときの問題（同じ名前の別の版・多すぎる）。無ければ null
    private fun closureProblem(packages: Map<String, IndexPackage>, name: String, version: String): Pair<ProjectDiagnosticCode, List<String>>? {
        val chosen = hashMapOf(name to version)
        val pending = arrayListOf(name to version)
        while (pending.isNotEmpty()) {
            val (current, currentVersion) = pending.removeAt(pending.lastIndex)
            for ((dependency, dependencyVersion) in packages.getValue(current).versions.getValue(currentVersion).dependencies.toSortedMap()) {
                val existing = chosen[dependency]
                when {
                    existing == null -> {
                        chosen[dependency] = dependencyVersion
                        if (chosen.size > RegistryLimits.MAX_CLOSURE) return ProjectDiagnosticCode.TOO_MANY_ENTRIES to listOf("dependencies of $name@$version", RegistryLimits.MAX_CLOSURE.toString())
                        pending.add(dependency to dependencyVersion)
                    }
                    existing != dependencyVersion -> {
                        val (first, second) = listOf(existing, dependencyVersion).sorted()
                        return ProjectDiagnosticCode.DEPENDENCY_CONFLICT to listOf("$name@$version", dependency, first, second)
                    }
                }
            }
        }
        return null
    }

    // ---- リポジトリのファイルからの生成（Phase 2 の公開のワークフローが使う） ----

    // entries（読み取りと検査を通ったもの）から index を作る。予約名・同じ名前の重複と、パッケージの間の規則を確かめる。
    // expires は generated の config.indexValidityDays 日後。generated は秒の単位
    fun build(config: RegistryConfig, reserved: ReservedNames, entries: List<PackageEntry>, serial: Long, generated: Instant): RegistryResult<RegistryIndex> {
        val diagnostics = ProjectDiagnostics()
        if (!RegistryRules.isValidId(serial)) diagnostics.add(ProjectDiagnosticCode.INVALID_ID, null as JsonValue?, "serial", serial.toString())
        if (generated.nano != 0) diagnostics.add(ProjectDiagnosticCode.INVALID_DATE, null as JsonValue?, "generated", generated.toString())
        if (entries.size > RegistryLimits.MAX_PACKAGES) diagnostics.add(ProjectDiagnosticCode.TOO_MANY_ENTRIES, null as JsonValue?, "packages", RegistryLimits.MAX_PACKAGES.toString())
        val reservedForms = reserved.names.keys.associateBy(RegistryRules::skeleton)
        val packages = LinkedHashMap<String, IndexPackage>()
        for (entry in entries.sortedBy { it.name }) {
            if (entry.name in packages) {
                diagnostics.add(ProjectDiagnosticCode.DUPLICATE_PACKAGE, null as JsonValue?, entry.name)
                continue
            }
            reservedForms[RegistryRules.skeleton(entry.name)]?.let { diagnostics.add(ProjectDiagnosticCode.RESERVED_PACKAGE_NAME, null as JsonValue?, entry.name, it) }
            packages[entry.name] = entry.toIndexPackage()
        }
        if (packages.values.sumOf { it.versions.size } > RegistryLimits.MAX_TOTAL_VERSIONS) {
            diagnostics.add(ProjectDiagnosticCode.TOO_MANY_ENTRIES, null as JsonValue?, "versions of all packages", RegistryLimits.MAX_TOTAL_VERSIONS.toString())
        }
        if (diagnostics.isEmpty) checkPackages(packages, diagnostics) { null }
        if (!diagnostics.isEmpty) return RegistryResult(null, diagnostics.list, diagnostics.truncated)
        val index = RegistryIndex(serial, generated, generated.plus(Duration.ofDays(config.indexValidityDays.toLong())), packages)
        // 作った index は、読み取りの検査もそのまま通る（念のため確かめる）
        val check = parse(write(index))
        return if (check.value == index) RegistryResult(index, emptyList()) else RegistryResult(null, check.diagnostics.ifEmpty { listOf(ProjectDiagnostic(ProjectDiagnosticCode.REGISTRY_NOT_CANONICAL, 1, 1, listOf("the index does not read back as written"))) })
    }
}
