package com.creatrbypc.createanalytical.registrytool

import com.creatrbypc.createanalytical.project.ProjectDiagnostic
import com.creatrbypc.createanalytical.project.registry.PackageEntry
import com.creatrbypc.createanalytical.project.registry.RegistryConfig
import com.creatrbypc.createanalytical.project.registry.RegistryFiles
import com.creatrbypc.createanalytical.project.registry.ReservedNames
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

// 道具の結果（エラーと、審査する人への知らせ）。文はファイルの中身（信頼できない）を含みうるので、表示するときに制御文字を \uXXXX にする
class Report {
    val errors = ArrayList<String>()
    val notices = ArrayList<String>()

    val ok: Boolean get() = errors.isEmpty()

    fun error(message: String) {
        errors += message
    }

    fun notice(message: String) {
        notices += message
    }

    fun addAll(other: Report) {
        errors += other.errors
        notices += other.notices
    }

    // 1 行に 1 件。行の始まりは必ず "error: " か "notice: " で、改行・制御文字は残さない（GitHub Actions のワークフローコマンド "::…" として解釈されないように）
    fun lines(): List<String> = errors.map { "error: ${safe(it)}" } + notices.map { "notice: ${safe(it)}" }

    companion object {
        fun safe(text: String): String = buildString {
            for (c in text) {
                val type = Character.getType(c).toByte()
                if (c < ' ' || c.code in 0x7f..0x9f || type == Character.FORMAT || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
                    append("\\u%04x".format(c.code))
                } else {
                    append(c)
                }
            }
        }
    }
}

// 道具を続けられない誤り（読めないディレクトリ・上限を超えた入力など）
class ToolFailure(message: String) : Exception(message)

// レジストリのリポジトリの作業ツリー（PR の前後）。すべてのファイルを、データとしてだけ読む（実行しない・展開しない）。
// ルートの .git は読まない。シンボリックリンク・通常のファイルでないものは拒否する（リンクの先は読まない）
class RegistryTree(val files: Map<String, ByteArray>) {
    companion object {
        const val MAX_FILES = 50_000
        const val MAX_FILE_BYTES = 16 * 1024 * 1024 + 1
        const val MAX_TOTAL_BYTES = 512L * 1024 * 1024

        fun read(root: Path): RegistryTree {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw ToolFailure("$root is not a directory")
            val files = java.util.TreeMap<String, ByteArray>()
            var total = 0L
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
                        if (dir != root && dir.parent == root && dir.fileName.toString() == ".git") FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        val relative = root.relativize(file).joinToString("/")
                        if (!attrs.isRegularFile) throw ToolFailure("${Report.safe(relative)} is a symbolic link or not a regular file; the registry has only regular files")
                        if (attrs.size() > MAX_FILE_BYTES) throw ToolFailure("${Report.safe(relative)} is larger than $MAX_FILE_BYTES bytes")
                        if (files.size >= MAX_FILES) throw ToolFailure("the repository has more than $MAX_FILES files")
                        val bytes = Files.readAllBytes(file)
                        if (bytes.size > MAX_FILE_BYTES) throw ToolFailure("${Report.safe(relative)} changed while it was read")
                        total += bytes.size
                        if (total > MAX_TOTAL_BYTES) throw ToolFailure("the repository is larger than $MAX_TOTAL_BYTES bytes")
                        files[relative] = bytes
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = throw ToolFailure("cannot read ${root.relativize(file)}: ${exc.message}")
                },
            )
            return RegistryTree(files)
        }
    }
}

// 読み取って検査したリポジトリ。problems が空のときだけ、すべての値がそろう
class LoadedRegistry(
    val config: RegistryConfig?,
    val reserved: ReservedNames?,
    val maintainers: Maintainers?,
    val keys: TrustedKeysFile?,
    val entries: List<PackageEntry>,
    val signedIndex: ByteArray?,
    val signedSignature: ByteArray?,
    val problems: List<String>,
) {
    val ok: Boolean get() = problems.isEmpty()

    fun entry(name: String): PackageEntry? = entries.firstOrNull { it.name == name }

    companion object {
        const val CONFIG = RegistryFiles.CONFIG_FILE
        const val RESERVED = RegistryFiles.RESERVED_FILE
        const val MAINTAINERS = "maintainers.json"
        const val KEYS = "keys/trusted-keys.json"
        const val SIGNED_INDEX = "signed/index.json"
        const val SIGNED_SIGNATURE = "signed/index.json.sig"
        val PLACEHOLDERS = setOf("packages/.gitkeep", "signed/.gitkeep")
        private val PACKAGE_PATH = Regex("packages/[a-z]/[^/]+\\.json")

        fun isPackagePath(path: String) = path.startsWith("packages/") && path !in PLACEHOLDERS

        fun load(tree: RegistryTree): LoadedRegistry {
            val problems = ArrayList<String>()
            fun diagnostics(path: String, list: List<ProjectDiagnostic>) = list.forEach { problems += it.format(path) }
            fun required(path: String): ByteArray? = tree.files[path] ?: null.also { problems += "$path is missing" }
            val config = required(CONFIG)?.let { bytes -> RegistryFiles.parseConfig(bytes).also { diagnostics(CONFIG, it.diagnostics) }.value }
            val reserved = required(RESERVED)?.let { bytes -> RegistryFiles.parseReserved(bytes).also { diagnostics(RESERVED, it.diagnostics) }.value }
            val maintainers = required(MAINTAINERS)?.let { bytes -> Maintainers.parse(bytes).also { r -> r.problems.forEach { problems += "$MAINTAINERS: $it" } }.value }
            val keys = required(KEYS)?.let { bytes -> TrustedKeysFile.parse(bytes).also { r -> r.problems.forEach { problems += "$KEYS: $it" } }.value }
            if (config != null && keys != null) {
                for (key in keys.keys.keys) if (key.keyId !in config.signingKeyIds) problems += "$KEYS: the key ${key.keyId} is not listed in signingKeyIds of $CONFIG"
            }
            val entries = ArrayList<PackageEntry>()
            for ((path, bytes) in tree.files) {
                if (!isPackagePath(path)) continue
                if (!PACKAGE_PATH.matches(path)) {
                    problems += "${Report.safe(path)}: only packages/<first letter>/<name>.json files are allowed in packages/"
                    continue
                }
                val result = RegistryFiles.parseEntry(path, bytes)
                diagnostics(path, result.diagnostics)
                result.value?.let(entries::add)
            }
            return LoadedRegistry(config, reserved, maintainers, keys, entries, tree.files[SIGNED_INDEX], tree.files[SIGNED_SIGNATURE], problems)
        }
    }
}

// PR が変えたファイルの種類。1 つの PR は、1 つの種類だけを変える（パッケージの登録・更新に、ほかの変更を混ぜて審査を逃れることを防ぐ）
enum class ChangeKind {
    // packages/ のファイル（登録・更新・取り下げ・削除）
    PACKAGES,

    // signed/index.json・signed/index.json.sig（署名した index の公開）
    PUBLICATION,

    // それ以外（運営のファイル・文書・ワークフロー）。運営責任者の確認（CODEOWNERS）が要る
    MAINTENANCE,
}

class Changes(val paths: List<String>) {
    val kinds: Set<ChangeKind> = paths.map(::kind).toSet()

    val packagePaths: List<String> get() = paths.filter { kind(it) == ChangeKind.PACKAGES }

    companion object {
        fun kind(path: String): ChangeKind = when {
            LoadedRegistry.isPackagePath(path) -> ChangeKind.PACKAGES
            path == LoadedRegistry.SIGNED_INDEX || path == LoadedRegistry.SIGNED_SIGNATURE -> ChangeKind.PUBLICATION
            else -> ChangeKind.MAINTENANCE
        }

        fun between(base: RegistryTree, head: RegistryTree): Changes =
            Changes((base.files.keys + head.files.keys).sorted().filter { path -> !(base.files[path]?.contentEquals(head.files[path]) ?: false) })
    }
}
