package com.creatrbypc.createanalytical.registrytool

import com.creatrbypc.createanalytical.install.GitHubReleases
import java.time.Instant
import kotlin.system.exitProcess

// テスト専用の registry-tool（S8.3 Phase 10A、W7）。GitHub の実環境のテストで、テスト用のレジストリのリポジトリが、テスト用の鍵
// （test-only-）で署名した index を公開するために使う。本番の main との違いは、publish-check がテスト用の鍵を受け入れることだけ。
// - テストのソース（src/test）にだけあり、本番の registry-tool の配布物（installDist）には入らない。作るのは
//   `./gradlew :createscript-registry-tool:testRegistryTool` を明示したときだけ（build/test-registry-tool/、起動スクリプトの名前は
//   createscript-registry-tool-TEST-ONLY）。テスト用のリポジトリのワークフローだけが、このビルドを使う
// - 実行のたびに、テスト用のビルドであることを標準エラーに出す（標準出力の行の形は本番と同じ）
object TestRegistryTool {
    const val BANNER = "createscript-registry-tool TEST-ONLY BUILD (accepts test-only- keys; not for the official registry)"

    // repository は GitHub Actions の GITHUB_REPOSITORY（公式のレジストリのリポジトリでは動かない）
    fun run(args: List<String>, repository: String?, err: java.io.PrintStream, tool: (Boolean) -> RegistryTool): Int {
        err.println(BANNER)
        if (repository != null && repository.equals(com.creatrbypc.createanalytical.install.RegistryTrust.OFFICIAL_REPOSITORY, ignoreCase = true)) {
            err.println("createscript-registry-tool-TEST-ONLY: refusing to run in the official registry repository")
            return 2
        }
        return tool(true).run(args)
    }
}

fun main(args: Array<String>) {
    exitProcess(TestRegistryTool.run(args.toList(), System.getenv("GITHUB_REPOSITORY"), System.err) { allow -> RegistryTool(System.out, System.err, { Instant.now() }, { LiveGitHub(GitHubReleases()) }, allowTestKeys = allow) })
}
