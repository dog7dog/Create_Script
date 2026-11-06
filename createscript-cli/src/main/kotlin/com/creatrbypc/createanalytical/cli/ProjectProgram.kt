package com.creatrbypc.createanalytical.cli

import com.creatrbypc.createanalytical.install.VerifiedPackage
import com.creatrbypc.createanalytical.script.module.ModuleAccess
import com.creatrbypc.createanalytical.script.module.ModuleLookup
import com.creatrbypc.createanalytical.script.module.ModuleResolver
import java.nio.file.Paths

// createscript run のプロジェクトモードのモジュール（S8.2 Phase 4）。ローカルのモジュール（エントリーのファイルのディレクトリの 名前.cscr: 第8.0段階と同じ規則）と、
// 確かめたインストール済みのパッケージ（InstalledPackages.verify。ソースはメモリーの中のもの）から探す。ファイルの読み直し・通信はしない。
// - 名前の衝突: ローカルのモジュールと、どれかのパッケージのモジュール（公開しないものを含む）、または 2 つのパッケージに同じ名前があれば曖昧
//   （CS_MODULE_AMBIGUOUS）。Core は関数を「モジュール名.関数名」で区別するので、同じ名前の 2 つのモジュールを 1 つのプログラムに入れられない
// - import できる範囲（ModuleAccess）: 同じ持ち主（プロジェクトのローカルのモジュール同士・同じパッケージのモジュール同士）は、公開しないものも import できる。
//   ほかのパッケージのモジュールは、import するもの（プロジェクトかパッケージ）の直接の依存で、そのパッケージが公開している（modules に書いた）ものだけ。
//   推移的な依存・公開しないモジュール・パッケージからプロジェクトのローカルのモジュールへの import は CS_MODULE_NOT_VISIBLE
internal class ProjectProgram(
    private val local: ProjectModules,
    packages: List<VerifiedPackage>,
    private val directDependencies: Set<String>,
    private val shownRoot: String?,
) : ModuleResolver, ModuleAccess {
    private val byName = packages.associateBy { it.name }
    private val packageModules: Map<String, List<VerifiedPackage>> =
        packages.flatMap { p -> p.modules.keys.map { it to p } }.groupBy({ it.first }, { it.second })

    val entryModule: String? get() = local.entryModule

    // その名前のモジュールの持ち主（null はプロジェクト、それ以外はパッケージの name）。2 つ以上なら衝突
    private fun owners(module: String): List<String?> {
        val result = ArrayList<String?>()
        if (local.exists(module)) result.add(null)
        packageModules[module]?.forEach { result.add(it.name) }
        return result
    }

    override fun resolve(name: String): ModuleLookup {
        val inPackages = packageModules[name].orEmpty()
        if (inPackages.isEmpty()) return local.resolve(name)
        if (inPackages.size > 1 || local.exists(name)) return ModuleLookup.Ambiguous
        return ModuleLookup.Found(inPackages.single().modules.getValue(name))
    }

    override fun allows(importer: String?, module: String): Boolean {
        val targets = owners(module)
        // 見つからない・衝突するものは、resolver が CS_MODULE_NOT_FOUND・CS_MODULE_AMBIGUOUS として報告する
        if (targets.size != 1) return true
        val target = targets.single()
        val from: String? = if (importer == null) {
            null
        } else {
            // import を書いたモジュールは解決できたもの（持ち主は 1 つ）。持ち主の null はプロジェクトを表すので、singleOrNull では
            // 「1 つも無い・2 つ以上」と区別できない。数で分ける
            val importers = owners(importer)
            if (importers.size != 1) return false
            importers[0]
        }
        if (target == from) return true
        // パッケージは、プロジェクトのローカルのモジュールを import できない
        if (target == null) return false
        val direct = if (from == null) directDependencies else byName.getValue(from).dependencies.toSet()
        return target in direct && module in byName.getValue(target).publicModules
    }

    // 診断に表示するファイルの名前
    fun displayName(module: String): String {
        val owner = packageModules[module]?.singleOrNull()
        if (owner == null || local.exists(module)) return local.displayName(module)
        val path = "${owner.directory}/modules/$module${CliLimits.EXTENSION}"
        return shownRoot?.let { Paths.get(it, path).toString() } ?: path
    }
}
