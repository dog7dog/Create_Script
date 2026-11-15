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
// - 書きかけのファイル（S8.2 N-3: どのファイルも .part-… の名前で書き終えてから最終の名前を付けるので、最終の名前のファイルは必ず書き終えたもの）は、
//   このユーザーの、リンクでない通常のファイルで、空の .part-<UUID>（名前を付ける前に止まったもの）か、.part-<inode>-<大きさ>-<最終の名前> のうち
//   inode が名前と同じ（作ったファイルそのもの）・大きさが名前の大きさ以下・最終の名前が記録にあり、そのファイルはまだ無いものだけ。
//   記録（journal.tmp）を書いている途中で止まったもの（journal.tmp が無く、journal.tmp の書きかけと空のものだけがある）も、同じ確認で片付ける
// 満たさないもの（記録が無い・壊れている・書き換えられた・確かめられない書きかけ・リンク・知らないファイル）は、何も消さずに残し、理由を知らせる。
// 取り消し・続行の片付けで消せなかったファイルが残ったもの（journal.tmp はもう無い）も、記録が無いので残す
internal class Leftovers(private val layout: Layout, private val owner: Owner, private val limits: InstallLimits) {
    private class Plan {
        // 消す順: パッケージのファイル → modules → 印 → パッケージのディレクトリ → 写し → journal.tmp → 作業用のディレクトリ
        // （途中で止まっても、残ったものを次の install が同じように照合できる順）
        val contents = ArrayList<Planned>()
        val markers = ArrayList<Planned>()
        val packageDirectories = ArrayList<Path>()
        val copies = ArrayList<Planned>()
        var journal: Planned? = null
    }

    // 消すもの: パス・確かめたときの SHA-256 と fileKey（消す直前に、同じファイルであることを両方で確かめる。S8.2 N-3 の対策 B）
    private class Planned(val path: Path, val sha256: String, val fileKey: Any?)

    private class Problem(val reason: String) : Exception(reason)

    private fun problem(reason: String): Nothing = throw Problem(reason)

    private val shownState = InstallLayout.DIRECTORY

    fun clean(): List<String> {
        if (SafeIo.exists(layout.journal)) return emptyList()
        // .createscript がリンクに差し替えられていれば、何もしない（S8.2 L-5）
        if (SafeIo.chain(layout.root, layout.state) != SafeIo.Chain.Real) return emptyList()
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
            // 消す直前に、作業用のディレクトリが今もプロジェクトの中の実際のディレクトリであること（S8.2 L-5）
            val chain = SafeIo.chain(layout.root, txn)
            if (chain is SafeIo.Chain.NotReal) {
                notes += "$shown was not removed: ${chain.reason}. createscript does not use it; check it and remove it by hand"
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
        // 記録を書いている途中で止まったもの: journal.tmp の書きかけと空のものだけ
        if (Recovery.JOURNAL_TEMP !in entries && entries.all { it.startsWith(SafeIo.PART_PREFIX) }) {
            for (entry in entries) plan.copies.add(part(txn, entry, "it", setOf(Recovery.JOURNAL_TEMP), entries))
            return plan
        }
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
        plan.journal = Planned(txn.resolve(Recovery.JOURNAL_TEMP), journalFile.identity.sha256, journalFile.identity.fileKey)
        for (entry in entries) {
            val path = txn.resolve(entry)
            fun copy(sha256: String?, maxBytes: Int) {
                if (sha256 == null) problem("$entry was not made by this installation")
                val copy = file(path, entry, maxBytes)
                if (copy.identity.sha256 != sha256) problem("$entry does not match the record in ${Recovery.JOURNAL_TEMP} (it was changed, or written only in part)")
                plan.copies.add(Planned(path, sha256, copy.identity.fileKey))
            }
            if (entry.startsWith(SafeIo.PART_PREFIX)) {
                val expected = setOfNotNull(Recovery.MANIFEST_OLD, Recovery.MANIFEST_NEW, journal.lockOld?.let { Recovery.LOCK_OLD }, journal.lockNew?.let { Recovery.LOCK_NEW })
                plan.copies.add(part(txn, entry, "it", expected, entries))
                continue
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
            // 印を書き終える前に止まったもの（印は最初に書くので、ほかのファイルは印の書きかけだけ）
            if (names.any { !it.startsWith(SafeIo.PART_PREFIX) }) problem("$label has files but no ${InstallLayout.MARKER}")
            for (name in names) plan.contents.add(part(dir, name, label, setOf(InstallLayout.MARKER), names))
            return
        }
        val markerFile = file(dir.resolve(InstallLayout.MARKER), "$label/${InstallLayout.MARKER}", Marker.MAX_BYTES)
        if (markerFile.identity.sha256 != p.marker) problem("$label/${InstallLayout.MARKER} does not match the record in ${Recovery.JOURNAL_TEMP}")
        val marker = Marker.parse(markerFile.bytes) ?: problem("$label/${InstallLayout.MARKER} is damaged")
        fun content(path: Path, relative: String) {
            val expected = marker.files[relative] ?: problem("$label has a file that was not in the package: $relative" + PackageDirectories.sidecarHint(relative))
            val file = file(path, "$label/$relative", PackageArchives.MAX_TOTAL_BYTES)
            if (file.identity.sha256 != expected) problem("$label/$relative does not match ${InstallLayout.MARKER} (it was changed, or written only in part)")
            plan.contents.add(Planned(path, expected, file.identity.fileKey))
        }
        for (name in names) {
            if (name == InstallLayout.MARKER) continue
            val path = dir.resolve(name)
            if (name.startsWith(SafeIo.PART_PREFIX)) {
                plan.contents.add(part(dir, name, label, marker.files.keys.filter { '/' !in it }.toSet(), names))
                continue
            }
            if (name == "modules" && marker.files.keys.any { it.startsWith("modules/") }) {
                directory(path, "$label/modules")
                val inner = SafeIo.list(path, PackageArchives.MAX_ENTRIES + 1)
                val expected = marker.files.keys.filter { it.startsWith("modules/") }.map { it.removePrefix("modules/") }.toSet()
                for (file in inner) {
                    if (file.startsWith(SafeIo.PART_PREFIX)) plan.contents.add(part(path, file, "$label/modules", expected, inner)) else content(path.resolve(file), "modules/$file")
                }
                plan.packageDirectories.add(path)
                continue
            }
            content(path, name)
        }
        plan.markers.add(Planned(dir.resolve(InstallLayout.MARKER), p.marker, markerFile.identity.fileKey))
    }

    // 書きかけのファイル（S8.2 N-3）を確かめる。片付けてよければ、そのパスと今の中身の SHA-256（消す直前にも同じことを確かめる）
    private fun part(dir: Path, entry: String, label: String, expected: Set<String>, names: Collection<String>): Planned {
        val path = dir.resolve(entry)
        val shown = "$label/$entry"
        val attributes = SafeIo.attributes(path) ?: problem("$shown disappeared")
        if (attributes.isSymbolicLink || !attributes.isRegularFile) problem("$shown is a symbolic link or not a regular file")
        if (!owner.owns(path)) problem("$shown belongs to another user")
        if (SafeIo.isCreatingPart(entry)) {
            // 名前を付ける前に止まったもの（中身は無い）
            if (attributes.size() != 0L) problem("$shown is not empty, so it is not a file that createscript was about to write")
        } else {
            val part = SafeIo.part(entry) ?: problem("$label has a file that createscript install does not make: $entry")
            if (part.finalName !in expected) problem("$shown is for ${part.finalName}, which is not in its record")
            if (part.finalName in names) problem("both $shown and ${part.finalName} exist")
            if (SafeIo.inode(path) != part.inode) problem("$shown was replaced by another file (it is not the file that createscript was writing)")
            if (attributes.size() > part.size) problem("$shown is larger than the file it was being written for (it was changed)")
        }
        // 書いている間は 0400（S8.2 N-3 の対策 A）。違うものは、権限を変えて書き換えたものかもしれない（または、以前の版が書いたもの）
        if (!SafeIo.isReadOnlyPart(path)) problem("$shown is not read-only (createscript keeps a file it is writing read-only), so it may have been changed")
        if (attributes.size() > PackageArchives.MAX_TOTAL_BYTES) problem("$shown is too large")
        val read = file(path, shown, PackageArchives.MAX_TOTAL_BYTES)
        return Planned(path, read.identity.sha256, read.identity.fileKey)
    }

    // ---- 削除（確かめたものだけ。消す直前にも、同じファイルであることを確かめる） ----

    private fun remove(txn: Path, plan: Plan): List<String> {
        val kept = ArrayList<String>()
        fun shown(path: Path) = layout.root.relativize(path).toString()
        fun delete(planned: Planned, maxBytes: Int) {
            SafeIo.deleteFileIfSame(planned.path, planned.sha256, maxBytes, planned.fileKey)?.let { kept += "${shown(planned.path)} ($it)" }
        }
        for (planned in plan.contents) delete(planned, PackageArchives.MAX_TOTAL_BYTES)
        // modules → パッケージのディレクトリ → staging の順（深い方から）に、印を消してから空のディレクトリを消す
        if (kept.isEmpty()) for (planned in plan.markers) delete(planned, Marker.MAX_BYTES)
        if (kept.isEmpty()) {
            for (dir in plan.packageDirectories.sortedByDescending { it.nameCount }) SafeIo.deleteEmptyDirectory(dir)?.let { kept += "${shown(dir)} ($it)" }
        }
        if (kept.isEmpty()) for (planned in plan.copies) delete(planned, PackageArchives.MAX_TOTAL_BYTES)
        if (kept.isEmpty()) plan.journal?.let { delete(it, Journal.MAX_BYTES) }
        if (kept.isEmpty()) SafeIo.deleteEmptyDirectory(txn)?.let { kept += "${shown(txn)} ($it)" }
        SafeIo.syncDirectory(layout.state)
        return kept
    }

    companion object {
        private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
