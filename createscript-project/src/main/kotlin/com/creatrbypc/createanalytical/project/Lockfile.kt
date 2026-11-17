package com.creatrbypc.createanalytical.project

/**
 * The project itself in a lock file.
 *
 * @property name the same as the name in the manifest.
 * @property version the same as the version in the manifest.
 */
public data class LockRoot(val name: String, val version: String)

/**
 * A package pinned in a lock file. In one lock file a package is identified by [name] (see "Package identity" in
 * `docs/packages.md`).
 *
 * @property name the package name.
 * @property version the exact version.
 * @property source where it comes from (`github:owner/repository`).
 * @property sha256 the SHA-256 (lowercase hex) of the package archive itself (`createscript-package.zip`, not
 *   the source archives that GitHub makes).
 * @property dependencies the names of the packages it uses directly; all of them are in the same lock file.
 */
public data class LockedPackage(val name: String, val version: String, val source: String, val sha256: String, val dependencies: List<String> = emptyList())

/** The contents of `createscript.lock` (lock version 1). Made with [of]; [packages] are sorted by name. */
public class Lockfile private constructor(public val root: LockRoot, public val packages: List<LockedPackage>) {
    override fun equals(other: Any?): Boolean = other is Lockfile && other.root == root && other.packages == packages

    override fun hashCode(): Int = root.hashCode() * 31 + packages.hashCode()

    public companion object {
        /**
         * A lock file with [packages] sorted by name (and their dependencies sorted), whatever their order.
         *
         * @throws IllegalArgumentException if a value is invalid, a name is duplicated or is the project's, a
         *   dependency is not in [packages], there is a cycle, or there are more than [Lockfiles.MAX_PACKAGES].
         */
        public fun of(root: LockRoot, packages: List<LockedPackage>): Lockfile {
            require(ProjectRules.isValidName(root.name) && ProjectRules.isValidVersion(root.version)) { "invalid root: $root" }
            require(packages.size <= Lockfiles.MAX_PACKAGES) { "too many packages" }
            val sorted = packages.map { it.copy(dependencies = it.dependencies.sorted()) }.sortedBy { it.name }
            val names = sorted.map { it.name }
            require(names.distinct().size == names.size) { "duplicate package names" }
            require(root.name !in names) { "a package must not have the name of the project" }
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

/**
 * The result of [Lockfiles.parse].
 *
 * @property lockfile the lock file, or `null` when there is an error.
 * @property diagnostics the problems found (empty when [lockfile] is not `null`).
 * @property truncated `true` when more diagnostics were dropped.
 * @property rootPosition where `root` is.
 * @property packagePositions where the `name` of each package is.
 */
public class LockfileResult(
    public val lockfile: Lockfile?,
    public val diagnostics: List<ProjectDiagnostic>,
    public val truncated: Boolean,
    public val rootPosition: FilePosition = FilePosition(1, 1),
    public val packagePositions: Map<String, FilePosition> = emptyMap(),
)

/** Reads, checks and writes `createscript.lock`. It reads no files. Safe to use from several threads. */
public object Lockfiles {
    /** The file name. */
    public const val FILE_NAME: String = "createscript.lock"

    /** The only lock version. */
    public const val LOCK_VERSION: Long = 1L

    /** The largest file, in bytes. */
    public const val MAX_BYTES: Int = 1024 * 1024

    /** The most packages. */
    public const val MAX_PACKAGES: Int = 4096

    /** The most direct dependencies of one package. */
    public const val MAX_PACKAGE_DEPENDENCIES: Int = 1024

    private val FIELDS = setOf("lockVersion", "root", "packages")
    private val ROOT_FIELDS = setOf("name", "version")
    private val PACKAGE_FIELDS = setOf("name", "version", "source", "sha256", "dependencies")

    /**
     * The canonical text of [lockfile]: `lockVersion`, `root` and `packages` by name, each with `name`, `version`,
     * `source`, `sha256` and `dependencies`. The same lock file always gives the same text (and UTF-8 bytes).
     */
    public fun write(lockfile: Lockfile): String = JsonWriter.write(
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

    /**
     * Reads [bytes] (UTF-8 JSON) and checks what the lock file alone decides: the values, duplicates, references
     * and cycles. Problems are diagnostics, not exceptions. Use [check] for the canonical form and the manifest.
     */
    public fun parse(bytes: ByteArray): LockfileResult {
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
            val name = reader.string("name")?.also { ProjectRules.nameCode(it.value, ProjectRules.isValidName(it.value), ProjectDiagnosticCode.INVALID_NAME)?.let { code -> diagnostics.add(code, it, it.value) } }
            val rootVersion = reader.string("version")?.also { if (!ProjectRules.isValidVersion(it.value)) diagnostics.add(ProjectDiagnosticCode.INVALID_VERSION, it, it.value) }
            if (name != null && rootVersion != null) lockRoot = LockRoot(name.value, rootVersion.value)
        }
        val packages = ArrayList<LockedPackage>()
        val positions = LinkedHashMap<String, FilePosition>()
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
                val name = reader.string("name")?.also { ProjectRules.nameCode(it.value, ProjectRules.isValidName(it.value), ProjectDiagnosticCode.INVALID_NAME)?.let { code -> diagnostics.add(code, it, it.value) } }
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
                            !ProjectRules.isValidName(text.value) -> diagnostics.add(ProjectRules.nameCode(text.value, false, ProjectDiagnosticCode.INVALID_NAME)!!, text, text.value)
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
                positions[name.value] = FilePosition(name.line, name.column)
                if (packageVersion != null && source != null && sha256 != null) packages.add(LockedPackage(name.value, packageVersion.value, source.value, sha256.value, dependencies))
            }
        }
        // プロジェクト自身と同じ名前のパッケージは使えない（マニフェストで自分自身に依存できないのと同じ）
        lockRoot?.let { rootInfo -> positions[rootInfo.name]?.let { diagnostics.add(ProjectDiagnosticCode.DEPENDS_ON_ITSELF, it, rootInfo.name) } }
        // 参照と循環は、パッケージの一覧がそろってから確かめる
        for (entry in packages) {
            for (dependency in entry.dependencies) {
                if (dependency !in positions) diagnostics.add(ProjectDiagnosticCode.LOCK_UNKNOWN_DEPENDENCY, positions[entry.name], entry.name, dependency)
            }
        }
        if (diagnostics.isEmpty) cycleMember(packages)?.let { diagnostics.add(ProjectDiagnosticCode.LOCK_DEPENDENCY_CYCLE, positions[it], it) }
        val rootPosition = rootSection?.let { FilePosition(it.line, it.column) } ?: FilePosition(1, 1)
        if (!diagnostics.isEmpty || lockRoot == null) return LockfileResult(null, diagnostics.list, diagnostics.truncated, rootPosition, positions)
        return LockfileResult(Lockfile.of(lockRoot!!, packages), emptyList(), false, rootPosition, positions)
    }

    /**
     * Checks a lock file that [parse] read: that [bytes] are exactly the canonical form ([write]; otherwise the
     * first different line is reported) and that it matches [manifest] (root, every dependency, no unused
     * packages). Returns [LockfileResult.diagnostics] when [result] has no lock file.
     */
    public fun check(bytes: ByteArray, result: LockfileResult, manifest: Manifest): List<ProjectDiagnostic> {
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
