package com.creatrbypc.createanalytical.install

import com.creatrbypc.createanalytical.project.Lockfiles
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
internal class Recovery(private val layout: Layout, private val owner: Owner, private val limits: InstallLimits, private val hooks: InstallHooks) {
    companion object {
        const val MANIFEST_OLD = "createscript.json.old"
        const val MANIFEST_NEW = "createscript.json.new"
        const val LOCK_OLD = "createscript.lock.old"
        const val LOCK_NEW = "createscript.lock.new"
        const val JOURNAL_TEMP = "journal.tmp"
        const val STAGING = "staging"
    }

    private fun unsafe(message: String): Nothing = throw InstallFailure(InstallFailure.Kind.UNSAFE, message)

    private fun shown(path: Path): String = layout.root.relativize(path).toString()

    // 読めたジャーナル（復旧できないときの説明に、残っているファイルと今の状態を示すために使う）
    private var loaded: Journal? = null

    // 復旧できないときの説明（S8.2 Phase 4 の条件: ジャーナルの無条件の削除を促さず、理由・残っているファイル・手で確かめる手順を示す）。何も変えない
    private fun manual(reason: String): Nothing {
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
            lines += "Current files:"
            lines += "  ${Manifests.FILE_NAME}: ${state(layout.manifest, journal.manifestOld, journal.manifestNew, Manifests.MAX_BYTES)}"
            lines += "  ${Lockfiles.FILE_NAME}: ${state(layout.lockfile, journal.lockOld, journal.lockNew, Lockfiles.MAX_BYTES)}"
        }
        val txn = journal?.let { "${InstallLayout.DIRECTORY}/${InstallLayout.TRANSACTION_PREFIX}${it.id}/" } ?: "the ${InstallLayout.DIRECTORY}/${InstallLayout.TRANSACTION_PREFIX}* directory"
        lines += "To check by hand:"
        lines += "  1. Compare ${Manifests.FILE_NAME} and ${Lockfiles.FILE_NAME} with the saved copies in $txn (*.old is the version before the installation, *.new the version after it), and keep the version you want."
        lines += "  2. In ${InstallLayout.DIRECTORY}/${InstallLayout.PACKAGES}, directories whose ${InstallLayout.MARKER} names this transaction" +
            (journal?.let { " (${it.id})" } ?: "") + " were added by the interrupted installation; they are used only if createscript.lock lists them."
        lines += "  3. Only when both files are as you want them, move ${InstallLayout.DIRECTORY}/${InstallLayout.JOURNAL} and $txn out of the project (keep them until you are sure), and run createscript install again; it checks every package again."
        unsafe(lines.joinToString("\n"))
    }

    // ジャーナルを読む（リンク・通常のファイルでない・ほかのユーザーのもの・壊れたものは使わない）
    private fun load(): Journal {
        val attributes = SafeIo.attributes(layout.journal) ?: manual("the journal disappeared")
        if (attributes.isSymbolicLink || !attributes.isRegularFile) manual("${shown(layout.journal)} is not a regular file")
        if (!owner.owns(layout.journal)) manual("${shown(layout.journal)} belongs to another user")
        val file = try {
            SafeIo.readRegular(layout.journal, Journal.MAX_BYTES)
        } catch (failure: InstallFailure) {
            manual(failure.message ?: "cannot read the journal")
        } ?: manual("the journal disappeared")
        val (journal, problem) = Journal.parse(file.bytes, limits.maxPackages)
        return journal ?: manual("${shown(layout.journal)} is damaged ($problem)")
    }

    private fun currentSha(path: Path, maxBytes: Int): String? = try {
        SafeIo.readRegular(path, maxBytes)?.identity?.sha256
    } catch (failure: InstallFailure) {
        manual(failure.message ?: "cannot read $path")
    }

    // 作業用のディレクトリにある写しが、ジャーナルの SHA-256 と同じか
    private fun saved(txn: Path, name: String, sha256: String?, maxBytes: Int): Boolean {
        if (sha256 == null) return false
        val path = txn.resolve(name)
        return try {
            SafeIo.readRegular(path, maxBytes)?.identity?.sha256 == sha256 && owner.owns(path)
        } catch (failure: InstallFailure) {
            false
        }
    }

    // このトランザクションが置いたパッケージか（印の transaction と、印の SHA-256 がジャーナルと同じ）
    private fun ours(state: PackageState, journal: Journal, p: JournalPackage): Boolean =
        state is PackageState.Valid && state.marker.transaction == journal.id && state.markerSha256 == p.marker && state.marker.name == p.name && state.marker.version == p.version

    fun recover(preferForward: Boolean): RecoveryOutcome {
        val journal = load()
        loaded = journal
        if (journal.state == Journal.State.COMMITTED) {
            val kept = cleanup(journal)
            return RecoveryOutcome("finished cleaning up an installation that had completed", kept)
        }
        val txn = layout.transaction(journal.id)
        val txnAttributes = SafeIo.attributes(txn)
        if (txnAttributes != null && (txnAttributes.isSymbolicLink || !txnAttributes.isDirectory || !owner.owns(txn))) manual("${shown(txn)} is not a directory of this installation")
        val manifest = currentSha(layout.manifest, Manifests.MAX_BYTES)
        val lock = currentSha(layout.lockfile, Lockfiles.MAX_BYTES)
        val states = journal.packages.map { p ->
            if (SafeIo.attributes(layout.packages)?.let { it.isSymbolicLink || !it.isDirectory } == true) manual("${shown(layout.packages)} is not a directory")
            val target = PackageDirectories.inspect(layout.packageDirectory(p.name, p.version), owner)
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
        return when {
            preferForward && canForward -> RecoveryOutcome("completed an interrupted installation", forward(journal, null, null))
            canBackward -> RecoveryOutcome("undid an interrupted installation", backward(journal))
            canForward -> RecoveryOutcome("completed an interrupted installation", forward(journal, null, null))
            else -> manual("the saved copies in ${shown(txn)} are missing or were changed")
        }
    }

    // 続行。expectedManifest・expectedLock は、インストールの最中なら読んだときの同一性（別のファイルへの差し替えを見分ける）
    fun forward(journal: Journal, expectedManifest: Identity?, expectedLock: Identity?): List<String> {
        val txn = layout.transaction(journal.id)
        for (p in journal.packages) {
            SafeIo.directory(layout.packages, create = true)
            SafeIo.directory(layout.packages.resolve(p.name), create = true)
            val target = layout.packageDirectory(p.name, p.version)
            when (val state = PackageDirectories.inspect(target, owner)) {
                PackageState.Absent -> {}
                is PackageState.Valid -> if (ours(state, journal, p)) continue else unsafe("${shown(target)} was created by another program during the installation")
                is PackageState.Invalid -> unsafe("${shown(target)} cannot be used (${state.reason})")
            }
            val staged = txn.resolve(STAGING).resolve(p.name).resolve(p.version)
            if (!ours(PackageDirectories.inspect(staged, owner), journal, p)) unsafe("the prepared copy of ${p.name} ${p.version} is missing or was changed")
            try {
                SafeIo.moveNew(staged, target)
            } catch (error: FileAlreadyExistsException) {
                unsafe("${shown(target)} was created by another program during the installation")
            }
            SafeIo.syncDirectory(target.parent)
            hooks.reached("package:${p.name}")
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
        if (current != null) {
            SafeIo.copyPermissions(target, replacement)
            SafeIo.replace(replacement, target)
        } else {
            try {
                SafeIo.moveNew(replacement, target)
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
            when (val state = PackageDirectories.inspect(target, owner)) {
                PackageState.Absent -> {}
                is PackageState.Valid -> if (ours(state, journal, p)) kept += PackageDirectories.delete(target, state)
                is PackageState.Invalid -> kept += "${shown(target)} (${state.reason})"
            }
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
        SafeIo.copyPermissions(target, backup)
        SafeIo.replace(backup, target)
        SafeIo.syncDirectory(target.parent)
        return null
    }

    // ジャーナルを書き換える（作業用のディレクトリの一時ファイルから、置き換える移動で）
    private fun writeJournal(journal: Journal) {
        val txn = layout.transaction(journal.id)
        val temporary = txn.resolve(JOURNAL_TEMP)
        deleteJournalTemp(temporary, journal.id)?.let { unsafe("${shown(temporary)} cannot be replaced ($it)") }
        SafeIo.createFile(temporary, journal.write())
        SafeIo.replace(temporary, layout.journal)
        SafeIo.syncDirectory(layout.state)
    }

    private fun deleteJournalTemp(path: Path, id: String): String? {
        val file = try {
            SafeIo.readRegular(path, Journal.MAX_BYTES) ?: return null
        } catch (failure: InstallFailure) {
            return failure.message
        }
        if (Journal.parse(file.bytes, limits.maxPackages).first?.id != id) return "it is not a journal of this installation"
        return SafeIo.deleteFileIfSame(path, file.identity.sha256, Journal.MAX_BYTES, file.identity.fileKey)
    }

    // 作業用のディレクトリと、ジャーナルを消す。消すのは、このトランザクションのもの（名前が決まっていて、SHA-256・印が一致するもの）だけ。残したものを返す
    private fun cleanup(journal: Journal): List<String> {
        val kept = ArrayList<String>()
        val txn = layout.transaction(journal.id)
        val attributes = SafeIo.attributes(txn)
        if (attributes != null) {
            if (attributes.isSymbolicLink || !attributes.isDirectory || !owner.owns(txn)) {
                kept += "${shown(txn)} (it is not a directory of this installation)"
            } else {
                for (name in SafeIo.list(txn, 64)) {
                    val path = txn.resolve(name)
                    val reason = when (name) {
                        MANIFEST_OLD -> SafeIo.deleteFileIfSame(path, journal.manifestOld, Manifests.MAX_BYTES)
                        MANIFEST_NEW -> SafeIo.deleteFileIfSame(path, journal.manifestNew, Manifests.MAX_BYTES)
                        // （deleteFileIfSame は、消せたときに null を返す。?.let と ?: を組み合わせると、消せたことを「作っていない」と取り違えるため、if で分ける）
                        LOCK_OLD -> if (journal.lockOld == null) "it was not created by this installation" else SafeIo.deleteFileIfSame(path, journal.lockOld, Lockfiles.MAX_BYTES)
                        LOCK_NEW -> if (journal.lockNew == null) "it was not created by this installation" else SafeIo.deleteFileIfSame(path, journal.lockNew, Lockfiles.MAX_BYTES)
                        JOURNAL_TEMP -> deleteJournalTemp(path, journal.id)
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
