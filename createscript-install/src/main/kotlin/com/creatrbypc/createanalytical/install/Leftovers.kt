package com.creatrbypc.createanalytical.install

import com.creatrbypc.createanalytical.project.Lockfiles
import com.creatrbypc.createanalytical.project.Manifests
import com.creatrbypc.createanalytical.project.PackageArchives
import java.nio.file.Path

// ジャーナル（transaction.json）から参照されない作業用のディレクトリ .createscript/txn-<id>/ の片付け（S8.2 L-3）。install.lock を取り、
// ジャーナルが無いときだけ行う（ジャーナルがあれば、そのトランザクションは Recovery が扱う）。
// インストールは、作業用のディレクトリの最初のファイルとして journal.tmp（ジャーナルの中身: 写しの SHA-256、パッケージの印の SHA-256）を書き、
// 各パッケージは印を最初に書いてから展開する。そのため、ジャーナルを公開する前に止まったものは、すべてのファイルを記録と照合できる。
// 消すのは、すべてを確かめてから（一部だけ消すことはしない）で、次のすべてを満たすものだけ:
// - 名前が txn-<UUID>。リンクでないディレクトリで、このユーザーのもの。中のディレクトリ・ファイルも、リンクでなく、このユーザーのもの
// - 空か、journal.tmp が正しいジャーナルで id がディレクトリの名前と同じ
// - ほかのファイルは、決まった名前の写し（SHA-256 が journal.tmp と同じ）と staging/<name>/<version>/ だけ。staging の中は、
//   journal.tmp の印の SHA-256 と同じ印と、印に書かれたファイル（SHA-256 が同じ。書く前に止まって足りないものはよい）だけ。印が無ければ空
// 満たさないもの（記録が無い・壊れている・書き換えられた・書きかけ・リンク・知らないファイル）は、何も消さずに残し、理由を知らせる。
// 取り消し・続行の片付けで消せなかったファイルが残ったもの（journal.tmp はもう無い）も、記録が無いので残す
internal class Leftovers(private val layout: Layout, private val owner: Owner, private val limits: InstallLimits) {
    private class Plan {
        // 消す順: パッケージのファイル → modules → 印 → パッケージのディレクトリ → 写し → journal.tmp → 作業用のディレクトリ
        // （途中で止まっても、残ったものを次の install が同じように照合できる順）
        val contents = ArrayList<Pair<Path, String>>()
        val markers = ArrayList<Pair<Path, String>>()
        val packageDirectories = ArrayList<Path>()
        val copies = ArrayList<Pair<Path, String>>()
        var journal: Pair<Path, String>? = null
    }

    private class Problem(val reason: String) : Exception(reason)

    private fun problem(reason: String): Nothing = throw Problem(reason)

    private val shownState = InstallLayout.DIRECTORY

    fun clean(): List<String> {
        if (SafeIo.exists(layout.journal)) return emptyList()
        val notes = ArrayList<String>()
        for (name in SafeIo.list(layout.state, 1024)) {
            if (!name.startsWith(InstallLayout.TRANSACTION_PREFIX)) continue
            val shown = "$shownState/$name"
            val txn = layout.state.resolve(name)
            val plan = try {
                plan(name, txn)
            } catch (problem: Problem) {
                notes += "$shown was not removed: ${problem.reason}. createscript does not use it; check it and remove it by hand"
                continue
            } catch (failure: InstallFailure) {
                notes += "$shown was not removed: ${failure.message}. createscript does not use it; check it and remove it by hand"
                continue
            }
            val kept = remove(txn, plan)
            notes += if (kept.isEmpty()) {
                "removed $shown, the prepared files of an installation that did not finish (no journal refers to it, and its files matched the record it began with)"
            } else {
                "$shown was not removed completely (${kept.joinToString("; ")}); check it and remove it by hand"
            }
        }
        return notes
    }

    // ---- 確認（何も消さない） ----

    private fun directory(path: Path, label: String) {
        val attributes = SafeIo.attributes(path) ?: problem("$label disappeared")
        if (attributes.isSymbolicLink) problem("$label is a symbolic link")
        if (!attributes.isDirectory) problem("$label is not a directory")
        if (!owner.owns(path)) problem("$label belongs to another user")
    }

    private fun file(path: Path, label: String, maxBytes: Int): ReadFile {
        val file = try {
            SafeIo.readRegular(path, maxBytes)
        } catch (failure: InstallFailure) {
            problem("$label is a symbolic link, not a regular file, or too large")
        } ?: problem("$label disappeared")
        if (!owner.owns(path)) problem("$label belongs to another user")
        return file
    }

    private fun plan(name: String, txn: Path): Plan {
        val id = name.removePrefix(InstallLayout.TRANSACTION_PREFIX)
        if (!UUID.matches(id)) problem("its name is not one that createscript install makes")
        directory(txn, "it")
        val plan = Plan()
        val entries = SafeIo.list(txn, 64)
        if (entries.isEmpty()) return plan
        if (Recovery.JOURNAL_TEMP !in entries) {
            problem(
                "it has no record of what it holds (${Recovery.JOURNAL_TEMP}); it may hold files that an earlier installation, finished or undone, " +
                    "could not confirm and so did not remove, or it was made by an earlier version of createscript",
            )
        }
        val journalFile = file(txn.resolve(Recovery.JOURNAL_TEMP), Recovery.JOURNAL_TEMP, Journal.MAX_BYTES)
        val journal = Journal.parse(journalFile.bytes, limits.maxPackages).first
            ?: problem("its ${Recovery.JOURNAL_TEMP} is incomplete or damaged (an installation may have stopped while writing it)")
        if (journal.id != id) problem("its ${Recovery.JOURNAL_TEMP} belongs to another installation")
        plan.journal = txn.resolve(Recovery.JOURNAL_TEMP) to journalFile.identity.sha256
        for (entry in entries) {
            val path = txn.resolve(entry)
            fun copy(sha256: String?, maxBytes: Int) {
                if (sha256 == null) problem("$entry was not made by this installation")
                val copy = file(path, entry, maxBytes)
                if (copy.identity.sha256 != sha256) problem("$entry does not match the record in ${Recovery.JOURNAL_TEMP} (it was changed, or written only in part)")
                plan.copies.add(path to sha256)
            }
            when (entry) {
                Recovery.JOURNAL_TEMP -> {}
                Recovery.MANIFEST_OLD -> copy(journal.manifestOld, Manifests.MAX_BYTES)
                Recovery.MANIFEST_NEW -> copy(journal.manifestNew, Manifests.MAX_BYTES)
                Recovery.LOCK_OLD -> copy(journal.lockOld, Lockfiles.MAX_BYTES)
                Recovery.LOCK_NEW -> copy(journal.lockNew, Lockfiles.MAX_BYTES)
                Recovery.STAGING -> staging(path, journal, plan)
                else -> problem("it has a file that createscript install does not make: $entry")
            }
        }
        return plan
    }

    private fun staging(staging: Path, journal: Journal, plan: Plan) {
        directory(staging, Recovery.STAGING)
        val byName = journal.packages.associateBy { it.name }
        for (name in SafeIo.list(staging, limits.maxPackages + 1)) {
            val p = byName[name] ?: problem("${Recovery.STAGING} holds $name, which is not in its record")
            val parent = staging.resolve(name)
            directory(parent, "${Recovery.STAGING}/$name")
            for (version in SafeIo.list(parent, 2)) {
                if (version != p.version) problem("${Recovery.STAGING} holds $name $version, which is not in its record")
                val label = "${Recovery.STAGING}/$name/$version"
                val dir = parent.resolve(version)
                directory(dir, label)
                packageDirectory(dir, label, p, plan)
                plan.packageDirectories.add(dir)
            }
            plan.packageDirectories.add(parent)
        }
        plan.packageDirectories.add(staging)
    }

    private fun packageDirectory(dir: Path, label: String, p: JournalPackage, plan: Plan) {
        val names = SafeIo.list(dir, PackageArchives.MAX_ENTRIES + 2)
        if (InstallLayout.MARKER !in names) {
            // 印を書く前に止まったもの（印は最初に書くので、ほかのファイルは無い）
            if (names.isNotEmpty()) problem("$label has files but no ${InstallLayout.MARKER}")
            return
        }
        val markerFile = file(dir.resolve(InstallLayout.MARKER), "$label/${InstallLayout.MARKER}", Marker.MAX_BYTES)
        if (markerFile.identity.sha256 != p.marker) problem("$label/${InstallLayout.MARKER} does not match the record in ${Recovery.JOURNAL_TEMP}")
        val marker = Marker.parse(markerFile.bytes) ?: problem("$label/${InstallLayout.MARKER} is damaged")
        fun content(path: Path, relative: String) {
            val expected = marker.files[relative] ?: problem("$label has a file that was not in the package: $relative" + PackageDirectories.sidecarHint(relative))
            val file = file(path, "$label/$relative", PackageArchives.MAX_TOTAL_BYTES)
            if (file.identity.sha256 != expected) problem("$label/$relative does not match ${InstallLayout.MARKER} (it was changed, or written only in part)")
            plan.contents.add(path to expected)
        }
        for (name in names) {
            if (name == InstallLayout.MARKER) continue
            val path = dir.resolve(name)
            if (name == "modules" && marker.files.keys.any { it.startsWith("modules/") }) {
                directory(path, "$label/modules")
                for (inner in SafeIo.list(path, PackageArchives.MAX_ENTRIES + 1)) content(path.resolve(inner), "modules/$inner")
                plan.packageDirectories.add(path)
                continue
            }
            content(path, name)
        }
        plan.markers.add(dir.resolve(InstallLayout.MARKER) to p.marker)
    }

    // ---- 削除（確かめたものだけ。消す直前にも、同じファイルであることを確かめる） ----

    private fun remove(txn: Path, plan: Plan): List<String> {
        val kept = ArrayList<String>()
        fun shown(path: Path) = layout.root.relativize(path).toString()
        fun delete(path: Path, sha256: String, maxBytes: Int) {
            SafeIo.deleteFileIfSame(path, sha256, maxBytes)?.let { kept += "${shown(path)} ($it)" }
        }
        for ((path, sha) in plan.contents) delete(path, sha, PackageArchives.MAX_TOTAL_BYTES)
        // modules → パッケージのディレクトリ → staging の順（深い方から）に、印を消してから空のディレクトリを消す
        if (kept.isEmpty()) for ((path, sha) in plan.markers) delete(path, sha, Marker.MAX_BYTES)
        if (kept.isEmpty()) {
            for (dir in plan.packageDirectories.sortedByDescending { it.nameCount }) SafeIo.deleteEmptyDirectory(dir)?.let { kept += "${shown(dir)} ($it)" }
        }
        if (kept.isEmpty()) for ((path, sha) in plan.copies) delete(path, sha, Lockfiles.MAX_BYTES.coerceAtLeast(Manifests.MAX_BYTES))
        if (kept.isEmpty()) plan.journal?.let { (path, sha) -> delete(path, sha, Journal.MAX_BYTES) }
        if (kept.isEmpty()) SafeIo.deleteEmptyDirectory(txn)?.let { kept += "${shown(txn)} ($it)" }
        SafeIo.syncDirectory(layout.state)
        return kept
    }

    companion object {
        private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
