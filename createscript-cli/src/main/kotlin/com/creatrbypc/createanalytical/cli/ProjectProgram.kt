package com.creatrbypc.createanalytical.cli

import com.creatrbypc.createanalytical.install.VerifiedPackage
import com.creatrbypc.createanalytical.script.module.ModuleLookup
import com.creatrbypc.createanalytical.script.module.ScopedModuleLookup
import com.creatrbypc.createanalytical.script.module.ScopedModuleResolver
import java.nio.file.Paths

// createscript run のプロジェクトモードのモジュール（S8.2 Phase 4。名前空間は S8.2 の H-1）。ローカルのモジュール（エントリーのファイルのディレクトリの
// 名前.cscr: 第8.0段階と同じ規則）と、確かめたインストール済みのパッケージ（InstalledPackages.verify。ソースはメモリーの中のもの）から、import 元ごとに探す。
// ファイルの読み直し・通信はしない。
// - モジュールの ID: ローカルのモジュールは名前のまま、パッケージのモジュールは "パッケージ名/モジュール名"（パッケージ名は英小文字・数字・"-"、
//   モジュール名は識別子なので、ローカルの名前とも、ほかのパッケージとも重ならない）。Core は関数を "ID.関数名" にするため、別のパッケージの
//   同じ名前のモジュールが 1 つのプログラムに共存できる
// - import 元から見えるもの（スコープ）:
//   プロジェクト（エントリーとローカルのモジュール）: ローカルのモジュールと、直接の依存の公開モジュール
//   パッケージ: 自分のモジュール（公開しないものを含む）と、自分の直接の依存の公開モジュール
// - 同じスコープに同じ名前が 2 つ以上あるときだけ曖昧（CS_MODULE_AMBIGUOUS。ローカルのモジュールは、大文字・小文字だけが違うファイルも同じ名前とみなす）。
//   スコープに無く、ほかの場所（推移的な依存・公開しないモジュール・パッケージから見たプロジェクトのローカルのモジュール）にあるものは CS_MODULE_NOT_VISIBLE
internal class ProjectProgram(
    private val local: ProjectModules,
    packages: List<VerifiedPackage>,
    private val directDependencies: Set<String>,
    private val shownRoot: String?,
) : ScopedModuleResolver {
    private val byName = packages.associateBy { it.name }

    // ID の持ち主のパッケージ（"/" の前）。ローカルのモジュールとエントリー（null）は null
    private fun packageOf(id: String?): VerifiedPackage? = id?.substringBefore('/', "")?.ifEmpty { null }?.let { byName.getValue(it) }

    // エントリーのモジュールの ID（ファイル名が識別子でない・ID として長すぎるなら null。import の名前は識別子 32 文字までなので、循環の確認には影響しない）
    val entryModule: String? get() = local.entryModule?.takeIf(ScopedModuleLookup::isValidId)

    override fun resolve(importer: String?, name: String): ScopedModuleLookup {
        val from = packageOf(importer)
        // スコープの中のパッケージのモジュール（パッケージからなら自分のもの、それと直接の依存の公開モジュール）。ID の順
        val candidates = ArrayList<Pair<String, String>>()
        if (from != null) from.modules[name]?.let { candidates.add(id(from, name) to it) }
        for (dependency in (from?.dependencies ?: directDependencies).sorted()) {
            val target = byName.getValue(dependency)
            if (name in target.publicModules) candidates.add(id(target, name) to target.modules.getValue(name))
        }
        if (from == null) {
            // プロジェクトから: ローカルのモジュールも同じスコープ
            if (candidates.isEmpty()) {
                return when (val lookup = local.resolve(name)) {
                    is ModuleLookup.Found -> ScopedModuleLookup.Found(name, lookup.source)
                    ModuleLookup.NotFound -> if (existsElsewhere(name)) ScopedModuleLookup.NotVisible else ScopedModuleLookup.Failed(lookup)
                    else -> ScopedModuleLookup.Failed(lookup)
                }
            }
            if (local.exists(name)) return ScopedModuleLookup.Failed(ModuleLookup.Ambiguous)
        }
        return when (candidates.size) {
            0 -> if (existsElsewhere(name)) ScopedModuleLookup.NotVisible else ScopedModuleLookup.Failed(ModuleLookup.NotFound)
            1 -> ScopedModuleLookup.Found(candidates[0].first, candidates[0].second)
            else -> ScopedModuleLookup.Failed(ModuleLookup.Ambiguous)
        }
    }

    // スコープの外にある（import できないが、どこかに存在する）か
    private fun existsElsewhere(name: String): Boolean = local.exists(name) || byName.values.any { name in it.modules }

    private fun id(p: VerifiedPackage, module: String) = "${p.name}/$module"

    // 診断に表示するファイルの名前（module はモジュールの ID）
    fun displayName(module: String): String {
        val owner = packageOf(module) ?: return local.displayName(module)
        val path = "${owner.directory}/modules/${module.substringAfter('/')}${CliLimits.EXTENSION}"
        return shownRoot?.let { Paths.get(it, path).toString() } ?: path
    }
}
