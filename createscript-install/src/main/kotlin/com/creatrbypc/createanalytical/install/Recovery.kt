package com.creatrbypc.createanalytical.install

import com.creatrbypc.createanalytical.project.Lockfile
import com.creatrbypc.createanalytical.project.Lockfiles
import com.creatrbypc.createanalytical.project.Manifest
import com.creatrbypc.createanalytical.project.Manifests
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path

// 復旧の結果。kept は、ほかのプログラムが変えたなどの理由で、触らずに残したもの
internal class RecoveryOutcome(val message: String, val kept: List<String>)

// ジャーナルによる続行・取り消し（S8.2 Phase 3。承認された設計の「安全なときだけ自動で復旧する」）。
// ジャーナルは信用しない: 形・値を厳密に確かめ（Journal.parse）、ジャーナル・作業用のディレクトリ・印がこのユーザーのものであることを確かめ、
// 今のファイルの SHA-256 がジャーナルの old・new のどちらかであることを確かめてからでなければ書き換えない。
// - 続行（forward）: 新しいマニフェスト・ロックファイルの写しと、置く前のパッケージ（または置き終えたパッケージ）がそろっているとき
// - 取り消し（backward）: 今回書いたもの（SHA-256 が new のマニフェスト・ロックファイル、このトランザクションの印があり中身が一致するパッケージ）だけを戻す・消す。
//   ほかのプログラムが変えたファイル（old でも new でもない）は、上書きも削除もせずに残す
// - どちらも安全にできなければ、何も変えずに止まり、手で確かめる手順を示す
// S8.2 L-2（中断した後の install が、持ち込まれた・書き換えられたジャーナルで、利用者が確かめたファイルを置き換えないための確認）:
// - ジャーナルを書いた場所（binding: ルートの実パスと、.createscript・txn-<id> の fileKey）が今と同じでなければ、自動では復旧しない。
//   fileKey が取れないファイルシステムでも、自動では復旧しない（同じプロセスの中の取り消しは、書いたジャーナルを覚えているので確認は要らない）
// - binding は認証ではないので、それだけでは信用しない。書き込む写しの内容も、必ず確かめる（checkForward・checkBackward）:
//   マニフェストは、前のものと同じか、前のものに依存を 1 つ足した・変えたもの（install が書くもの）だけ。新しいロックファイルは lock check に通り、
//   前のロックファイルと同じ name・version・source の項目は同じ SHA-256・依存。置くパッケージは、作り直した正規形のアーカイブがロックファイルの SHA-256 と同じ。
//   前の版の写しは、正しい形のマニフェスト・ロックファイル
// - 限界: 同じユーザーとして、このディレクトリのファイルを書き換えられるプログラムは、binding を合わせ、依存を変えたように見える写しを用意できる（docs/packages.md）
internal class Recovery(
    private val layout: Layout,
    private val owner: Owner,
    private val limits: InstallLimits,
    private val hooks: InstallHooks,
    private val keys: DirectoryKeys = DirectoryKeys.FILE_KEY,
) {
    companion object {
        const val MANIFEST_OLD = "createscript.json.old"
        const val MANIFEST_NEW = "createscript.json.new"
        const val LOCK_OLD = "createscript.lock.old"
        const val LOCK_NEW = "createscript.lock.new"
        const val JOURNAL_TEMP = "journal.tmp"
        const val STAGING = "staging"

        // 置く途中のパッケージのディレクトリ（S8.3 L-5。packages/<name>/.placing-<トランザクションの id>。"." で始まるので、版として扱われない）
        const val PLACING_PREFIX = ".placing-"
        private const val MODULES = "modules"
    }

    private fun unsafe(message: String): Nothing = throw InstallFailure(InstallFailure.Kind.UNSAFE, message)

    private fun shown(path: Path): String = layout.root.relativize(path).toString()

    // 読めたジャーナル（復旧できないときの説明に、残っているファイルと今の状態を示すために使う）
    private var loaded: Journal? = null

    // 読めたジャーナルを、書いた場所の確認に通ったか（通らなければ、説明の「前・後の版」はジャーナルに書かれていることにすぎない）
    private var confirmed = false

    // 復旧できないときの説明（S8.2 Phase 4 の条件: ジャーナルの無条件の削除を促さず、理由・残っているファイル・手で確かめる手順を示す）。何も変えない
    // firstStep は、理由に合わせて最初に示す手順（無ければ null）
    private fun manual(reason: String, firstStep: String? = null): Nothing {
        val lines = ArrayList<String>()
        lines += "an earlier createscript install was interrupted, and it cannot be finished or undone safely: $reason. Nothing was changed."
        lines += "Files left by the interrupted installation:"
        lines += "  ${InstallLayout.DIRECTORY}/${InstallLayout.JOURNAL}"
        val transactions = try {
            SafeIo.list(layout.state, 1024).filter { it.startsWith(InstallLayout.TRANSACTION_PREFIX) }
        } catch (failure: InstallFailure) {
            emptyList()
        }
        for (name in transactions) {
            val contents = try {
                SafeIo.list(layout.state.resolve(name), 64).joinToString(", ")
            } catch (failure: InstallFailure) {
                "cannot be listed"
            }
            lines += "  ${InstallLayout.DIRECTORY}/$name/ ($contents)"
        }
        val journal = loaded
        if (journal != null) {
            fun state(path: Path, old: String?, new: String?, maxBytes: Int): String {
                val sha = try {
                    SafeIo.readRegular(path, maxBytes)?.identity?.sha256
                } catch (failure: InstallFailure) {
                    return "cannot be read"
                }
                return when {
                    sha == null && old == null -> "missing, as before the installation"
                    sha == null -> "missing"
                    sha == new && sha == old -> "not changed by the installation"
                    sha == new -> "the version after the installation"
                    sha == old -> "the version before the installation"
                    else -> "changed by another program (neither before nor after the installation)"
                }
            }
            lines += if (confirmed) "Current files:" else "Current files (compared with the journal, which could not be confirmed):"
            lines += "  ${Manifests.FILE_NAME}: ${state(layout.manifest, journal.manifestOld, journal.manifestNew, Manifests.MAX_BYTES)}"
            lines += "  ${Lockfiles.FILE_NAME}: ${state(layout.lockfile, journal.lockOld, journal.lockNew, Lockfiles.MAX_BYTES)}"
        }
        val txn = journal?.let { "${InstallLayout.DIRECTORY}/${InstallLayout.TRANSACTION_PREFIX}${it.id}/" } ?: "the ${InstallLayout.DIRECTORY}/${InstallLayout.TRANSACTION_PREFIX}* directory"
        lines += "To check by hand:"
        val steps = ArrayList<String>()
        firstStep?.let { steps += it }
        // 作業用のディレクトリ（写し）が無いとき（S8.2 N-2）
        val missing = journal != null && !SafeIo.exists(layout.transaction(journal.id))
        steps += if (missing) {
            "The saved copies ($txn) are missing. If you moved that directory, put it back and run createscript install again. " +
                "Otherwise check ${Manifests.FILE_NAME} and ${Lockfiles.FILE_NAME} yourself (createscript manifest check, createscript lock check), and keep the version you want."
        } else {
            "Compare ${Manifests.FILE_NAME} and ${Lockfiles.FILE_NAME} with the saved copies in $txn (*.old is the version before the installation, *.new the version after it), and keep the version you want."
        }
        steps += "In ${InstallLayout.DIRECTORY}/${InstallLayout.PACKAGES}, directories whose ${InstallLayout.MARKER} names this transaction" +
            (journal?.let { " (${it.id})" } ?: "") + " were added by the interrupted installation; they are used only if createscript.lock lists them."
        steps += "Only when both files are as you want them, move ${InstallLayout.DIRECTORY}/${InstallLayout.JOURNAL}" + (if (missing) "" else " and $txn") +
            " out of the project (keep them until you are sure), and run createscript install again; it checks every package again."
        steps.forEachIndexed { index, step -> lines += "  ${index + 1}. $step" }
        unsafe(lines.joinToString("\n"))
    }

    // ジャーナルを読む（リンク・通常のファイルでない・ほかのユーザーのもの・壊れたものは使わない）。trusted は、このプロセスが書いたジャーナル（ディスクのものが同じであること）
    private fun load(trusted: Journal?): Journal {
        val attributes = SafeIo.attributes(layout.journal) ?: manual("the journal disappeared")
        if (attributes.isSymbolicLink || !attributes.isRegularFile) manual("${shown(layout.journal)} is not a regular file")
        if (!owner.owns(layout.journal)) manual("${shown(layout.journal)} belongs to another user")
        val file = try {
            SafeIo.readRegular(layout.journal, Journal.MAX_BYTES)
        } catch (failure: InstallFailure) {
            manual(failure.message ?: "cannot read the journal")
        } ?: manual("the journal disappeared")
        val (journal, problem) = Journal.parse(file.bytes, limits.maxPackages)
        if (journal == null) manual("${shown(layout.journal)} is damaged ($problem)")
        loaded = journal
        if (trusted != null) {
            if (journal != trusted && journal != trusted.committed()) manual("${shown(layout.journal)} was changed by another program during the installation")
            confirmed = true
            return journal
        }
        checkBinding(journal)
        confirmed = true
        return journal
    }

    // ジャーナルを書いた場所が、今の場所と同じか（S8.2 L-2）。違えば・確かめられなければ、何も変えずに止まる
    private fun checkBinding(journal: Journal) {
        val binding = journal.binding
        val moved = "it may have come with a project that was copied, downloaded, moved or restored, so its saved copies are not used automatically"
        if (binding.root != layout.root.toString()) manual("${shown(layout.journal)} was written for another directory (${binding.root}); $moved")
        val txn = layout.transaction(journal.id)
        // 済んだ（committed）インストールの片付けの途中なら、作業用のディレクトリはもう無いことがある（片付けは、プロジェクトのファイルを書き換えない）。
        // 済んでいない（prepared）のに無ければ、続行にも取り消しにも要る写しが無い（S8.2 N-2: fileKey の問題とは区別して知らせる）
        val checkTransaction = SafeIo.exists(txn)
        if (journal.state == Journal.State.PREPARED && !checkTransaction) {
            manual("${shown(layout.journal)} refers to ${shown(txn)}, which does not exist (it was removed or moved), so the saved copies needed to finish or undo the installation are missing")
        }
        val current = keys.of(layout.state)
        val currentTransaction = if (checkTransaction) keys.of(txn) else null
        if (binding.directory == null || current == null || (checkTransaction && (binding.transaction == null || currentTransaction == null))) {
            manual("this file system does not report file identities (fileKey), so createscript cannot confirm that ${shown(layout.journal)} was written in this directory")
        }
        if (binding.directory != current || (checkTransaction && binding.transaction != currentTransaction)) {
            manual("${shown(layout.journal)} was not written in this directory on this computer (${InstallLayout.DIRECTORY} or ${shown(txn)} is not the directory it was written in); $moved")
        }
    }

    private fun current(path: Path, maxBytes: Int): ReadFile? = try {
        SafeIo.readRegular(path, maxBytes)
    } catch (failure: InstallFailure) {
        manual(failure.message ?: "cannot read $path")
    }

    // 作業用のディレクトリにある写しが、ジャーナルの SHA-256 と同じならその中身
    private fun copy(txn: Path, name: String, sha256: String?, maxBytes: Int): ByteArray? {
        if (sha256 == null) return null
        val path = txn.resolve(name)
        return try {
            SafeIo.readRegular(path, maxBytes)?.takeIf { it.identity.sha256 == sha256 && owner.owns(path) }?.bytes
        } catch (failure: InstallFailure) {
            null
        }
    }

    private fun saved(txn: Path, name: String, sha256: String?, maxBytes: Int): Boolean = copy(txn, name, sha256, maxBytes) != null

    // SHA-256 が sha256 の版の中身（今のファイルか、作業用のディレクトリの写し）
    private fun version(file: ReadFile?, txn: Path, name: String, sha256: String?, maxBytes: Int): ByteArray? =
        if (sha256 != null && file?.identity?.sha256 == sha256) file.bytes else copy(txn, name, sha256, maxBytes)

    // ---- 書き込む写しの内容の確認（S8.2 L-2）。問題があれば理由を返す ----

    // install が書くマニフェストは、前のものと同じか、前のものに依存を 1 つ足した・変えたもの（ManifestEditor.withDependency の出力）だけ
    private fun manifestProblem(oldBytes: ByteArray, newBytes: ByteArray): String? {
        val old = Manifests.parse(oldBytes).manifest ?: return "the saved ${Manifests.FILE_NAME} from before the installation is not a valid manifest"
        val new = Manifests.parse(newBytes).manifest ?: return "the ${Manifests.FILE_NAME} from after the installation is not a valid manifest"
        if (oldBytes.contentEquals(newBytes)) return null
        val added = new.dependencies.filter { it !in old.dependencies }.singleOrNull()
        val rebuilt = added?.let {
            try {
                ManifestEditor.withDependency(oldBytes, old, it)
            } catch (failure: InstallFailure) {
                null
            }
        }
        if (rebuilt == null || !rebuilt.contentEquals(newBytes)) {
            return "the ${Manifests.FILE_NAME} from after the installation is not the one from before it with one dependency added or changed, which is the only change createscript install makes"
        }
        return null
    }

    // 前のロックファイルと同じ name・version・source の項目は、install が必ず同じ SHA-256・依存にする（Resolver）
    private fun pinProblem(old: Lockfile?, new: Lockfile): String? {
        val byName = old?.packages?.associateBy { it.name }.orEmpty()
        for (entry in new.packages) {
            val previous = byName[entry.name] ?: continue
            if (previous.version != entry.version || previous.source != entry.source) continue
            if (previous.sha256 != entry.sha256 || previous.dependencies != entry.dependencies) {
                return "the ${Lockfiles.FILE_NAME} from after the installation changes the SHA-256 or the dependencies of ${entry.name} ${entry.version}, which createscript install never does"
            }
        }
        return null
    }

    private fun parseLock(bytes: ByteArray, label: String): Pair<Lockfile?, String?> {
        val lock = Lockfiles.parse(bytes).lockfile ?: return null to "the $label ${Lockfiles.FILE_NAME} is not valid"
        return lock to null
    }

    // 続行する前: 書き込む新しいマニフェスト・ロックファイルと、置くパッケージ
    private fun checkForward(journal: Journal, txn: Path, manifest: ReadFile?, lock: ReadFile?): String? {
        val newManifestBytes = version(manifest, txn, MANIFEST_NEW, journal.manifestNew, Manifests.MAX_BYTES) ?: return "the new ${Manifests.FILE_NAME} is missing"
        val oldManifestBytes = version(manifest, txn, MANIFEST_OLD, journal.manifestOld, Manifests.MAX_BYTES)
            ?: return "the saved ${Manifests.FILE_NAME} from before the installation is missing, so the new one cannot be checked"
        manifestProblem(oldManifestBytes, newManifestBytes)?.let { return it }
        val newManifest: Manifest = Manifests.parse(newManifestBytes).manifest!!
        val oldLock = journal.lockOld?.let { sha ->
            val bytes = version(lock, txn, LOCK_OLD, sha, Lockfiles.MAX_BYTES)
                ?: return "the saved ${Lockfiles.FILE_NAME} from before the installation is missing, so the new one cannot be checked"
            parseLock(bytes, "saved").let { (parsed, problem) -> parsed ?: return "$problem (from before the installation)" }
        }
        val newLockSha = journal.lockNew
        if (newLockSha == null) {
            if (journal.packages.isNotEmpty() || newManifest.dependencies.isNotEmpty()) return "the journal adds packages but no ${Lockfiles.FILE_NAME}"
            return null
        }
        val newLockBytes = version(lock, txn, LOCK_NEW, newLockSha, Lockfiles.MAX_BYTES) ?: return "the new ${Lockfiles.FILE_NAME} is missing"
        val parsed = Lockfiles.parse(newLockBytes)
        val newLock = parsed.lockfile ?: return "the new ${Lockfiles.FILE_NAME} is not valid"
        if (Lockfiles.check(newLockBytes, parsed, newManifest).isNotEmpty()) {
            return "the new ${Lockfiles.FILE_NAME} does not match the new ${Manifests.FILE_NAME} (as createscript lock check would report)"
        }
        pinProblem(oldLock, newLock)?.let { return it }
        val locked = newLock.packages.associateBy { it.name }
        for (p in journal.packages) {
            val entry = locked[p.name]?.takeIf { it.version == p.version } ?: return "${p.name} ${p.version} is not in the new ${Lockfiles.FILE_NAME}"
            val target = layout.packageDirectory(p.name, p.version)
            val placed = PackageDirectories.inspect(target, owner, keepContents = true)
            val state = if (ours(placed, journal, p)) placed else PackageDirectories.inspect(txn.resolve(STAGING).resolve(p.name).resolve(p.version), owner, keepContents = true)
            if (state !is PackageState.Valid || !ours(state, journal, p)) return "the prepared copy of ${p.name} ${p.version} is missing or was changed"
            if (state.marker.source != entry.source || state.marker.sha256 != entry.sha256 || !PackageDirectories.matchesArchive(state, entry.sha256)) {
                return "the prepared copy of ${p.name} ${p.version} does not match the archive in the new ${Lockfiles.FILE_NAME} (SHA-256 ${entry.sha256})"
            }
        }
        return null
    }

    // 取り消す前: 書き戻す前の版の写し（書き戻すものだけ）
    private fun checkBackward(journal: Journal, txn: Path, manifest: ReadFile?, lock: ReadFile?): String? {
        if (journal.manifestOld != journal.manifestNew && manifest != null && manifest.identity.sha256 == journal.manifestNew) {
            val old = copy(txn, MANIFEST_OLD, journal.manifestOld, Manifests.MAX_BYTES) ?: return "the saved ${Manifests.FILE_NAME} from before the installation is missing"
            manifestProblem(old, manifest.bytes)?.let { return it }
        }
        val lockOld = journal.lockOld
        if (lockOld != null && lockOld != journal.lockNew && lock != null && lock.identity.sha256 == journal.lockNew) {
            val old = copy(txn, LOCK_OLD, lockOld, Lockfiles.MAX_BYTES) ?: return "the saved ${Lockfiles.FILE_NAME} from before the installation is missing"
            val oldLock = parseLock(old, "saved").let { (parsed, problem) -> parsed ?: return "$problem (from before the installation)" }
            val newLock = parseLock(lock.bytes, "current").let { (parsed, problem) -> parsed ?: return problem }
            pinProblem(oldLock, newLock)?.let { return it }
        }
        return null
    }

    // このトランザクションが置いたパッケージか（印の transaction と、印の SHA-256 がジャーナルと同じ）
    private fun ours(state: PackageState, journal: Journal, p: JournalPackage): Boolean =
        state is PackageState.Valid && state.marker.transaction == journal.id && state.markerSha256 == p.marker && state.marker.name == p.name && state.marker.version == p.version

    // trusted は、このプロセスが書いたジャーナル（インストールの途中の失敗を、その場で取り消すとき）。null なら、ディスクのジャーナルの書いた場所を確かめる
    fun recover(preferForward: Boolean, trusted: Journal? = null): RecoveryOutcome {
        val journal = load(trusted)
        val txn = layout.transaction(journal.id)
        val txnAttributes = SafeIo.attributes(txn)
        if (txnAttributes != null && (txnAttributes.isSymbolicLink || !txnAttributes.isDirectory || !owner.owns(txn))) manual("${shown(txn)} is not a directory of this installation")
        // 置き場所の途中がリンクなら、リンクの先を読まず・書かない（S8.2 L-5）。中断の後の復旧は止まり、同じプロセスの中の取り消しは、
        // そのパッケージに触らずに（残したものとして知らせて）ほかを取り消す
        (SafeIo.chain(layout.root, txn) as? SafeIo.Chain.NotReal)?.let { manual(it.reason) }
        // ジャーナルを書き換える途中（journal.tmp を書いている途中）で止まったもの（S8.2 N-1）。何かを変える前に、片付けてよいものか確かめる。
        // 正式なジャーナルは transaction.json で、journal.tmp はその一時ファイル。確かめられなければ、何も変えずに止まる
        if (txnAttributes != null) {
            val temporary = txn.resolve(JOURNAL_TEMP)
            removeJournalTemp(temporary, journal)?.let { problem ->
                manual(
                    "${shown(temporary)}, the temporary file used to rewrite the journal, cannot be removed safely ($problem)",
                    "Move ${shown(temporary)} out of the project (keep it until you are sure). It is only a temporary copy; the journal itself is ${shown(layout.journal)}. Then run createscript install again, which finishes or undoes the installation.",
                )
            }
        }
        if (journal.state == Journal.State.COMMITTED) {
            val kept = cleanup(journal)
            return RecoveryOutcome("finished cleaning up an installation that had completed", kept)
        }
        val linked = journal.packages.associate { p -> p.name to (SafeIo.chain(layout.root, layout.packages.resolve(p.name)) as? SafeIo.Chain.NotReal) }
        if (trusted == null) linked.values.firstOrNull { it != null }?.let { manual(it.reason) }
        val manifestFile = current(layout.manifest, Manifests.MAX_BYTES)
        val lockFile = current(layout.lockfile, Lockfiles.MAX_BYTES)
        val manifest = manifestFile?.identity?.sha256
        val lock = lockFile?.identity?.sha256
        val states = journal.packages.map { p ->
            if (SafeIo.attributes(layout.packages)?.let { it.isSymbolicLink || !it.isDirectory } == true) manual("${shown(layout.packages)} is not a directory")
            val target = linked[p.name]?.let { PackageState.Invalid(it.reason) } ?: PackageDirectories.inspect(layout.packageDirectory(p.name, p.version), owner)
            val staged = if (txnAttributes == null) PackageState.Absent else PackageDirectories.inspect(txn.resolve(STAGING).resolve(p.name).resolve(p.version), owner)
            Triple(p, target, staged)
        }
        val canForward = manifest != null && (manifest == journal.manifestOld || manifest == journal.manifestNew) &&
            (manifest == journal.manifestNew || saved(txn, MANIFEST_NEW, journal.manifestNew, Manifests.MAX_BYTES)) &&
            (lock == journal.lockOld || lock == journal.lockNew) &&
            (lock == journal.lockNew || saved(txn, LOCK_NEW, journal.lockNew, Lockfiles.MAX_BYTES)) &&
            states.all { (p, target, staged) -> ours(target, journal, p) || (target == PackageState.Absent && ours(staged, journal, p)) }
        val canBackward = (manifest != journal.manifestNew || journal.manifestOld == journal.manifestNew || saved(txn, MANIFEST_OLD, journal.manifestOld, Manifests.MAX_BYTES)) &&
            (lock != journal.lockNew || journal.lockOld == journal.lockNew || journal.lockOld == null || saved(txn, LOCK_OLD, journal.lockOld, Lockfiles.MAX_BYTES))
        val direction = when {
            preferForward && canForward -> true
            canBackward -> false
            canForward -> true
            else -> manual("the saved copies in ${shown(txn)} are missing or were changed")
        }
        // 書き込む写しの内容を確かめる（どちらの向きでも。問題があれば、もう一方の向きにも切り替えずに止まる）
        if (direction) {
            checkForward(journal, txn, manifestFile, lockFile)?.let { manual("$it; the installation was not completed") }
            return RecoveryOutcome("completed an interrupted installation", forward(journal, null, null))
        }
        checkBackward(journal, txn, manifestFile, lockFile)?.let { manual("$it; the installation was not undone") }
        return RecoveryOutcome("undid an interrupted installation", backward(journal))
    }

    // 続行。expectedManifest・expectedLock は、インストールの最中なら読んだときの同一性（別のファイルへの差し替えを見分ける）
    // パッケージを置く場所が、プロジェクトの中の実際のディレクトリであること（S8.2 L-5）
    private fun requireReal(directory: Path, what: String) {
        when (val chain = SafeIo.chain(layout.root, directory)) {
            SafeIo.Chain.Real -> {}
            SafeIo.Chain.Missing -> unsafe("${shown(directory)} disappeared during the installation; $what")
            is SafeIo.Chain.NotReal -> unsafe("${chain.reason}; another program changed it during the installation, so $what")
        }
    }

    // 置いたパッケージが、置いた場所にあるか（S8.2 L-5）。parentKey・key は、移動の前に記録した packages/<name> と、パッケージのディレクトリの fileKey
    // （取れなければ null。そのときは実パスと印で確かめる）。違えば理由
    private fun placedProblem(p: JournalPackage, journal: Journal, target: Path, parentKey: String?, key: String?): String? {
        when (val chain = SafeIo.chain(layout.root, target.parent)) {
            SafeIo.Chain.Real -> {}
            SafeIo.Chain.Missing -> return "${shown(target.parent)} disappeared"
            is SafeIo.Chain.NotReal -> return chain.reason
        }
        if (parentKey != null && SafeIo.currentId(target.parent) != parentKey) return "${shown(target.parent)} was replaced by another directory"
        val attributes = SafeIo.attributes(target) ?: return "${shown(target)} is not there"
        if (attributes.isSymbolicLink || !attributes.isDirectory) return "${shown(target)} is not a directory"
        if (key != null && SafeIo.currentId(target) != key) return "${shown(target)} is another directory"
        if (SafeIo.chain(layout.root, target) != SafeIo.Chain.Real) return "${shown(target)} is not a directory inside the project"
        if (!ours(PackageDirectories.inspect(target, owner), journal, p)) return "the files in ${shown(target)} do not match the prepared package"
        return null
    }

    private fun escaped(p: JournalPackage, target: Path, problem: String): Nothing = unsafe(
        "${p.name} ${p.version} was not found in ${shown(target)} after it was moved there: $problem. Another program changed the directories " +
            "during the installation, so the package may have been written outside the project; nothing was removed there, and the installation was not completed",
    )

    fun forward(journal: Journal, expectedManifest: Identity?, expectedLock: Identity?): List<String> {
        val txn = layout.transaction(journal.id)
        // 置いたパッケージ（packages/<name> とパッケージのディレクトリの fileKey）。マニフェストを書き換える前に、もう一度すべてを確かめる
        val placed = ArrayList<Triple<JournalPackage, String?, String?>>()
        for (p in journal.packages) {
            SafeIo.directory(layout.packages, create = true, layout.shown(layout.packages))
            SafeIo.directory(layout.packages.resolve(p.name), create = true, layout.shown(layout.packages.resolve(p.name)))
            val target = layout.packageDirectory(p.name, p.version)
            requireReal(target.parent, "${p.name} ${p.version} was not moved")
            when (val state = PackageDirectories.inspect(target, owner)) {
                PackageState.Absent -> {}
                is PackageState.Valid -> if (ours(state, journal, p)) {
                    placed += Triple(p, SafeIo.currentId(target.parent), SafeIo.currentId(target))
                    continue
                } else {
                    unsafe("${shown(target)} was created by another program during the installation")
                }
                is PackageState.Invalid -> unsafe("${shown(target)} cannot be used (${state.reason})")
            }
            val staged = txn.resolve(STAGING).resolve(p.name).resolve(p.version)
            requireReal(staged.parent, "${p.name} ${p.version} was not moved")
            val stagedState = PackageDirectories.inspect(staged, owner, keepContents = true)
            if (!ours(stagedState, journal, p)) unsafe("the prepared copy of ${p.name} ${p.version} is missing or was changed")
            val parentKey = SafeIo.currentId(target.parent)
            val anchored = SafeIo.anchored(target)
            val key = if (anchored) null else SafeIo.currentId(staged)
            hooks.reached("moving:${p.name}")
            val placedKey = try {
                // S8.3 L-5: 固定したディレクトリでは、確かめた写しの中身を packages/<name>/.placing-<id> に書き、同じディレクトリの中で名前を付ける
                // （作業用のディレクトリから packages へ、ディレクトリをまたいで移さない）
                if (anchored) place(stagedState as PackageState.Valid, target, journal) else SafeIo.moveNew(staged, target).let { key }
            } catch (error: FileAlreadyExistsException) {
                unsafe("${shown(target)} was created by another program during the installation")
            }
            hooks.reached("moved:${p.name}")
            // 移動は、確かめた後に途中のディレクトリがリンクに差し替えられると、リンクの先へ行く（防げない）。移動した後に、置いた場所にあることを確かめ、
            // 無ければ成功扱いにしない（リンクの先は触らない）
            placedProblem(p, journal, target, parentKey, placedKey)?.let { escaped(p, target, it) }
            placed += Triple(p, parentKey, placedKey)
            // 置いた写しは、これまでの移動と同じく作業用のディレクトリから無くす（確かめたものだけ。消せなければ後片付けが知らせる）
            if (anchored) PackageDirectories.delete(staged, stagedState as PackageState.Valid)
            SafeIo.syncDirectory(target.parent)
            hooks.reached("package:${p.name}")
        }
        // マニフェスト・ロックファイルを書き換える直前に、置いたすべてのパッケージを確かめ直す
        hooks.reached("placed")
        for ((p, parentKey, key) in placed) {
            val target = layout.packageDirectory(p.name, p.version)
            placedProblem(p, journal, target, parentKey, key)?.let { escaped(p, target, it) }
        }
        replaceFile(layout.manifest, journal.manifestOld, journal.manifestNew, txn.resolve(MANIFEST_NEW), expectedManifest, Manifests.MAX_BYTES)
        hooks.reached("manifest")
        replaceFile(layout.lockfile, journal.lockOld, journal.lockNew, txn.resolve(LOCK_NEW), expectedLock, Lockfiles.MAX_BYTES)
        hooks.reached("lock")
        writeJournal(journal.committed())
        hooks.reached("committed")
        return cleanup(journal)
    }

    // 今のファイルが old（読んだときと同じファイル）であることを確かめてから、用意した new に差し替える。既に new なら何もしない
    private fun replaceFile(target: Path, old: String?, new: String?, replacement: Path, expected: Identity?, maxBytes: Int) {
        val current = SafeIo.readRegular(target, maxBytes)
        val sha = current?.identity?.sha256
        if (old == new) {
            if (sha != old) unsafe("${shown(target)} was changed by another program during the installation; it was not overwritten")
            return
        }
        if (sha == new) return
        if (sha != old) unsafe("${shown(target)} was changed by another program during the installation; it was not overwritten")
        if (expected != null && current != null && !current.identity.sameFileAs(expected)) unsafe("${shown(target)} was replaced by another program during the installation; it was not overwritten")
        val prepared = SafeIo.readRegular(replacement, maxBytes)
        if (prepared == null || prepared.identity.sha256 != new) unsafe("the prepared ${shown(replacement)} is missing or was changed")
        if (SafeIo.anchored(target)) {
            // S8.3 L-5: 確かめた中身を target と同じディレクトリに書いてから付け替える（作業用のディレクトリから、ディレクトリをまたいで移さない）。
            // 権限は前のファイルと同じにする
            try {
                if (current != null) SafeIo.replaceContent(target, prepared.bytes, SafeIo.modeOf(target)) else SafeIo.publishContent(target, prepared.bytes)
            } catch (error: FileAlreadyExistsException) {
                unsafe("${shown(target)} was created by another program during the installation; it was not overwritten")
            }
            // 使った写しは、これまでの移動と同じく無くす
            SafeIo.deleteFileIfSame(replacement, new!!, maxBytes)
        } else if (current != null) {
            SafeIo.copyPermissions(target, replacement)
            SafeIo.replace(replacement, target)
        } else {
            try {
                // 置き換えない付け替え（S8.3 L-5: ハードリンク。確かめた後に作られた同じ名前のファイルを置き換えない）
                SafeIo.renameNoReplace(replacement, target)
            } catch (error: FileAlreadyExistsException) {
                unsafe("${shown(target)} was created by another program during the installation; it was not overwritten")
            }
        }
        SafeIo.syncDirectory(target.parent)
    }

    // 取り消し。消さなかった・戻さなかったものを返す
    private fun backward(journal: Journal): List<String> {
        val txn = layout.transaction(journal.id)
        val kept = ArrayList<String>()
        restoreFile(layout.manifest, journal.manifestOld, journal.manifestNew, txn.resolve(MANIFEST_OLD), Manifests.MAX_BYTES)?.let { kept += it }
        hooks.reached("rollback:manifest")
        restoreFile(layout.lockfile, journal.lockOld, journal.lockNew, txn.resolve(LOCK_OLD), Lockfiles.MAX_BYTES)?.let { kept += it }
        hooks.reached("rollback:lock")
        for (p in journal.packages.asReversed()) {
            val target = layout.packageDirectory(p.name, p.version)
            // 途中がリンクなら、リンクの先を読まず・消さない（S8.2 L-5）
            val chain = SafeIo.chain(layout.root, target.parent)
            if (chain is SafeIo.Chain.NotReal) {
                kept += "${shown(target)} (not touched: ${chain.reason})"
                hooks.reached("rollback:package:${p.name}")
                continue
            }
            when (val state = PackageDirectories.inspect(target, owner)) {
                PackageState.Absent -> {}
                is PackageState.Valid -> if (ours(state, journal, p)) kept += PackageDirectories.delete(target, state)
                is PackageState.Invalid -> kept += "${shown(target)} (${state.reason})"
            }
            // 置く途中で止まった .placing-<id>（S8.3 L-5）
            removePlacing(target, journal)?.let { kept += it }
            hooks.reached("rollback:package:${p.name}")
        }
        return kept + cleanup(journal)
    }

    // new（今回書いたもの）なら old に戻す（old が無かったなら消す）。old ならそのまま。どちらでもなければ、触らずに理由を返す
    private fun restoreFile(target: Path, old: String?, new: String?, backup: Path, maxBytes: Int): String? {
        if (old == new) return null
        val current = try {
            SafeIo.readRegular(target, maxBytes)
        } catch (failure: InstallFailure) {
            return "${shown(target)} (${failure.message})"
        }
        val sha = current?.identity?.sha256
        if (sha == old) return null
        if (sha != new) return "${shown(target)} (it was changed by another program)"
        if (old == null) {
            return SafeIo.deleteFileIfSame(target, new!!, maxBytes, current!!.identity.fileKey)?.let { "${shown(target)} ($it)" }
        }
        val saved = SafeIo.readRegular(backup, maxBytes)
        if (saved == null || saved.identity.sha256 != old) unsafe("the saved copy ${shown(backup)} is missing or was changed")
        if (SafeIo.anchored(target)) {
            SafeIo.replaceContent(target, saved.bytes, SafeIo.modeOf(target))
            SafeIo.deleteFileIfSame(backup, old, maxBytes)
        } else {
            SafeIo.copyPermissions(target, backup)
            SafeIo.replace(backup, target)
        }
        SafeIo.syncDirectory(target.parent)
        return null
    }

    // ジャーナルを書き換える（作業用のディレクトリの一時ファイルから、置き換える移動で）
    private fun writeJournal(journal: Journal) {
        val txn = layout.transaction(journal.id)
        requireReal(txn, "the journal was not changed")
        val temporary = txn.resolve(JOURNAL_TEMP)
        removeJournalTemp(temporary, journal)?.let { unsafe("${shown(temporary)} cannot be replaced ($it); the journal was not changed. Move it out of the project and run createscript install again") }
        if (SafeIo.anchored(layout.journal)) {
            // S8.3 L-5: .createscript の中に書いてから付け替える
            SafeIo.replaceContent(layout.journal, journal.write(), SafeIo.modeOf(layout.journal))
        } else {
            SafeIo.createFile(temporary, journal.write())
            SafeIo.replace(temporary, layout.journal)
        }
        SafeIo.syncDirectory(layout.state)
    }

    // ---- S8.3 L-5: パッケージを置く（固定したディレクトリ） ----

    private fun placing(target: Path, journal: Journal): Path = target.resolveSibling(PLACING_PREFIX + journal.id)

    // 確かめた写し（印と中身）を packages/<name>/.placing-<id> に書き、同じディレクトリの中で <version> の名前を付ける（置き換えない）。
    // 印を最初に書く（途中で止まっても、印と照合できる: S8.2 L-3 と同じ順）。置いたディレクトリの識別子を返す
    private fun place(staged: PackageState.Valid, target: Path, journal: Journal): String {
        val markerBytes = staged.markerBytes ?: unsafe("internal error: the prepared copy was read without its contents")
        val directory = placing(target, journal)
        removePlacing(target, journal)?.let { unsafe("${shown(directory)} was left by an earlier attempt and cannot be removed ($it)") }
        val owned = HashSet<String>()
        SafeIo.createOwnedDirectory(directory, owned)
        val id = owned.single()
        SafeIo.writeNew(directory.resolve(InstallLayout.MARKER), markerBytes, owned = owned)
        if (staged.contents.keys.any { it.startsWith("$MODULES/") }) SafeIo.createOwnedDirectory(directory.resolve(MODULES), owned)
        for ((relative, bytes) in staged.contents.entries.sortedBy { it.key }) {
            val path = if (relative.startsWith("$MODULES/")) directory.resolve(MODULES).resolve(relative.removePrefix("$MODULES/")) else directory.resolve(relative)
            SafeIo.writeNew(path, bytes, owned = owned)
        }
        if (staged.contents.keys.any { it.startsWith("$MODULES/") }) SafeIo.syncDirectory(directory.resolve(MODULES))
        SafeIo.syncDirectory(directory)
        SafeIo.moveNew(directory, target)
        return id
    }

    // 前の試みで置く途中で止まった .placing-<id> を消す（このトランザクションのもの: 名前に id がある）。中は、印に書かれた名前・modules・
    // 書きかけ（.part-…）だけで、ほかのものがあれば消さずに理由を返す。無ければ・消せたら null
    private fun removePlacing(target: Path, journal: Journal): String? {
        val directory = placing(target, journal)
        val attributes = SafeIo.attributes(directory) ?: return null
        if (attributes.isSymbolicLink || !attributes.isDirectory || !owner.owns(directory)) return "${shown(directory)} (it is not a directory of this installation)"
        val p = journal.packages.firstOrNull { it.name == target.parent.fileName.toString() && it.version == target.fileName.toString() } ?: return "${shown(directory)} (it is not part of this installation)"
        val markerPath = directory.resolve(InstallLayout.MARKER)
        val names = (Marker.parse(SafeIo.readRegular(markerPath, Marker.MAX_BYTES)?.bytes ?: ByteArray(0))?.takeIf { it.transaction == journal.id && it.name == p.name }?.files?.keys ?: emptySet()) +
            InstallLayout.MARKER
        fun removable(name: String, known: Set<String>) = name in known || name.startsWith(SafeIo.PART_PREFIX)
        val modules = directory.resolve(MODULES)
        if (SafeIo.exists(modules)) {
            val knownModules = names.filter { it.startsWith("$MODULES/") }.map { it.removePrefix("$MODULES/") }.toSet()
            for (name in SafeIo.list(modules, 1024)) {
                if (!removable(name, knownModules)) return "${shown(modules.resolve(name))} (it was not written by this installation)"
                SafeIo.deleteVerified(modules.resolve(name)) { SafeIo.attributes(it)?.isRegularFile == true }?.let { return "${shown(modules.resolve(name))} ($it)" }
            }
            SafeIo.deleteEmptyDirectory(modules)?.let { return "${shown(modules)} ($it)" }
        }
        val topNames = names.filter { '/' !in it }.toSet()
        for (name in SafeIo.list(directory, 1024)) {
            if (!removable(name, topNames)) return "${shown(directory.resolve(name))} (it was not written by this installation)"
            SafeIo.deleteVerified(directory.resolve(name)) { SafeIo.attributes(it)?.isRegularFile == true }?.let { return "${shown(directory.resolve(name))} ($it)" }
        }
        return SafeIo.deleteEmptyDirectory(directory)?.let { "${shown(directory)} ($it)" }
    }

    // 書きかけ（S8.3 L-5: 固定したディレクトリでは、マニフェスト・ロックファイル・ジャーナルを、置く場所の .part-… で書いてから付け替える）。
    // 途中で止まったもの（このユーザーの、読み取りだけの通常のファイルで、空の .part-<UUID> か、最終の名前が finals のどれかで i-node・大きさが名前と合うもの）を消す
    private fun removeStrayParts(directory: Path, finals: Set<String>) {
        if (!SafeIo.anchored(directory.resolve("x"))) return
        val names = try {
            SafeIo.list(directory, 4096)
        } catch (failure: InstallFailure) {
            return
        }
        for (name in names.filter { it.startsWith(SafeIo.PART_PREFIX) }) {
            val path = directory.resolve(name)
            val attributes = SafeIo.attributes(path) ?: continue
            if (attributes.isSymbolicLink || !attributes.isRegularFile || !owner.owns(path) || !SafeIo.isReadOnlyPart(path)) continue
            val ours = if (SafeIo.isCreatingPart(name)) {
                attributes.size() == 0L
            } else {
                val part = SafeIo.part(name) ?: continue
                part.finalName in finals && SafeIo.inode(path) == part.inode && attributes.size() <= part.size
            }
            if (ours) SafeIo.deleteVerified(path) { SafeIo.attributes(it)?.let { a -> a.isRegularFile && a.size() == attributes.size() } == true }
        }
    }

    // 作業用のディレクトリの journal.tmp（ジャーナルを書き換えるための一時ファイル）を消す（S8.2 N-1）。無ければ何もしない。
    // 消すのは、このユーザーの、リンクでない通常のファイルで、次のどちらかのものだけ（作業用のディレクトリは、呼ぶ側が確かめたもの: 書いた場所・所有者・リンクでないこと）:
    // - このトランザクションのジャーナル（id が同じ。書き終えたもの）
    // - このトランザクションのジャーナル（prepared・committed）が書くバイト列の先頭の一部（書いている途中で止まったもの。空のファイルを含む）。
    //   ジャーナルのバイト列は内容から決まるので、途中で止まったものは必ずその先頭と同じになる
    // それ以外（リンク・通常のファイルでない・ほかのユーザーのもの・読めない・別の中身）は消さずに理由を返す
    private fun removeJournalTemp(path: Path, journal: Journal): String? {
        val attributes = SafeIo.attributes(path) ?: return null
        if (attributes.isSymbolicLink) return "it is a symbolic link"
        if (!attributes.isRegularFile) return "it is not a regular file"
        if (!owner.owns(path)) return "it belongs to another user"
        val file = try {
            SafeIo.readRegular(path, Journal.MAX_BYTES) ?: return null
        } catch (failure: InstallFailure) {
            return failure.message ?: "it cannot be read"
        }
        val complete = Journal.parse(file.bytes, limits.maxPackages).first?.id == journal.id
        val partial = Journal.State.entries.any { state ->
            val expected = journal.copy(state = state).write()
            file.bytes.size <= expected.size && expected.copyOf(file.bytes.size).contentEquals(file.bytes)
        }
        if (!complete && !partial) return "it is neither a journal of this installation nor the beginning of one that was being written"
        return SafeIo.deleteFileIfSame(path, file.identity.sha256, Journal.MAX_BYTES, file.identity.fileKey)
    }

    // 作業用のディレクトリと、ジャーナルを消す。消すのは、このトランザクションのもの（名前が決まっていて、SHA-256・印が一致するもの）だけ。残したものを返す
    private fun cleanup(journal: Journal): List<String> {
        val kept = ArrayList<String>()
        val txn = layout.transaction(journal.id)
        val attributes = SafeIo.attributes(txn)
        if (attributes != null) {
            val chain = SafeIo.chain(layout.root, txn)
            if (attributes.isSymbolicLink || !attributes.isDirectory || !owner.owns(txn) || chain is SafeIo.Chain.NotReal) {
                kept += "${shown(txn)} (it is not a directory of this installation" + ((chain as? SafeIo.Chain.NotReal)?.let { ": ${it.reason}" } ?: "") + ")"
            } else {
                for (name in SafeIo.list(txn, 64)) {
                    val path = txn.resolve(name)
                    val reason = when (name) {
                        MANIFEST_OLD -> SafeIo.deleteFileIfSame(path, journal.manifestOld, Manifests.MAX_BYTES)
                        MANIFEST_NEW -> SafeIo.deleteFileIfSame(path, journal.manifestNew, Manifests.MAX_BYTES)
                        // （deleteFileIfSame は、消せたときに null を返す。?.let と ?: を組み合わせると、消せたことを「作っていない」と取り違えるため、if で分ける）
                        LOCK_OLD -> if (journal.lockOld == null) "it was not created by this installation" else SafeIo.deleteFileIfSame(path, journal.lockOld, Lockfiles.MAX_BYTES)
                        LOCK_NEW -> if (journal.lockNew == null) "it was not created by this installation" else SafeIo.deleteFileIfSame(path, journal.lockNew, Lockfiles.MAX_BYTES)
                        JOURNAL_TEMP -> removeJournalTemp(path, journal)
                        STAGING -> cleanStaging(path, journal)
                        else -> "it was not created by this installation"
                    }
                    reason?.let { kept += "${shown(path)} ($it)" }
                }
                if (kept.isEmpty()) SafeIo.deleteEmptyDirectory(txn)?.let { kept += "${shown(txn)} ($it)" }
            }
        }
        hooks.reached("cleaned")
        // ジャーナル（今も同じトランザクションのものであること）
        val file = try {
            SafeIo.readRegular(layout.journal, Journal.MAX_BYTES)
        } catch (failure: InstallFailure) {
            null
        }
        if (file != null) {
            if (Journal.parse(file.bytes, limits.maxPackages).first?.id == journal.id) {
                SafeIo.deleteFileIfSame(layout.journal, file.identity.sha256, Journal.MAX_BYTES, file.identity.fileKey)?.let { kept += "${shown(layout.journal)} ($it)" }
            } else {
                kept += "${shown(layout.journal)} (it was replaced by another program)"
            }
        }
        // 書き換えの途中で止まった書きかけ（S8.3 L-5）
        removeStrayParts(layout.state, setOf(InstallLayout.JOURNAL))
        removeStrayParts(layout.root, setOf(Manifests.FILE_NAME, Lockfiles.FILE_NAME))
        SafeIo.syncDirectory(layout.state)
        return kept
    }

    private fun cleanStaging(staging: Path, journal: Journal): String? {
        val attributes = SafeIo.attributes(staging) ?: return null
        if (attributes.isSymbolicLink || !attributes.isDirectory) return "it is not a directory"
        val byName = journal.packages.associateBy { it.name }
        for (name in SafeIo.list(staging, limits.maxPackages + 1)) {
            val p = byName[name] ?: return "it holds $name, which is not part of this installation"
            val parent = staging.resolve(name)
            val parentAttributes = SafeIo.attributes(parent)
            if (parentAttributes == null || parentAttributes.isSymbolicLink || !parentAttributes.isDirectory) return "$name is not a directory"
            for (version in SafeIo.list(parent, 2)) {
                if (version != p.version) return "it holds $name $version, which is not part of this installation"
                val state = PackageDirectories.inspect(parent.resolve(version), owner)
                if (!ours(state, journal, p)) return "the prepared copy of $name $version was changed"
                PackageDirectories.delete(parent.resolve(version), state as PackageState.Valid).firstOrNull()?.let { return it }
            }
            SafeIo.deleteEmptyDirectory(parent)?.let { return "$name ($it)" }
        }
        return SafeIo.deleteEmptyDirectory(staging)
    }
}
