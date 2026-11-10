package com.creatrbypc.createanalytical.install

import com.creatrbypc.createanalytical.project.Lockfile
import com.creatrbypc.createanalytical.project.Manifests
import com.creatrbypc.createanalytical.project.PackageArchives
import com.creatrbypc.createanalytical.project.PackageKind
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Path

// 確かめたインストール済みのパッケージ（S8.2 Phase 4）。dependencies は直接の依存の name、publicModules はマニフェストの modules（外から import できるもの）、
// modules はすべてのモジュール（公開しないものを含む）の名前 → ソース。directory はプロジェクトのルートからのパス
class VerifiedPackage(
    val name: String,
    val version: String,
    val source: String,
    val dependencies: List<String>,
    val publicModules: Set<String>,
    val modules: Map<String, String>,
    val directory: String,
)

// createscript run（プロジェクトモード）の前の確認（S8.2 Phase 4）。通信しない・ファイルを書かない。
// lock は lock check（Lockfiles.check）に通ったもの。ロックファイルの各パッケージについて、次のすべてを確かめる:
// - .createscript/packages/<name>/<version>/ が、リンクでないディレクトリで、印（.createscript-package.json）と中身が完全に一致する（足りない・余分・変わったファイルが無い）
// - 印の name・version・source・sha256 が、ロックファイルと同じ（sha256 は、インストールしたアーカイブのもの）
// - 確かめたファイル（印を除く）から作り直した正規形のアーカイブの SHA-256 が、ロックファイルの sha256 と同じ（S8.2 M-1。インストールは正規形のアーカイブだけを受け入れる）
// - パッケージの createscript.json が library で、name・version が同じ。依存の name・version・source が、ロックファイルの dependencies と、その各パッケージと同じ
// - .cscr が正しい UTF-8 で、ソースの上限以下。公開するモジュールのファイルがある
// 返すソースは、確かめたときに読んだバイト列から作る（確かめた後に差し替えられたファイルは使わない）。
// 印と中身を同じように書き換えたものは、作り直したアーカイブがロックファイルと合わないので使わない。
// 限界: 信頼の元はロックファイル。ロックファイルも合わせて書き換えたものは見分けられない（docs/packages.md）
object InstalledPackages {
    private fun invalid(message: String): Nothing = throw InstallFailure(InstallFailure.Kind.INVALID, message)

    fun verify(root: Path, lock: Lockfile): List<VerifiedPackage> {
        val realRoot = try {
            root.toRealPath()
        } catch (error: IOException) {
            throw InstallFailure(InstallFailure.Kind.NO_PROJECT, "not a directory: $root")
        }
        val layout = Layout(realRoot)
        if (SafeIo.exists(layout.journal)) invalid("an installation was interrupted (${InstallLayout.DIRECTORY}/${InstallLayout.JOURNAL} exists); run createscript install to finish or undo it")
        if (lock.packages.isEmpty()) return emptyList()
        // ほかのユーザーが、プロジェクトのディレクトリや .createscript を書き換えられないこと（S8.2 L-5 の案 C。install と同じ規則）
        val permissions = DirectoryPermissions(PermissionReader.DEFAULT, DirectoryPermissions.currentUser())
        (permissions.ancestorsProblem(realRoot) ?: permissions.treeProblem(layout.state))?.let { invalid("$it. The packages were not used") }
        fun notInstalled(what: String): Nothing = invalid("$what is not installed; run createscript install")
        if (!SafeIo.directory(layout.state, create = false)) notInstalled(lock.packages.first().let { "${it.name} ${it.version}" })
        if (!SafeIo.directory(layout.packages, create = false)) notInstalled(lock.packages.first().let { "${it.name} ${it.version}" })
        val owner = Owner.of(layout.state)
        val byName = lock.packages.associateBy { it.name }
        return lock.packages.map { entry ->
            val label = "${entry.name} ${entry.version}"
            if (!SafeIo.directory(layout.packages.resolve(entry.name), create = false)) notInstalled(label)
            val directory = layout.packageDirectory(entry.name, entry.version)
            val shown = realRoot.relativize(directory).toString()
            val state = when (val inspected = PackageDirectories.inspect(directory, owner, keepContents = true)) {
                PackageState.Absent -> notInstalled(label)
                is PackageState.Invalid -> invalid("$shown cannot be used (${inspected.reason}); remove it and run createscript install")
                is PackageState.Valid -> inspected
            }
            val marker = state.marker
            if (marker.name != entry.name || marker.version != entry.version || marker.source != entry.source || marker.sha256 != entry.sha256) {
                invalid("$shown was installed from another archive (${marker.source} ${marker.version}, SHA-256 ${marker.sha256}) than createscript.lock says; remove it and run createscript install")
            }
            // 確かめたファイル（印を除く）から正規形のアーカイブを作り直し、ロックファイルの SHA-256 と比べる（S8.2 M-1。印と中身を同じように書き換えたものも見つける）
            if (!PackageDirectories.matchesArchive(state, entry.sha256)) {
                invalid("$shown does not match the archive in createscript.lock (SHA-256 ${entry.sha256}); its files were changed after the installation. Remove it and run createscript install")
            }
            val manifest = Manifests.parse(state.contents.getValue(Manifests.FILE_NAME)).manifest
                ?: invalid("$shown/${Manifests.FILE_NAME} is not valid; remove the directory and run createscript install")
            if (manifest.kind != PackageKind.LIBRARY || manifest.name != entry.name || manifest.version != entry.version) {
                invalid("$shown/${Manifests.FILE_NAME} is not the library $label")
            }
            if (manifest.dependencies.map { it.name } != entry.dependencies) {
                invalid("$label depends on ${manifest.dependencies.map { it.name }}, but createscript.lock lists ${entry.dependencies}; run createscript install")
            }
            for (dependency in manifest.dependencies) {
                val locked = byName[dependency.name]
                if (locked == null || locked.version != dependency.version || locked.source != dependency.source) {
                    invalid("$label needs ${dependency.name} ${dependency.version} from ${dependency.source}, which does not match createscript.lock; run createscript install")
                }
            }
            val modules = java.util.TreeMap<String, String>()
            for ((path, bytes) in state.contents) {
                if (!path.startsWith("modules/") || !path.endsWith(".cscr")) continue
                PackageArchives.sourceProblem(bytes)?.let { invalid("$shown/$path cannot be used ($it)") }
                modules[path.removePrefix("modules/").removeSuffix(".cscr")] = decode(bytes) ?: invalid("$shown/$path is not valid UTF-8")
            }
            manifest.modules.firstOrNull { it !in modules }?.let { invalid("$shown has no modules/$it.cscr") }
            VerifiedPackage(entry.name, entry.version, entry.source, entry.dependencies, manifest.modules.toSet(), modules, shown)
        }
    }

    private fun decode(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (error: CharacterCodingException) {
        null
    }
}
