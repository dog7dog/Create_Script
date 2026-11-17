# CreateScript の文書

> **原文（英語）**：[docs/README.md](../README.md)（コミット `4a2192b` の時点。A5b で更新した内容を含む）。内容が食い違うときは英語版が正です。

文書は、読む人ごとにまとめています。利用者向けと組み込み向けの文書は英語で書き、日本語版を `ja/` に置いています（この索引が日本語版の入口です。英語の索引は [docs/README.md](../README.md) です）。
設計と運用の文書は日本語で書いてあり、翻訳はしていません。

> [!TIP]
> どこから読むか迷ったら：CreateScript で **プログラムを書く人** は「利用者向け」の [language.md](language.md) から、アプリケーションに **組み込む人** は
> 「組み込み・ツール向け」の [api/embedding.md](api/embedding.md) から読むと早いです。

## 利用者向け

| 文書 | 内容 |
|---|---|
| [language.md](language.md) | CreateScript 言語：値、制御構文、関数、モジュール、クラス、リスト、ラムダ、`\|>`、`?!`、数学、tick、上限 |
| [cli.md](cli.md) | `createscript` コマンド：ファイルとプロジェクトの実行、REPL と行編集、Java を同梱したパッケージの作り方 |
| [samples.md](samples.md) | サンプルのプログラム |
| [packages.md](packages.md) | プロジェクトのファイル（`createscript.json`、`createscript.lock`）、パッケージのアーカイブ、パッケージのダウンロードとインストール |
| [registry.md](registry.md) | 計画中の CreateScript-Registry：構成、index、署名、運営、クライアント（未公開） |
| [../../createscript-cli/README.md](../../createscript-cli/README.md) | コマンドの詳しい説明書（日本語） |

## 組み込み・ツール向け（API）

| 文書 | 内容 |
|---|---|
| [api/embedding.md](api/embedding.md) | Core の組み込み：コンパイル、実行、上限、モジュール、保存と再開、スレッド |
| [api/project-files.md](api/project-files.md) | `createscript-project` でのプロジェクトのファイルの読み取りと検査 |
| [api/api-surface.md](api/api-surface.md) | どの宣言が安定 API・内部 API・モジュール内専用か、API の一覧の仕組み |
| [api/compatibility.md](api/compatibility.md) | 互換性の方針と、その確かめ方 |

## 設計

| 文書 | 内容 |
|---|---|
| [design/registry-github-architecture.md](../design/registry-github-architecture.md) | CreateScript-Registry の GitHub の構成（2 つの App、タグの ruleset）。日本語 |

## 運用

| 文書 | 内容 |
|---|---|
| [operations/registry-production-setup.md](../operations/registry-production-setup.md) | 本番のレジストリの構築。日本語 |
| [operations/registry-settings-key.md](../operations/registry-settings-key.md) | ruleset のスナップショットに署名する設定の鍵。日本語 |
| [operations/registry-github-test-environment-setup.md](../operations/registry-github-test-environment-setup.md) | GitHub のテスト環境の構築。日本語 |
| [operations/registry-github-integration-test.md](../operations/registry-github-integration-test.md) | GitHub の統合試験の手順。日本語 |
| [operations/registry-github-execution-plan.md](../operations/registry-github-execution-plan.md) | GitHub の統合の操作の実行計画。日本語 |

## 記録

[audits/](../audits/) には、段階 S8.3 のセキュリティ監査と評価の報告があります。これらは記録です：文書を移しても更新しないので、書いた時点で有効だった
パスを挙げています（下を参照）。[CHANGELOG.md](../../CHANGELOG.md) も同じように扱います。

## 移した文書

段階 A5a（2026-11-15）で、次の文書を移しました。それより前の記録、コミットメッセージ、監査は、古いパスを使っています。

| 古いパス | 新しいパス |
|---|---|
| `docs/registry-github-architecture.md` | [`docs/design/registry-github-architecture.md`](../design/registry-github-architecture.md) |
| `docs/registry-production-setup.md` | [`docs/operations/registry-production-setup.md`](../operations/registry-production-setup.md) |
| `docs/registry-settings-key.md` | [`docs/operations/registry-settings-key.md`](../operations/registry-settings-key.md)（`createscript-registry-tool` のメッセージがこのパスを示すので、古いパスには、そこを案内する短いページを残しています） |
| `docs/registry-github-test-environment-setup.md` | [`docs/operations/registry-github-test-environment-setup.md`](../operations/registry-github-test-environment-setup.md) |
| `docs/registry-github-integration-test.md` | [`docs/operations/registry-github-integration-test.md`](../operations/registry-github-integration-test.md) |
| `docs/registry-github-execution-plan.md` | [`docs/operations/registry-github-execution-plan.md`](../operations/registry-github-execution-plan.md) |
| `README.md` の「Using the Core library」の詳細 | [`docs/api/embedding.md`](api/embedding.md)（README には短い例を残しています） |
| `docs/api/api-surface.md` の「Compatibility rules」 | [`docs/api/compatibility.md`](api/compatibility.md) |

## 日本語版と、英語版との合わせ方

段階 A5b で、利用者向けと API の文書の日本語版を `docs/ja/` に加えました。ファイル名は同じで（`docs/ja/language.md`、…、`docs/ja/api/api-surface.md`）、
索引がこの `docs/ja/README.md` です（英語の索引は [docs/README.md](../README.md)）。正は英語の文書です。どの日本語の文書も、先頭に、従っている英語の文書のパスとコミットを書いています。
日本語の文書は、注記・箇条書き・`<details>`・自分だけの表で、原文より詳しく説明してかまいません。何かを省くことはしません。

照合の処理はリポジトリにあり、新しい依存は要りません：`test-support/docs-check/`（`test-support/api-listing/` と同じ形）を、`createscript-cli` の
`DocsTranslationTest` が実行します。そのため、`./gradlew build` と CI で動きます。英語の文書とその日本語版の組（と、`README.md` と `README.ja.md`）ごとに、
次のことを確かめます。

- 見出しの階層の並びが同じ。
- コードブロックが、同じ順に、一字一句同じ。ただし、翻訳してよいコメント（`bash` のブロックの `#` のコメント、`kotlin` のブロックの `//` のコメント）は除く。
- 表が同じで、行の数も同じ（日本語の文書にだけある表は、直前に `<!-- ja-only -->` の行を書いて印を付け、比べない）。
- リンク先の集合が同じ：日本語版のある文書へのリンクはその日本語版を指し、アンカーは同じ位置の見出しを指す。
- `CS_...` の ID が同じで、英語の本文のどの数も日本語の本文にある。
- 日本語の文書の先頭の、原文を示す行。

さらに、リポジトリの Markdown のファイルの、すべての相対リンクとアンカーが、存在するファイルと見出しに届くことを確かめます（`docs/audits/` と
`CHANGELOG.md` の記録は古いパスを持つので、報告するだけで失敗にはしません）。訳文が同じことを言っているかは、人が確かめます。照合の処理では分かりません。

<!-- ja-only -->
| 英語 | この文書群での訳語 |
|---|---|
| Stable (API) | 安定 API |
| Internal API | 内部 API |
| Module-private | モジュール内専用 |
| opt-in | オプトイン |
| saved state | 保存状態 |
| host | ホスト |
| diagnostic | 診断 |
| tick | tick |
| manifest / lock file | マニフェスト / ロックファイル |
| package archive | パッケージのアーカイブ |
| yank / remove | 取り下げ / 取り除き |
