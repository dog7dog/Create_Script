package com.creatrbypc.createanalytical.install

import com.creatrbypc.createanalytical.project.JsonArray
import com.creatrbypc.createanalytical.project.JsonInteger
import com.creatrbypc.createanalytical.project.JsonNull
import com.creatrbypc.createanalytical.project.JsonObject
import com.creatrbypc.createanalytical.project.JsonParser
import com.creatrbypc.createanalytical.project.JsonString
import com.creatrbypc.createanalytical.project.JsonValue
import com.creatrbypc.createanalytical.project.JsonWriter
import com.creatrbypc.createanalytical.project.PackageArchives
import com.creatrbypc.createanalytical.project.ProjectRules

// 厳密な読み取りの補助。項目の集合が決まったものと完全に同じオブジェクトだけを受け付ける
private object Strict {
    fun obj(value: JsonValue?, fields: Set<String>): JsonObject? = (value as? JsonObject)?.takeIf { it.members.keys == fields }

    fun string(o: JsonObject, key: String): String? = (o.members[key] as? JsonString)?.value

    fun integer(o: JsonObject, key: String): Long? = (o.members[key] as? JsonInteger)?.value

    // 文字列か null（JSON の null）。どちらでもなければ失敗
    fun optionalString(o: JsonObject, key: String): Result<String?> = when (val value = o.members[key]) {
        is JsonString -> Result.success(value.value)
        is JsonNull -> Result.success(null)
        else -> Result.failure(IllegalArgumentException(key))
    }

    fun optional(value: String?): JsonValue = value?.let(JsonWriter::string) ?: JsonNull(0, 0)

    val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
}

// ジャーナルに書く、加えるパッケージ（marker は、そのディレクトリの印のファイルの SHA-256）
internal data class JournalPackage(val name: String, val version: String, val marker: String)

// .createscript/transaction.json（S8.2 Phase 3）。複数のファイルにまたがる更新の、前と後の状態を記録する。
// manifest・lock の old・new は SHA-256（lock の null は「ファイルが無い」）。ジャーナルは信用しない: 読むときに形・値をすべて確かめ、
// 復旧では、今のファイルがジャーナルの old・new のどちらかと同じことを確かめてからでなければ書き換えない
internal data class Journal(
    val id: String,
    val state: State,
    val manifestOld: String,
    val manifestNew: String,
    val lockOld: String?,
    val lockNew: String?,
    val packages: List<JournalPackage>,
) {
    enum class State(val id: String) { PREPARED("prepared"), COMMITTED("committed") }

    fun committed() = copy(state = State.COMMITTED)

    fun write(): ByteArray = JsonWriter.write(
        JsonWriter.obj(
            "journalVersion" to JsonWriter.integer(VERSION),
            "id" to JsonWriter.string(id),
            "state" to JsonWriter.string(state.id),
            "manifest" to JsonWriter.obj("old" to JsonWriter.string(manifestOld), "new" to JsonWriter.string(manifestNew)),
            "lock" to JsonWriter.obj("old" to Strict.optional(lockOld), "new" to Strict.optional(lockNew)),
            "packages" to JsonWriter.array(
                packages.map { JsonWriter.obj("name" to JsonWriter.string(it.name), "version" to JsonWriter.string(it.version), "marker" to JsonWriter.string(it.marker)) },
            ),
        ),
    ).toByteArray(Charsets.UTF_8)

    companion object {
        const val VERSION = 1L
        const val MAX_BYTES = 1024 * 1024
        private val FIELDS = setOf("journalVersion", "id", "state", "manifest", "lock", "packages")
        private val PAIR = setOf("old", "new")
        private val PACKAGE = setOf("name", "version", "marker")

        // 正しい形のジャーナルか（そうでなければ理由）
        fun parse(bytes: ByteArray, maxPackages: Int): Pair<Journal?, String?> {
            fun bad(reason: String) = null to reason
            val root = Strict.obj(JsonParser.parse(bytes, MAX_BYTES).value, FIELDS) ?: return bad("it is not a journal of this version")
            if (Strict.integer(root, "journalVersion") != VERSION) return bad("unsupported journal version")
            val id = Strict.string(root, "id")?.takeIf { Strict.UUID.matches(it) } ?: return bad("invalid id")
            val state = Strict.string(root, "state")?.let { s -> State.entries.firstOrNull { it.id == s } } ?: return bad("invalid state")
            val manifest = Strict.obj(root.members["manifest"], PAIR) ?: return bad("invalid manifest field")
            val manifestOld = Strict.string(manifest, "old")?.takeIf(ProjectRules::isValidSha256) ?: return bad("invalid manifest SHA-256")
            val manifestNew = Strict.string(manifest, "new")?.takeIf(ProjectRules::isValidSha256) ?: return bad("invalid manifest SHA-256")
            val lock = Strict.obj(root.members["lock"], PAIR) ?: return bad("invalid lock field")
            val lockOld = Strict.optionalString(lock, "old").getOrElse { return bad("invalid lock SHA-256") }
            val lockNew = Strict.optionalString(lock, "new").getOrElse { return bad("invalid lock SHA-256") }
            if (listOfNotNull(lockOld, lockNew).any { !ProjectRules.isValidSha256(it) }) return bad("invalid lock SHA-256")
            val list = root.members["packages"] as? JsonArray ?: return bad("invalid packages field")
            if (list.elements.size > maxPackages) return bad("too many packages")
            val packages = ArrayList<JournalPackage>()
            for (element in list.elements) {
                val entry = Strict.obj(element, PACKAGE) ?: return bad("invalid package entry")
                val name = Strict.string(entry, "name")?.takeIf(ProjectRules::isValidName) ?: return bad("invalid package name")
                val version = Strict.string(entry, "version")?.takeIf(ProjectRules::isValidVersion) ?: return bad("invalid package version")
                val marker = Strict.string(entry, "marker")?.takeIf(ProjectRules::isValidSha256) ?: return bad("invalid marker SHA-256")
                if (packages.any { it.name == name }) return bad("duplicate package $name")
                packages.add(JournalPackage(name, version, marker))
            }
            return Journal(id, state, manifestOld, manifestNew, lockOld, lockNew, packages) to null
        }
    }
}

// インストールしたパッケージのディレクトリの印 .createscript-package.json（S8.2 Phase 3）。どのインストール（transaction）が、
// どのアーカイブ（sha256）から、どのファイル（パスと SHA-256）を置いたか。印も信用しない: ディレクトリの中身が印と完全に一致するときだけ使う
internal data class Marker(
    val transaction: String,
    val name: String,
    val version: String,
    val source: String,
    val sha256: String,
    val files: Map<String, String>,
) {
    fun write(): ByteArray = JsonWriter.write(
        JsonWriter.obj(
            "markerVersion" to JsonWriter.integer(VERSION),
            "transaction" to JsonWriter.string(transaction),
            "name" to JsonWriter.string(name),
            "version" to JsonWriter.string(version),
            "source" to JsonWriter.string(source),
            "sha256" to JsonWriter.string(sha256),
            "files" to JsonWriter.array(files.toSortedMap().map { (path, sha) -> JsonWriter.obj("path" to JsonWriter.string(path), "sha256" to JsonWriter.string(sha)) }),
        ),
    ).toByteArray(Charsets.UTF_8)

    companion object {
        const val VERSION = 1L
        const val MAX_BYTES = 256 * 1024
        private val FIELDS = setOf("markerVersion", "transaction", "name", "version", "source", "sha256", "files")
        private val FILE = setOf("path", "sha256")

        fun parse(bytes: ByteArray): Marker? {
            val root = Strict.obj(JsonParser.parse(bytes, MAX_BYTES).value, FIELDS) ?: return null
            if (Strict.integer(root, "markerVersion") != VERSION) return null
            val transaction = Strict.string(root, "transaction")?.takeIf { Strict.UUID.matches(it) } ?: return null
            val name = Strict.string(root, "name")?.takeIf(ProjectRules::isValidName) ?: return null
            val version = Strict.string(root, "version")?.takeIf(ProjectRules::isValidVersion) ?: return null
            val source = Strict.string(root, "source")?.takeIf(ProjectRules::isValidSource) ?: return null
            val sha256 = Strict.string(root, "sha256")?.takeIf(ProjectRules::isValidSha256) ?: return null
            val list = root.members["files"] as? JsonArray ?: return null
            if (list.elements.size > PackageArchives.MAX_ENTRIES) return null
            val files = java.util.TreeMap<String, String>()
            for (element in list.elements) {
                val entry = Strict.obj(element, FILE) ?: return null
                val path = Strict.string(entry, "path")?.takeIf { PackageArchives.pathProblem(it) == null } ?: return null
                val sha = Strict.string(entry, "sha256")?.takeIf(ProjectRules::isValidSha256) ?: return null
                if (files.put(path, sha) != null) return null
            }
            if (com.creatrbypc.createanalytical.project.Manifests.FILE_NAME !in files) return null
            return Marker(transaction, name, version, source, sha256, files)
        }
    }
}
