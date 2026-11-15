package com.creatrbypc.createanalytical.install

import com.creatrbypc.createanalytical.project.ProjectRules
import java.nio.file.Path

// 置き場所 .createscript/packages/<name>/<version>/ のうち、使えないものを探して知らせる（S8.2 L-4）。install の始めに毎回行う。
// 読むだけで、何も消さず・直さず・書かない。リンクはたどらない（リンクそのものを知らせ、先は読まない）。
// 使えるもの（中身が印と完全に一致し、印の name・version が置き場所と同じで、中身から作り直した正規形のアーカイブの SHA-256 が印と同じもの）は、
// ロックファイルに無い古い版でも知らせない（使われないだけで害は無い）。. で始まる名前（.DS_Store など）は見ない。
// 確かめる途中の失敗（読めない・一覧を取れない）も、知らせるだけで、インストールを失敗させない。
// このインストールが使うパッケージの置き場所は、後でインストールが確かめる（使えなければ、触らずに止まる）
internal class UnusablePackages(private val layout: Layout, private val owner: Owner) {
    companion object {
        // 確かめるパッケージのディレクトリの数の上限（それを超えたら、確かめなかったことを知らせる）
        const val MAX_DIRECTORIES = 256
        private const val ADVICE = "createscript does not use it and did not change it. If you edited files there, keep a copy of your changes; then remove the directory"
    }

    private fun shown(path: Path) = layout.root.relativize(path).toString()

    fun scan(): List<String> {
        val notes = ArrayList<String>()
        try {
            scanInto(notes)
        } catch (failure: InstallFailure) {
            notes += "${shown(layout.packages)} could not be checked completely (${failure.message}); this installation does not depend on it"
        }
        return notes
    }

    private fun scanInto(notes: MutableList<String>) {
        val packages = layout.packages
        val attributes = SafeIo.attributes(packages) ?: return
        if (attributes.isSymbolicLink) {
            notes += "${shown(packages)} is a symbolic link; it was not followed. $ADVICE (the link, not what it points to)"
            return
        }
        if (!attributes.isDirectory) {
            notes += "${shown(packages)} is not a directory. $ADVICE"
            return
        }
        // 途中のディレクトリがリンクに差し替えられていれば、先を読まない（S8.2 L-5）
        if (SafeIo.chain(layout.root, packages) != SafeIo.Chain.Real) return
        var checked = 0
        for (name in SafeIo.list(packages, 1024)) {
            if (name.startsWith(".")) continue
            val parent = packages.resolve(name)
            val parentAttributes = SafeIo.attributes(parent) ?: continue
            when {
                parentAttributes.isSymbolicLink -> {
                    notes += "${shown(parent)} is a symbolic link; it was not followed. $ADVICE (the link, not what it points to)"
                    continue
                }
                !parentAttributes.isDirectory -> {
                    notes += "${shown(parent)} is not a package directory. $ADVICE"
                    continue
                }
                !ProjectRules.isValidName(name) -> {
                    notes += "${shown(parent)} is not a package directory made by createscript install ('$name' is not a valid package name). $ADVICE"
                    continue
                }
            }
            for (version in SafeIo.list(parent, 1024)) {
                if (version.startsWith(".")) continue
                val directory = parent.resolve(version)
                if (!ProjectRules.isValidVersion(version)) {
                    notes += "${shown(directory)} is not a package directory made by createscript install ('$version' is not a valid version). $ADVICE"
                    continue
                }
                if (++checked > MAX_DIRECTORIES) {
                    notes += "${shown(packages)} holds more than $MAX_DIRECTORIES package directories; the others were not checked"
                    return
                }
                problem(directory, name, version)?.let { notes += "${shown(directory)} cannot be used ($it). $ADVICE and run createscript install again if a project needs $name $version" }
            }
        }
    }

    // 使えない理由（使えれば null）。inspect は、リンク・ディレクトリでないものを、読まずに Invalid にする
    private fun problem(directory: Path, name: String, version: String): String? {
        if (SafeIo.chain(layout.root, directory.parent) != SafeIo.Chain.Real) return "a directory on the way is a symbolic link; it was not followed"
        return when (val state = PackageDirectories.inspect(directory, owner, keepContents = true)) {
            PackageState.Absent -> null
            is PackageState.Invalid -> state.reason
            is PackageState.Valid -> when {
                state.marker.name != name || state.marker.version != version -> "its ${InstallLayout.MARKER} names ${state.marker.name} ${state.marker.version}"
                // 印と中身を同じように書き換えたもの（S8.2 M-1 と同じ確認）
                !PackageDirectories.matchesArchive(state, state.marker.sha256) -> "its files do not make the archive named in ${InstallLayout.MARKER} (SHA-256 ${state.marker.sha256}); they were changed after the installation"
                else -> null
            }
        }
    }
}
