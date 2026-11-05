package com.creatrbypc.createanalytical.project

// ロックファイルの root（このプロジェクト自身。マニフェストの name・version と同じ）
data class LockRoot(val name: String, val version: String)

// 固定したパッケージ（S8.1）。パッケージの識別の規則は docs/packages.md の「パッケージの識別」:
// - 1 つのロックファイルの中で、パッケージは name で識別する（同じ name は 1 つだけ。同じ名前の別の版・別の取得元を同時には使わない）
// - version・source は、その name をどこのどの版から取るか、sha256 は取ったパッケージのアーカイブのバイト列そのもの（正式なパッケージアーカイブ。
//   GitHub が自動で作るソースの ZIP・tar.gz は対象にしない）
// - dependencies は、このパッケージが直接使うパッケージの name（名前の順・重複なし。どれもこのロックファイルにある）
data class LockedPackage(val name: String, val version: String, val source: String, val sha256: String, val dependencies: List<String> = emptyList())

// createscript.lock（lockVersion 1）。packages は名前の順に並べたもの（of で作る）
class Lockfile private constructor(val root: LockRoot, val packages: List<LockedPackage>) {
    override fun equals(other: Any?) = other is Lockfile && other.root == root && other.packages == packages

    override fun hashCode() = root.hashCode() * 31 + packages.hashCode()

    companion object {
        // 並べ替えて作る（入力の順に関係なく同じ内容になる）。値の形・重複・参照・循環が正しくなければ IllegalArgumentException
        fun of(root: LockRoot, packages: List<LockedPackage>): Lockfile {
            require(ProjectRules.isValidName(root.name) && ProjectRules.isValidVersion(root.version)) { "invalid root: $root" }
            require(packages.size <= Lockfiles.MAX_PACKAGES) { "too many packages" }
            val sorted = packages.map { it.copy(dependencies = it.dependencies.sorted()) }.sortedBy { it.name }
            val names = sorted.map { it.name }
            require(names.distinct().size == names.size) { "duplicate package names" }
            for (entry in sorted) {
                require(ProjectRules.isValidName(entry.name) && ProjectRules.isValidVersion(entry.version)) { "invalid package: ${entry.name}" }
                require(ProjectRules.isValidSource(entry.source) && ProjectRules.isValidSha256(entry.sha256)) { "invalid package: ${entry.name}" }
                require(entry.dependencies.distinct().size == entry.dependencies.size && entry.dependencies.all { it in names }) { "invalid dependencies of ${entry.name}" }
            }
            require(Lockfiles.cycleMember(sorted) == null) { "dependency cycle" }
            return Lockfile(root, sorted)
        }
    }
}

// lockfile はエラーが無いときだけ。packagePositions はパッケージの name の値の位置
class LockfileResult(
    val lockfile: Lockfile?,
    val diagnostics: List<ProjectDiagnostic>,
    val truncated: Boolean,
    val rootPosition: Pair<Int, Int> = 1 to 1,
    val packagePositions: Map<String, Pair<Int, Int>> = emptyMap(),
)

// createscript.lock の読み取り・検査・書き出し（S8.1。ファイルの読み書きはしない）
object Lockfiles {
    const val FILE_NAME = "createscript.lock"
    const val LOCK_VERSION = 1L
    const val MAX_BYTES = 1024 * 1024
    const val MAX_PACKAGES = 4096
    const val MAX_PACKAGE_DEPENDENCIES = 1024

    private val FIELDS = setOf("lockVersion", "root", "packages")
    private val ROOT_FIELDS = setOf("name", "version")
    private val PACKAGE_FIELDS = setOf("name", "version", "source", "sha256", "dependencies")

    // 決まった形の書き出し。lockVersion・root（name・version）・packages（名前の順。各パッケージは name・version・source・sha256・dependencies の順）。
    // 同じ内容なら、必ず同じ文字列（UTF-8 にして同じバイト列）になる
    fun write(lockfile: Lockfile): String = JsonWriter.write(
        JsonWriter.obj(
            "lockVersion" to JsonWriter.integer(LOCK_VERSION),
            "root" to JsonWriter.obj("name" to JsonWriter.string(lockfile.root.name), "version" to JsonWriter.string(lockfile.root.version)),
            "packages" to JsonWriter.array(
                lockfile.packages.map { entry ->
                    JsonWriter.obj(
                        "name" to JsonWriter.string(entry.name),
                        "version" to JsonWriter.string(entry.version),
                        "source" to JsonWriter.string(entry.source),
                        "sha256" to JsonWriter.string(entry.sha256),
                        "dependencies" to JsonWriter.array(entry.dependencies.map(JsonWriter::string)),
                    )
                },
            ),
        ),
    )

    // 読み取りと、ロックファイルの中だけで決まる検査（値の形・重複・参照・循環）。正規形・マニフェストとの整合は check で確かめる
    fun parse(bytes: ByteArray): LockfileResult {
        val json = JsonParser.parse(bytes, MAX_BYTES)
        val root = json.value ?: return LockfileResult(null, json.diagnostics, false)
        val diagnostics = ProjectDiagnostics()
        if (root !is JsonObject) {
            diagnostics.add(ProjectDiagnosticCode.NOT_AN_OBJECT, root, "createscript.lock")
            return LockfileResult(null, diagnostics.list, diagnostics.truncated)
        }
        val fields = FieldReader(diagnostics, root, "")
        val version = fields.integer("lockVersion") ?: return LockfileResult(null, diagnostics.list, diagnostics.truncated)
        if (version.value != LOCK_VERSION) {
            diagnostics.add(ProjectDiagnosticCode.UNSUPPORTED_LOCK_VERSION, version, version.value.toString(), LOCK_VERSION.toString())
            return LockfileResult(null, diagnostics.list, diagnostics.truncated)
        }
        fields.allowOnly(FIELDS)
        val rootSection = fields.obj("root")
        var lockRoot: LockRoot? = null
        rootSection?.let { section ->
            val reader = FieldReader(diagnostics, section, "root")
            reader.allowOnly(ROOT_FIELDS)
            val name = reader.string("name")?.also { if (!ProjectRules.isValidName(it.value)) diagnostics.add(ProjectDiagnosticCode.INVALID_NAME, it, it.value) }
            val rootVersion = reader.string("version")?.also { if (!ProjectRules.isValidVersion(it.value)) diagnostics.add(ProjectDiagnosticCode.INVALID_VERSION, it, it.value) }
            if (name != null && rootVersion != null) lockRoot = LockRoot(name.value, rootVersion.value)
        }
        val packages = ArrayList<LockedPackage>()
        val positions = LinkedHashMap<String, Pair<Int, Int>>()
        fields.array("packages")?.let { list ->
            if (list.elements.size > MAX_PACKAGES) diagnostics.add(ProjectDiagnosticCode.TOO_MANY_ENTRIES, list, "packages", MAX_PACKAGES.toString())
            for ((index, element) in list.elements.withIndex()) {
                val path = "packages[$index]"
                val entry = element as? JsonObject
                if (entry == null) {
                    diagnostics.add(ProjectDiagnosticCode.WRONG_TYPE, element, path, "object")
                    continue
                }
                val reader = FieldReader(diagnostics, entry, path)
                reader.allowOnly(PACKAGE_FIELDS)
                val name = reader.string("name")?.also { if (!ProjectRules.isValidName(it.value)) diagnostics.add(ProjectDiagnosticCode.INVALID_NAME, it, it.value) }
                val packageVersion = reader.string("version")?.also { if (!ProjectRules.isValidVersion(it.value)) diagnostics.add(ProjectDiagnosticCode.INVALID_VERSION, it, it.value) }
                val source = reader.string("source")?.also { if (!ProjectRules.isValidSource(it.value)) diagnostics.add(ProjectDiagnosticCode.INVALID_SOURCE, it, it.value) }
                val sha256 = reader.string("sha256")?.also { if (!ProjectRules.isValidSha256(it.value)) diagnostics.add(ProjectDiagnosticCode.INVALID_SHA256, it, it.value) }
                val dependencies = ArrayList<String>()
                reader.array("dependencies")?.let { names ->
                    if (names.elements.size > MAX_PACKAGE_DEPENDENCIES) diagnostics.add(ProjectDiagnosticCode.TOO_MANY_ENTRIES, names, "$path.dependencies", MAX_PACKAGE_DEPENDENCIES.toString())
                    for (dependency in names.elements) {
                        val text = dependency as? JsonString
                        when {
                            text == null -> diagnostics.add(ProjectDiagnosticCode.WRONG_TYPE, dependency, "$path.dependencies", "string")
                            !ProjectRules.isValidName(text.value) -> diagnostics.add(ProjectDiagnosticCode.INVALID_NAME, text, text.value)
                            text.value in dependencies -> diagnostics.add(ProjectDiagnosticCode.LOCK_DUPLICATE_DEPENDENCY, text, name?.value ?: path, text.value)
                            else -> dependencies.add(text.value)
                        }
                    }
                }
                if (name == null) continue
                if (name.value in positions) {
                    diagnostics.add(ProjectDiagnosticCode.LOCK_DUPLICATE_PACKAGE, name, name.value)
                    continue
                }
                positions[name.value] = name.line to name.column
                if (packageVersion != null && source != null && sha256 != null) packages.add(LockedPackage(name.value, packageVersion.value, source.value, sha256.value, dependencies))
            }
        }
        // 参照と循環は、パッケージの一覧がそろってから確かめる
        for (entry in packages) {
            for (dependency in entry.dependencies) {
                if (dependency !in positions) diagnostics.add(ProjectDiagnosticCode.LOCK_UNKNOWN_DEPENDENCY, positions[entry.name], entry.name, dependency)
            }
        }
        if (diagnostics.isEmpty) cycleMember(packages)?.let { diagnostics.add(ProjectDiagnosticCode.LOCK_DEPENDENCY_CYCLE, positions[it], it) }
        val rootPosition = rootSection?.let { it.line to it.column } ?: (1 to 1)
        if (!diagnostics.isEmpty || lockRoot == null) return LockfileResult(null, diagnostics.list, diagnostics.truncated, rootPosition, positions)
        return LockfileResult(Lockfile.of(lockRoot!!, packages), emptyList(), false, rootPosition, positions)
    }

    // 正規形（write の出力）と同じバイト列か、マニフェストと整合するか。どちらも parse が成功したロックファイルについて確かめる。
    // 正規形でなければ、最初に違う行を報告する
    fun check(bytes: ByteArray, result: LockfileResult, manifest: Manifest): List<ProjectDiagnostic> {
        val lockfile = result.lockfile ?: return result.diagnostics
        val diagnostics = ProjectDiagnostics()
        val canonical = write(lockfile).toByteArray(Charsets.UTF_8)
        if (!canonical.contentEquals(bytes)) {
            val actual = bytes.toString(Charsets.UTF_8).split('\n')
            val expected = String(canonical, Charsets.UTF_8).split('\n')
            val line = actual.indices.firstOrNull { it >= expected.size || actual[it] != expected[it] } ?: actual.size
            diagnostics.add(ProjectDiagnostic(ProjectDiagnosticCode.LOCK_NOT_CANONICAL, line + 1, 1))
        }
        if (lockfile.root.name != manifest.name) diagnostics.add(ProjectDiagnosticCode.LOCK_ROOT_MISMATCH, result.rootPosition, "name", manifest.name, lockfile.root.name)
        if (lockfile.root.version != manifest.version) diagnostics.add(ProjectDiagnosticCode.LOCK_ROOT_MISMATCH, result.rootPosition, "version", manifest.version, lockfile.root.version)
        val byName = lockfile.packages.associateBy { it.name }
        for (dependency in manifest.dependencies) {
            val locked = byName[dependency.name]
            when {
                locked == null -> diagnostics.add(ProjectDiagnosticCode.LOCK_MISSING_PACKAGE, result.rootPosition, dependency.name)
                locked.version != dependency.version ->
                    diagnostics.add(ProjectDiagnosticCode.LOCK_VERSION_MISMATCH, result.packagePositions[dependency.name], dependency.name, dependency.version, locked.version)
                locked.source != dependency.source ->
                    diagnostics.add(ProjectDiagnosticCode.LOCK_SOURCE_MISMATCH, result.packagePositions[dependency.name], dependency.name, dependency.source, locked.source)
            }
        }
        // マニフェストの依存から、パッケージの依存をたどって届かないパッケージは使われない
        val reached = HashSet<String>()
        val pending = ArrayList(manifest.dependencies.map { it.name }.filter { it in byName })
        while (pending.isNotEmpty()) {
            val name = pending.removeAt(pending.lastIndex)
            if (reached.add(name)) pending.addAll(byName.getValue(name).dependencies)
        }
        for (entry in lockfile.packages) if (entry.name !in reached) diagnostics.add(ProjectDiagnosticCode.LOCK_UNUSED_PACKAGE, result.packagePositions[entry.name], entry.name)
        return diagnostics.list
    }

    // 依存の循環の上にあるパッケージ（名前の順で最初に見つかったもの）。無ければ null。再帰せずに深さ優先でたどる（存在しない依存は無視する）
    internal fun cycleMember(packages: List<LockedPackage>): String? {
        val byName = packages.associateBy { it.name }
        // 0: 未訪問、1: たどっている途中、2: 済み
        val state = HashMap<String, Int>()
        for (start in packages.map { it.name }.sorted()) {
            if (state[start] != null) continue
            val stack = arrayListOf(start to 0)
            state[start] = 1
            while (stack.isNotEmpty()) {
                val (name, next) = stack.last()
                val dependencies = byName[name]?.dependencies.orEmpty()
                if (next < dependencies.size) {
                    stack[stack.lastIndex] = name to next + 1
                    val dependency = dependencies[next]
                    if (dependency !in byName) continue
                    when (state[dependency]) {
                        1 -> return dependency
                        null -> {
                            state[dependency] = 1
                            stack.add(dependency to 0)
                        }
                    }
                } else {
                    state[name] = 2
                    stack.removeAt(stack.lastIndex)
                }
            }
        }
        return null
    }
}
