package com.creatrbypc.createanalytical.install

import com.creatrbypc.createanalytical.project.Dependency
import com.creatrbypc.createanalytical.project.JsonObject
import com.creatrbypc.createanalytical.project.JsonParser
import com.creatrbypc.createanalytical.project.JsonValue
import com.creatrbypc.createanalytical.project.JsonWriter
import com.creatrbypc.createanalytical.project.LockRoot
import com.creatrbypc.createanalytical.project.LockedPackage
import com.creatrbypc.createanalytical.project.Lockfile
import com.creatrbypc.createanalytical.project.Lockfiles
import com.creatrbypc.createanalytical.project.Manifest
import com.creatrbypc.createanalytical.project.Manifests
import com.creatrbypc.createanalytical.project.PackageArchives
import com.creatrbypc.createanalytical.project.ProjectRules
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID

// install github:持ち主/リポジトリ@v1.2.0 の指定（@1.2.0 も可）。版は厳密な版だけ
class PackageSpec(val source: String, val version: String) {
    override fun toString() = "$source@v$version"

    companion object {
        fun parse(text: String): PackageSpec? {
            val at = text.lastIndexOf('@')
            if (at <= 0) return null
            val source = text.substring(0, at)
            val version = text.substring(at + 1).removePrefix("v")
            if (!ProjectRules.isValidSource(source) || !ProjectRules.isValidVersion(version)) return null
            return PackageSpec(source, version)
        }
    }
}

// 置いたパッケージ。installed は今回新しく置いたか（false は、前から正しく置かれていた）。directory はプロジェクトのルートからのパス
class InstalledPackage(
    val name: String,
    val version: String,
    val source: String,
    val sha256: String,
    val installed: Boolean,
    val directory: String,
    val requiredBy: String,
    val fetch: FetchSummary,
)

// インストールの結果。recovery は始めに行った復旧（無ければ null）、added は createscript.json に足した・変えた依存
class InstallResult(
    val recovery: String?,
    val packages: List<InstalledPackage>,
    val added: Dependency?,
    val manifestChanged: Boolean,
    val lockChanged: Boolean,
    val warnings: List<String>,
)

// 処理の区切り（テストで、中断・ほかのプロセスによる差し替えを起こすために使う）
internal fun interface InstallHooks {
    fun reached(step: String)

    companion object {
        val NONE = InstallHooks {}
    }
}

// パッケージのインストール（S8.2 Phase 3。docs/packages.md の「Installing」）。手順:
// 1. .createscript/install.lock を tryLock で取る（同時に 1 つだけ）。中断したインストールのジャーナルがあれば、先に復旧する
// 2. createscript.json・createscript.lock を読み（シンボリックリンク・通常のファイルでないものは使わない）、依存を解決して、すべてのアーカイブを取得・検査する
//    （ここまでプロジェクトのファイルは変えない。通信の失敗・検査の失敗では何も変わらない）
// 3. 変えるものが無ければ終わり（同じ指定での再実行は何も変えない）
// 4. .createscript/txn-<id>/ に、新しいマニフェスト・ロックファイル、今のものの写し、展開したパッケージを用意してディスクに書き出し、
//    ジャーナル .createscript/transaction.json（状態 prepared）を、置き換えない操作で作る
// 5. パッケージを置き換えない移動で packages/<name>/<version>/ に置き、マニフェスト・ロックファイルを、今のものが読んだときと同じ（SHA-256・fileKey）
//    ことを確かめてから、置き換える移動で差し替える。ジャーナルを committed にして、作業用のファイルとジャーナルを消す
// 途中の失敗では、ジャーナルを使って取り消す。プロセスが止まった（クラッシュ）ときは、次の install が続きを行うか取り消す（Recovery）。
// ダウンロードしたものは実行しない。展開するのは検査に通ったソースと文書だけ
class Installer internal constructor(
    private val root: Path,
    private val fetcher: PackageFetcher,
    private val limits: InstallLimits,
    private val hooks: InstallHooks,
    private val keys: DirectoryKeys = DirectoryKeys.FILE_KEY,
    private val fileSystem: InstallFileSystem = InstallFileSystem.DEFAULT,
) {
    constructor(root: Path, fetcher: PackageFetcher) : this(root, fetcher, InstallLimits(), InstallHooks.NONE)

    private fun unsafe(message: String): Nothing = throw InstallFailure(InstallFailure.Kind.UNSAFE, message)

    private fun invalid(message: String): Nothing = throw InstallFailure(InstallFailure.Kind.INVALID, message)

    fun install(spec: PackageSpec?, offline: Boolean): InstallResult {
        val realRoot = try {
            root.toRealPath()
        } catch (error: IOException) {
            throw InstallFailure(InstallFailure.Kind.NO_PROJECT, "not a directory: $root")
        }
        if (SafeIo.attributes(realRoot)?.isDirectory != true) throw InstallFailure(InstallFailure.Kind.NO_PROJECT, "not a directory: $root")
        val layout = Layout(realRoot)
        if (!SafeIo.exists(layout.manifest)) throw InstallFailure(InstallFailure.Kind.NO_PROJECT, "${Manifests.FILE_NAME} not found in $root (create one with createscript init)")
        SafeIo.directory(layout.state, create = true)
        return withInstallLock(layout) {
            val owner = Owner.probe(layout.state)
            if (!owner.owns(layout.state)) unsafe("${layout.state} belongs to another user")
            // 新しいファイルの隣に ._ のファイルができるファイルシステム（macOS の FAT・exFAT など）では、置いたパッケージの中身が印と一致しなくなる。
            // 検査を弱めずに、何も変える前に止まる（S8.2 L-6）
            if (fileSystem.addsSidecarFiles(layout.state)) {
                unsafe(
                    "the file system of ${layout.state} adds a ._ file next to every new file (macOS does this on FAT and exFAT drives and on some network drives). " +
                        "createscript install checks that package directories hold exactly the installed files, so it cannot install packages there. Nothing was changed. " +
                        "Move the project to a drive formatted as APFS or Mac OS Extended",
                )
            }
            run(layout, owner, spec, offline)
        }
    }

    private fun <T> withInstallLock(layout: Layout, body: () -> T): T {
        val attributes = SafeIo.attributes(layout.installLock)
        if (attributes != null && (attributes.isSymbolicLink || !attributes.isRegularFile)) unsafe("${layout.installLock} is not a regular file")
        val channel = try {
            FileChannel.open(layout.installLock, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        } catch (error: IOException) {
            unsafe("cannot open ${layout.installLock}")
        }
        channel.use {
            val lock = try {
                channel.tryLock()
            } catch (error: OverlappingFileLockException) {
                null
            } catch (error: IOException) {
                unsafe("cannot lock ${layout.installLock}")
            } ?: throw InstallFailure(
                InstallFailure.Kind.BUSY,
                "another createscript install is running in this project (${InstallLayout.DIRECTORY}/${InstallLayout.INSTALL_LOCK} is locked); try again when it has finished",
            )
            try {
                return body()
            } finally {
                try {
                    lock.release()
                } catch (error: IOException) {
                    // チャネルを閉じればロックも外れる
                }
            }
        }
    }

    private fun run(layout: Layout, owner: Owner, spec: PackageSpec?, offline: Boolean): InstallResult {
        var recovery: String? = null
        if (SafeIo.exists(layout.journal)) {
            val outcome = Recovery(layout, owner, limits, hooks, keys).recover(preferForward = true)
            recovery = outcome.message
            if (outcome.kept.isNotEmpty()) {
                unsafe("${outcome.message}, but some files were changed by another program and were left as they are: ${outcome.kept.joinToString("; ")}. Check them and run createscript install again")
            }
        }
        // 復旧した後の失敗でも、復旧したこと（createscript.json・createscript.lock が変わったかもしれないこと）を知らせる（S8.2 L-2）
        if (recovery == null) return install(layout, owner, spec, offline, null)
        try {
            return install(layout, owner, spec, offline, recovery)
        } catch (failure: InstallFailure) {
            throw InstallFailure(failure.kind, "first $recovery (check ${Manifests.FILE_NAME} and ${Lockfiles.FILE_NAME}); then this installation failed: ${failure.message}")
        }
    }

    private fun install(layout: Layout, owner: Owner, spec: PackageSpec?, offline: Boolean, recovery: String?): InstallResult {
        val warnings = ArrayList<String>()
        // ジャーナルから参照されない作業用のディレクトリ（S8.2 L-3）。記録（journal.tmp・印）と完全に一致するものだけを消し、ほかは残して知らせる
        warnings += Leftovers(layout, owner, limits).clean()
        hooks.reached("start")

        // プロジェクトのファイル（書き換えるので、シンボリックリンクは使わない）
        val manifestFile = readProjectFile(layout.manifest, Manifests.MAX_BYTES) ?: throw InstallFailure(InstallFailure.Kind.NO_PROJECT, "${Manifests.FILE_NAME} not found")
        val parsedManifest = Manifests.parse(manifestFile.bytes)
        val manifest = parsedManifest.manifest ?: invalid(
            "${Manifests.FILE_NAME} is not valid (run createscript manifest check):\n" + parsedManifest.diagnostics.joinToString("\n") { "  " + it.format(Manifests.FILE_NAME) },
        )
        val lockFile = readProjectFile(layout.lockfile, Lockfiles.MAX_BYTES)
        val lock = lockFile?.let { file ->
            val parsed = Lockfiles.parse(file.bytes)
            parsed.lockfile ?: invalid(
                "${Lockfiles.FILE_NAME} is not valid (run createscript lock check); it was not changed:\n" + parsed.diagnostics.joinToString("\n") { "  " + it.format(Lockfiles.FILE_NAME) },
            )
        }

        // 依存の解決と取得（プロジェクトのファイルは変えない）
        val resolver = Resolver(fetcher, limits, offline)
        var dependencies = manifest.dependencies
        var added: Dependency? = null
        var manifestNew = manifestFile.bytes
        if (spec != null) {
            val lockedEntry = lock?.packages?.firstOrNull { it.source == spec.source && it.version == spec.version }
            val (_, archive) = resolver.fetch(spec.source, spec.version, lockedEntry?.sha256, null)
            val name = archive.manifest.name
            if (name == manifest.name) invalid("${spec.source} ${spec.version} is the package $name, which has the name of this project")
            val existing = manifest.dependencies.firstOrNull { it.name == name }
            if (existing != null && existing.source != spec.source) {
                invalid("${Manifests.FILE_NAME} already has the dependency $name from ${existing.source}; it was not replaced by ${spec.source}. Change it by hand if you want to")
            }
            manifest.dependencies.firstOrNull { it.source == spec.source && it.name != name }?.let {
                invalid("${Manifests.FILE_NAME} already uses ${spec.source} as the dependency ${it.name}, but the package is named $name")
            }
            val dependency = Dependency(name, spec.version, spec.source)
            dependencies = (manifest.dependencies.filter { it.name != name } + dependency).sortedBy { it.name }
            if (existing != dependency) {
                added = dependency
                manifestNew = ManifestEditor.withDependency(manifestFile.bytes, manifest, dependency)
            }
        }
        val packages = resolver.resolve(manifest.name, dependencies, lock)
        hooks.reached("resolved")
        val lockNew = if (lockFile == null && packages.isEmpty()) {
            null
        } else {
            Lockfiles.write(Lockfile.of(LockRoot(manifest.name, manifest.version), packages.map { LockedPackage(it.name, it.version, it.source, it.sha256, it.dependencies) }))
                .toByteArray(Charsets.UTF_8)
        }

        // 置き場所の状態（既にあるものは、印と中身がこのパッケージと完全に同じで、中身から作り直したアーカイブが取得したものと同じときだけ使う。
        // 違えば触らずに止まる）
        if (packages.isNotEmpty()) SafeIo.directory(layout.packages, create = false)
        val toCreate = ArrayList<ResolvedPackage>()
        for (p in packages) {
            SafeIo.directory(layout.packages.resolve(p.name), create = false)
            val directory = layout.packageDirectory(p.name, p.version)
            when (val state = PackageDirectories.inspect(directory, owner, keepContents = true)) {
                PackageState.Absent -> toCreate.add(p)
                is PackageState.Valid -> {
                    val m = state.marker
                    if (m.name != p.name || m.version != p.version || m.source != p.source || m.sha256 != p.sha256) {
                        unsafe("${relative(layout, directory)} holds another package (${m.source} ${m.version}, SHA-256 ${m.sha256}); nothing was changed. Remove it by hand and run createscript install again")
                    }
                    if (!PackageDirectories.matchesArchive(state, p.sha256)) {
                        unsafe("${relative(layout, directory)} does not match the archive of ${p.name} ${p.version} (SHA-256 ${p.sha256}); its files were changed after the installation. Nothing was changed. Remove it by hand and run createscript install again")
                    }
                }
                is PackageState.Invalid -> unsafe("${relative(layout, directory)} exists but cannot be used (${state.reason}); nothing was changed. Remove it by hand and run createscript install again")
            }
        }
        val manifestChanged = !manifestNew.contentEquals(manifestFile.bytes)
        val lockChanged = lockNew != null && (lockFile == null || !lockNew.contentEquals(lockFile.bytes))
        if (manifestChanged || lockChanged || toCreate.isNotEmpty()) {
            transact(layout, owner, manifestFile, manifestNew, lockFile, lockNew, toCreate).forEach { warnings += "not removed after the installation: $it" }
        }
        packages.flatMap { it.fetch.warnings }.forEach { warnings += it }
        val reports = packages.map { p ->
            InstalledPackage(p.name, p.version, p.source, p.sha256, p in toCreate, relative(layout, layout.packageDirectory(p.name, p.version)), p.requiredBy, p.fetch)
        }
        return InstallResult(recovery, reports, added, manifestChanged, lockChanged, warnings)
    }

    private fun readProjectFile(path: Path, maxBytes: Int): ReadFile? = try {
        SafeIo.readRegular(path, maxBytes)
    } catch (failure: InstallFailure) {
        unsafe("${failure.message}; createscript install does not replace symbolic links or special files. Nothing was changed")
    }

    private fun relative(layout: Layout, path: Path): String = layout.root.relativize(path).toString()

    // .createscript と作業用のディレクトリが、プロジェクトの中の実際のディレクトリで、ジャーナルに記録した fileKey のままか（S8.2 L-5）。違えば理由
    private fun placementProblem(layout: Layout, txn: Path, binding: JournalBinding): String? {
        (SafeIo.chain(layout.root, txn) as? SafeIo.Chain.NotReal)?.let { return it.reason }
        if (SafeIo.chain(layout.root, txn) == SafeIo.Chain.Missing) return "${relative(layout, txn)} disappeared"
        if (binding.directory != null && keys.of(layout.state) != binding.directory) return "${InstallLayout.DIRECTORY} was replaced by another directory"
        if (binding.transaction != null && keys.of(txn) != binding.transaction) return "${relative(layout, txn)} was replaced by another directory"
        return null
    }

    // ---- トランザクション ----

    // 残したもの（作業用のファイルを消せなかったもの）を返す
    private fun transact(layout: Layout, owner: Owner, manifestOld: ReadFile, manifestNew: ByteArray, lockOld: ReadFile?, lockNew: ByteArray?, create: List<ResolvedPackage>): List<String> {
        val id = UUID.randomUUID().toString()
        val txn = layout.transaction(id)
        try {
            java.nio.file.Files.createDirectory(txn)
        } catch (error: IOException) {
            unsafe("cannot create ${relative(layout, txn)}; nothing was changed")
        }
        // ジャーナルを書いた場所（S8.2 L-2）。fileKey が取れなくても、インストールはそのまま行う（中断したときの復旧が、手で確かめる手順になるだけ）
        val binding = JournalBinding(layout.root.toString(), keys.of(layout.state), keys.of(txn))
        // 書いたジャーナル（その場の取り消しでは、ディスクのジャーナルがこれと同じことを確かめて使う。書いた場所の確認は要らない）
        var journalWritten: Journal? = null
        // ジャーナルを公開できたか分からなくなったとき（作業用のディレクトリを消さない）
        var uncertain = false
        try {
            // ジャーナルの中身（印は、書く前にメモリーの中で作る）を、作業用のディレクトリの最初のファイル journal.tmp として書く（S8.2 L-3）。
            // 途中で止まった作業用のディレクトリは、journal.tmp と印に記録したものと完全に一致するときだけ、次の install が消す（Leftovers）
            val markers = create.associate { it.name to PackageDirectories.marker(id, it) }
            val staged = create.map { JournalPackage(it.name, it.version, SafeIo.sha256Hex(markers.getValue(it.name))) }
            val journal = Journal(id, Journal.State.PREPARED, manifestOld.identity.sha256, SafeIo.sha256Hex(manifestNew), lockOld?.identity?.sha256, lockNew?.let(SafeIo::sha256Hex), staged, binding)
            val temporary = txn.resolve(Recovery.JOURNAL_TEMP)
            SafeIo.createFile(temporary, journal.write())
            hooks.reached("recorded")
            SafeIo.createFile(txn.resolve(Recovery.MANIFEST_OLD), manifestOld.bytes)
            SafeIo.createFile(txn.resolve(Recovery.MANIFEST_NEW), manifestNew)
            lockOld?.let { SafeIo.createFile(txn.resolve(Recovery.LOCK_OLD), it.bytes) }
            lockNew?.let { SafeIo.createFile(txn.resolve(Recovery.LOCK_NEW), it) }
            for (p in create) PackageDirectories.stage(txn.resolve(Recovery.STAGING), p, markers.getValue(p.name)) { hooks.reached("staging:${p.name}") }
            SafeIo.syncDirectory(txn)
            hooks.reached("staged")
            // 書き込んだ .createscript と作業用のディレクトリが、今も同じ実際のディレクトリであること（S8.2 L-5: 途中でリンクに差し替えられたなら、
            // 書いたものがプロジェクトの外へ行ったかもしれない。公開せずに止まり、どこも消さない）
            placementProblem(layout, txn, binding)?.let {
                uncertain = true
                unsafe("$it; another program changed ${InstallLayout.DIRECTORY} during the installation, so the prepared files may have been written elsewhere. Nothing in the project was changed, and nothing was removed")
            }
            // 書き終えた journal.tmp に transaction.json の名前を付けて公開する（S8.2 L-6: ハードリンク、作れなければ原子的な移動。写さない）
            try {
                SafeIo.publishNew(temporary, layout.journal, fileSystem)
            } catch (error: FileAlreadyExistsException) {
                unsafe("${relative(layout, layout.journal)} appeared during the installation; nothing was changed")
            } catch (error: java.nio.file.AtomicMoveNotSupportedException) {
                unsafe("the file system of ${layout.state} supports neither hard links nor atomic renames, so createscript install cannot write its journal safely; nothing was changed")
            } catch (error: IOException) {
                if (SafeIo.exists(layout.journal)) {
                    uncertain = true
                    unsafe(
                        "cannot tell whether ${relative(layout, layout.journal)} was written completely (${error.message ?: error.javaClass.simpleName}); " +
                            "nothing else was changed. Run createscript install again: it finishes or undoes the installation, or stops with steps to check it by hand",
                    )
                }
                throw error
            }
            journalWritten = journal
            // 公開した後も、.createscript が同じ実際のディレクトリで、ジャーナルがそこにあること（違えば続けない。取り消しは、書いたジャーナルで行う）
            placementProblem(layout, txn, binding)?.let { unsafe("$it; another program changed ${InstallLayout.DIRECTORY} while the journal was written") }
            if (!SafeIo.exists(layout.journal)) unsafe("${relative(layout, layout.journal)} disappeared while it was written")
            SafeIo.syncDirectory(layout.state)
            java.nio.file.Files.deleteIfExists(temporary)
            hooks.reached("journal")
            return Recovery(layout, owner, limits, hooks, keys).forward(journal, manifestOld.identity, lockOld?.identity)
        } catch (error: Exception) {
            val reason = (error as? InstallFailure)?.message ?: (error.message ?: error.javaClass.simpleName)
            val written = journalWritten
            if (written == null) {
                // 作業用のディレクトリが、今もプロジェクトの中の実際のディレクトリであるときだけ消す（リンクの先は消さない。S8.2 L-5）
                if (!uncertain && SafeIo.chain(layout.root, txn) == SafeIo.Chain.Real) SafeIo.deleteOwnTree(txn)
                throw (error as? InstallFailure) ?: InstallFailure(InstallFailure.Kind.UNSAFE, "cannot prepare the installation: $reason; nothing was changed")
            }
            val undo = try {
                Recovery(layout, owner, limits, hooks, keys).recover(preferForward = false, trusted = written)
            } catch (failure: InstallFailure) {
                unsafe("$reason. The installation could not be undone automatically (${failure.message}); ${InstallLayout.DIRECTORY}/${InstallLayout.JOURNAL} was kept")
            }
            val kept = if (undo.kept.isEmpty()) "" else " (left as they are: ${undo.kept.joinToString("; ")})"
            throw InstallFailure((error as? InstallFailure)?.kind ?: InstallFailure.Kind.UNSAFE, "$reason; ${undo.message}$kept")
        }
    }
}

// マニフェストの書き換え（S8.2 の設計の 3）: JSON の木として読み、dependencies の 1 つの項目だけを足すか置き換える。
// ほかの項目は値も順序もそのまま残す（失われるのは空白などの書式だけ）。書いたものを読み直して、意図した内容と同じことを確かめる
internal object ManifestEditor {
    fun withDependency(bytes: ByteArray, manifest: Manifest, dependency: Dependency): ByteArray {
        val root = JsonParser.parse(bytes, Manifests.MAX_BYTES).value as? JsonObject ?: error("the manifest was checked")
        val members = LinkedHashMap<String, JsonValue>(root.members)
        val current = members["dependencies"] as? JsonObject
        val entries = LinkedHashMap<String, JsonValue>(current?.members ?: emptyMap())
        entries[dependency.name] = JsonWriter.obj("version" to JsonWriter.string(dependency.version), "source" to JsonWriter.string(dependency.source))
        members["dependencies"] = JsonObject(entries, emptyMap(), 0, 0)
        val written = JsonWriter.write(JsonObject(members, emptyMap(), 0, 0)).toByteArray(Charsets.UTF_8)
        val expected = manifest.copy(dependencies = (manifest.dependencies.filter { it.name != dependency.name } + dependency).sortedBy { it.name })
        val reread = Manifests.parse(written).manifest
        if (reread != expected) throw InstallFailure(InstallFailure.Kind.INVALID, "cannot add the dependency ${dependency.name} to ${Manifests.FILE_NAME}")
        return written
    }
}

// パッケージのディレクトリの状態
internal sealed interface PackageState {
    data object Absent : PackageState

    // 印と中身が完全に一致する。files はパス（印を含む）の同一性、contents は確かめたときに読んだ中身（keepContents のときだけ。印を除く）
    class Valid(val marker: Marker, val markerSha256: String, val files: Map<String, Identity>, val contents: Map<String, ByteArray> = emptyMap()) : PackageState

    class Invalid(val reason: String) : PackageState
}

internal object PackageDirectories {
    private const val MODULES = "modules"

    // ディレクトリが、印（.createscript-package.json）と完全に一致するか。リンクでないディレクトリ・通常のファイルだけで、印に無いもの・足りないもの・
    // SHA-256 の違うものがあれば Invalid。ディレクトリと印は、このユーザーのものであること
    // keepContents なら、確かめたバイト列を返す（S8.2 Phase 4: 実行では、確かめたものと同じバイト列をコンパイルする。確かめた後の差し替えを使わない）
    fun inspect(directory: Path, owner: Owner, keepContents: Boolean = false): PackageState {
        val attributes = SafeIo.attributes(directory) ?: return PackageState.Absent
        if (attributes.isSymbolicLink) return PackageState.Invalid("it is a symbolic link")
        if (!attributes.isDirectory) return PackageState.Invalid("it is not a directory")
        if (!owner.owns(directory)) return PackageState.Invalid("it belongs to another user")
        val markerPath = directory.resolve(InstallLayout.MARKER)
        val markerFile = try {
            SafeIo.readRegular(markerPath, Marker.MAX_BYTES)
        } catch (failure: InstallFailure) {
            return PackageState.Invalid("its ${InstallLayout.MARKER} cannot be used")
        } ?: return PackageState.Invalid("it has no ${InstallLayout.MARKER}")
        if (!owner.owns(markerPath)) return PackageState.Invalid("its ${InstallLayout.MARKER} belongs to another user")
        val marker = Marker.parse(markerFile.bytes) ?: return PackageState.Invalid("its ${InstallLayout.MARKER} is damaged")
        val files = HashMap<String, Identity>()
        val contents = HashMap<String, ByteArray>()
        files[InstallLayout.MARKER] = markerFile.identity
        var total = 0L
        fun check(path: Path, relative: String): String? {
            val expected = marker.files[relative] ?: return "it has a file that was not installed: $relative" + sidecarHint(relative)
            val file = try {
                SafeIo.readRegular(path, PackageArchives.MAX_TOTAL_BYTES)
            } catch (failure: InstallFailure) {
                return "$relative is not a regular file"
            } ?: return "$relative is missing"
            if (file.identity.sha256 != expected) return "$relative was changed"
            total += file.identity.size
            if (total > PackageArchives.MAX_TOTAL_BYTES) return "it is too large"
            files[relative] = file.identity
            if (keepContents) contents[relative] = file.bytes
            return null
        }
        val hasModules = marker.files.keys.any { it.startsWith("$MODULES/") }
        for (name in SafeIo.list(directory, PackageArchives.MAX_ENTRIES + 2)) {
            if (name == InstallLayout.MARKER) continue
            val path = directory.resolve(name)
            if (name == MODULES && hasModules) {
                val modules = SafeIo.attributes(path)
                if (modules == null || modules.isSymbolicLink || !modules.isDirectory) return PackageState.Invalid("$MODULES is not a directory")
                for (inner in SafeIo.list(path, PackageArchives.MAX_ENTRIES + 1)) check(path.resolve(inner), "$MODULES/$inner")?.let { return PackageState.Invalid(it) }
                continue
            }
            check(path, name)?.let { return PackageState.Invalid(it) }
        }
        if (files.keys - InstallLayout.MARKER != marker.files.keys) return PackageState.Invalid("some installed files are missing")
        return PackageState.Valid(marker, markerFile.identity.sha256, files, contents)
    }

    // ._ で始まる名前は、macOS が FAT・exFAT などで作る AppleDouble のファイル（S8.2 L-6。検査は弱めず、理由だけを示す）
    fun sidecarHint(relative: String): String =
        if (relative.substringAfterLast('/').startsWith("._")) " (macOS makes such ._ files on FAT and exFAT drives and some network drives, where packages cannot be installed)" else ""

    // 確かめたディレクトリ（keepContents で読んだもの）のファイル（印を除く）から正規形のアーカイブを作り直すと、SHA-256 が sha256 になるか（S8.2 M-1）。
    // インストールは正規形のアーカイブだけを受け入れ、その中身をそのまま置くので、変わっていなければ必ず同じになる。印は使わない（印と中身を同じように書き換えても見つかる）
    fun matchesArchive(state: PackageState.Valid, sha256: String): Boolean {
        val rebuilt = PackageArchives.rebuild(state.contents) ?: return false
        return SafeIo.sha256Hex(rebuilt) == sha256
    }

    // パッケージの印（.createscript-package.json）のバイト列。ファイルの SHA-256 は、展開するバイト列から計算する（書く前にジャーナルに記録するため）
    fun marker(transaction: String, p: ResolvedPackage): ByteArray {
        val hashes = java.util.TreeMap<String, String>()
        for ((path, bytes) in p.files) hashes[path] = SafeIo.sha256Hex(bytes)
        return Marker(transaction, p.name, p.version, p.source, p.sha256, hashes).write()
    }

    // 作業用のディレクトリ staging/<name>/<version>/ に、印を最初に書いてから展開する（S8.2 L-3: 途中で止まっても、置いたファイルを印と照合できる）。
    // afterMarker は、印を書いた後（テストの中断に使う）
    fun stage(staging: Path, p: ResolvedPackage, marker: ByteArray, afterMarker: () -> Unit = {}) {
        SafeIo.directory(staging, create = true)
        val parent = staging.resolve(p.name)
        SafeIo.directory(parent, create = true)
        val directory = parent.resolve(p.version)
        java.nio.file.Files.createDirectory(directory)
        SafeIo.createFile(directory.resolve(InstallLayout.MARKER), marker)
        afterMarker()
        for ((path, bytes) in p.files) {
            val target = if (path.startsWith("$MODULES/")) {
                SafeIo.directory(directory.resolve(MODULES), create = true)
                directory.resolve(MODULES).resolve(path.removePrefix("$MODULES/"))
            } else {
                directory.resolve(path)
            }
            SafeIo.createFile(target, bytes)
        }
        SafeIo.syncDirectory(directory)
    }

    // 確かめたディレクトリを消す（印に書かれたファイルを、同一性を確かめてから消す。印は最後）。消さなかったものを返す
    fun delete(directory: Path, state: PackageState.Valid): List<String> {
        val kept = ArrayList<String>()
        for ((relative, identity) in state.files.entries.sortedBy { it.key == InstallLayout.MARKER }) {
            val path = if (relative.startsWith("$MODULES/")) directory.resolve(MODULES).resolve(relative.removePrefix("$MODULES/")) else directory.resolve(relative)
            SafeIo.deleteFileIfSame(path, identity.sha256, PackageArchives.MAX_TOTAL_BYTES, identity.fileKey)?.let { kept += "$path ($it)" }
        }
        if (state.files.keys.any { it.startsWith("$MODULES/") }) SafeIo.deleteEmptyDirectory(directory.resolve(MODULES))?.let { kept += "${directory.resolve(MODULES)} ($it)" }
        SafeIo.deleteEmptyDirectory(directory)?.let { kept += "$directory ($it)" }
        return kept
    }
}
