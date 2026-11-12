# CreateScript-Registry の GitHub の構成（設計）

> **状態（2026-11-12、S8.3 Phase 10A）：Phase 9 で設計として採用し、Phase 10A で雛形とツールに実装しました。GitHub 上での検証は、まだです。**
>
> - 公開専用の GitHub App（Registry Publisher App）を使う方式を、設計の方針として採用しました。
> - 雛形の `publish.yml` の `release` ジョブは、Publisher App のトークンだけで公開します（W1）。ruleset は A・B の 2 つのファイルです（W2）。`check-settings`・`check-live-rulesets` がそれらを検査します（W3）。テスト専用のビルドもあります（W7）。詳細は `docs/audits/S8.3-Phase10A-Local-Implementation.md`。
> - どれも、ローカルの模擬の試験だけで確かめています。
> - この文書の GitHub の動作に関する記述は、すべて GitHub の文書の理解に基づく **前提** です。前提は 7 章に一覧し、`docs/registry-github-integration-test.md` で検証します。
>   - **特に、ruleset の回避者（bypass actor）の設定がタグの作成にどう作用するかは、未検証です。**
>
> 関連：
> - `registry-template/GOVERNANCE.md`・`SIGNING.md`・`SECURITY.md`
> - `docs/registry-production-setup.md`、`docs/registry.md`
> - `docs/audits/S8.3-Phase4〜9`

## 1. Registry Checks App と Registry Publisher App の責務

| | Registry Checks App | Registry Publisher App |
|---|---|---|
| 目的 | 必須チェック `registry/validate`・`registry/approvals` を、PR の head のコミットに付ける | 承認され、署名された index の Release（`index-<serial>`）を公開する |
| 使うジョブ | `validate.yml` の `report`（PR のデータを扱わない） | `publish.yml` の `release`（ビルドもツールの実行もしない） |
| できること | check run の作成 | Release の作成（下書き）・確認・公開、`index-*` のタグの作成（ruleset A の唯一の回避者） |
| できないこと（設計の目標） | Release・タグ・コンテンツの変更。PR の承認 | 必須チェックの作成。PR の承認。`main` への push（ブランチの ruleset に回避者が無い）。作成済みの `index-*` の更新・削除（ruleset B に回避者が無い） |
| 署名鍵 | 触れない | **触れない**（本番の Ed25519 の秘密鍵は、署名者の手元のオフラインの機械だけにある） |
| 秘密鍵の置き場所 | environment `registry-checks` の secret `REGISTRY_CHECKS_APP_KEY` | environment `publish` の secret `REGISTRY_PUBLISHER_APP_KEY` |
| ID の置き場所 | リポジトリの変数 `REGISTRY_CHECKS_APP_ID`、`main` の ruleset の `integration_id` | リポジトリの変数 `REGISTRY_PUBLISHER_APP_ID`、ruleset A の `bypass_actors` |

2 つを分ける理由：
- どちらかの鍵が漏れても、もう一方の役割は奪えない。
  - Checks App の鍵では公開できない。
  - Publisher App の鍵では、必須チェックを偽装できない。
- 2 つの鍵は、別々の environment に置く。

## 2. 権限の一覧

### GitHub App

| App | リポジトリの権限 | そのほか | インストール先 | トークンの限定（ワークフローが要求する） |
|---|---|---|---|---|
| Registry Checks App | Checks: read and write（Metadata: read は GitHub が必ず付ける） | なし。webhook なし。ユーザーの認可なし | レジストリのリポジトリだけ | `{"repositories": ["<名前>"], "permissions": {"checks": "write"}}`。使った後に失効させる（実装済み） |
| Registry Publisher App | **Contents: read and write**（Metadata: read） | 同上 | レジストリのリポジトリだけ | `{"repositories": ["<名前>"], "permissions": {"contents": "write"}}`。使った後に失効させる（計画） |

Contents: write は、Release とタグの操作に必要な最小限の権限と考えている（前提 P-12）。この権限には、ブランチへの push も含まれる。そのため、`main` への push は、ブランチの ruleset（回避者なし）で止める。ほかのブランチへの push は止まらないが、`main` に入るには PR と 2 人の承認が必要。

### ワークフローの `GITHUB_TOKEN`（ジョブごと）

| ワークフロー / ジョブ | 今 | 移行の後 |
|---|---|---|
| `validate` / `validate`・`approvals` | `contents: read`、`pull-requests: read` | 同じ |
| `validate` / `report` | `pull-requests: read` | 同じ |
| `publish` / `check` | `contents: read`、`pull-requests: read` | 同じ |
| `publish` / `release` | Phase 9 まで：`contents: write`（environment `publish`） | **Phase 10A から：`contents: read`**。書き込みは Publisher App のトークンだけ（`GITHUB_TOKEN` への切り替えは無い） |
| `publish` / `verify` | `contents: read` | 同じ |

### 人

| 役割 | GitHub の権限 | 備考 |
|---|---|---|
| 運営責任者 | admin | ruleset を変えられる（防げない。変更は事前に issue で告知する） |
| メンテナー | write | `main` へは PR だけ。タグ `index-*` の作成は ruleset A で拒否（前提 P-4）。別の名前の Release・`latest` の変更は防げない |
| `publish` environment の承認者 | メンテナー | 自分の承認は禁止（prevent self-review） |
| 署名者 | — | オフラインで署名する。GitHub には秘密鍵を置かない |

## 3. ruleset の構成

| ruleset | 対象 | 規則 | 回避者 | ファイル |
|---|---|---|---|---|
| main | `~DEFAULT_BRANCH` | 削除・force push の禁止、線形の履歴、PR（2 承認、コードオーナー、古い承認の取り消し、最後の push の承認、スレッドの解決）、strict な必須チェック（`registry/validate`・`registry/approvals`、`integration_id` = Checks App） | なし | `github-settings/main-ruleset.json`（実装済み） |
| **A：タグの作成** | `refs/tags/index-*` | `creation` | **Publisher App だけ**（`actor_type: Integration`、`bypass_mode: always`） | `github-settings/index-tags-creation-ruleset.json`（Phase 10A。`actor_id` は仮の値 0） |
| **B：タグの更新・削除** | `refs/tags/index-*` | `update`、`deletion`、`non_fast_forward` | **なし** | `github-settings/index-tags-protection-ruleset.json`（Phase 9 までの `index-tags-ruleset.json` の名前を変えたもの） |

A と B を分ける理由：回避者は、その ruleset のすべての規則に及ぶ（前提 P-6）。1 つにまとめると、Publisher App がタグを更新・削除できてしまう。

ruleset A（`index-tags-creation-ruleset.json` と同じ内容。Publisher App の ID は、App を作った後に入れる。`0` は仮の値）：

```json
{
  "name": "index tags: creation",
  "target": "tag",
  "enforcement": "active",
  "conditions": { "ref_name": { "include": ["refs/tags/index-*"], "exclude": [] } },
  "rules": [ { "type": "creation" } ],
  "bypass_actors": [ { "actor_id": 0, "actor_type": "Integration", "bypass_mode": "always" } ]
}
```

**GitHub の仕様上、実現できない場合の代替案**（7 章の前提 P-4〜P-7 の結果で選ぶ）：

| 実現できない部分 | 代替案 |
|---|---|
| App を回避者にしても、Release の公開（API）でのタグの作成が許されない | (a) Publisher App のトークンで、先にタグを git の push で作ってから、そのタグで Release を作る。(b) タグの作成の制限をやめ、クライアント側で、`latest` の tag が受け取った index の `index-<serial>` であることを確かめる（コードの変更。P4-L6） |
| 作成の制限が、admin・write の利用者に効かない | (b) と、公開のたびの監視（latest が最大の `index-<serial>` か）。Organization に移し、owner を複数にする（手順書 A-3） |
| 更新・削除の禁止が、immutable な Release のタグに効かない | immutable な Release そのもの（アセットとタグを固定する）に頼る。削除の可否を確かめて、運用の手順に入れる（P4-L7） |
| 下書きの Release の digest が得られない | 公開の後の `verify`（digest の照合）に頼り、失敗は記録する（今の実装） |

## 4. 公開の流れ

index の公開は、2 つの PR と 1 つの公開のワークフローで行う。パッケージの登録の PR と、署名した index の公開の PR は、別の PR である（今の設計。`GOVERNANCE.md` の規則 5）。

| # | 段階 | 実施する場所・主体 | 実装の状態 |
|---|---|---|---|
| 1 | パッケージの登録の PR | 持ち主（fork でもよい） | 実装済み |
| 2 | 内容の静的な検証 | `validate.yml` / `validate`（`validate-pr`。PR はデータとしてだけ扱う） | 実装済み |
| 3 | 必須チェックの発行 | `validate.yml` / `report`（Registry Checks App。現在の head SHA にだけ） | 実装済み |
| 4 | 独立したメンテナー 2 人以上の承認 | GitHub の ruleset と `approvals`（`check-approvals`：作成者・持ち主の除外、最新のコミット） | 実装済み |
| 5 | PR のマージ | メンテナー（strict な必須チェック） | 実装済み |
| 6 | 公開する対象のコミットの確認 | 署名者が `main` の最新を取り、手元で `check-repo` | 手順（SIGNING.md） |
| 7 | index の構築 | 署名者の手元で `build-index`（serial = 公開済み + 1、変更の一覧） | 実装済み |
| 8 | オフラインでの署名 | 署名者の手元（オフラインの機械）で `sign`。**GitHub Actions では署名しない。本番の秘密鍵を GitHub の secret に置かない** | 実装済み（本番の鍵は未生成、`PRODUCTION_SIGNING_ENABLED = false`） |
| — | 公開の PR（`signed/index.json`・`.sig` だけを変える） | 1〜5 と同じ流れ（`validate` が `publish-check` を行う） | 実装済み |
| 9 | 署名・serial・SHA-256 の再検証 | `publish.yml` / `check`：`publish-check`（署名、serial が直前の次、履歴、そのコミットから作り直した index とバイト列まで同じ）と、マージ済みの PR の承認の再検査（最新のコミット、ツリーの一致）。2 つのファイルの SHA-256 を記録 | 実装済み |
| 10 | `publish` environment での承認 | メンテナー（自分の承認は禁止） | 設定（未適用） |
| 11 | Publisher App の短い期間のトークンの取得 | `publish.yml` / `release`（ファイルの照合の後、最初の使用の直前に、`contents: write` とこのリポジトリに限って取る。すぐにマスクし、与えられた権限が `contents: write`（と `metadata: read`）だけで、このリポジトリだけであることを確かめる。step の終わりに失効させる） | 実装済み（Phase 10A。GitHub では未検証） |
| 12 | 下書きの Release の作成 | `release`：同じ serial の Release（下書きを含む）があれば止まる。`--draft` で作る | 実装済み |
| 13 | アセットの digest の検証 | `release`：アセットがちょうど 2 つ、`uploaded`、GitHub の digest = 記録した SHA-256 | 実装済み |
| 14 | Release の公開 | `release`：`PATCH draft=false, make_latest=true` | 実装済み |
| 15 | 公開の後の immutable の確認 | `release`（応答の `immutable`）と `verify`（`check-release`） | 実装済み |
| 9' | リポジトリのタグの ruleset の確認 | `publish.yml` / `check`：実際の ruleset（API）と `github-settings/` の A・B、App の ID を照合する（`check-live-rulesets`）。確かめられない項目があれば失敗する | 実装済み（Phase 10A）。**`bypass_actors` は、ruleset に書き込める利用者にだけ返される（GitHub の文書）。読み取りのトークンでは、ここで止まる見込み（P-19）** |
| 16 | 公開の結果の記録 | ジョブの要約（失敗のとき）。成功の記録（公開の issue への記載）は手順 | 一部実装。手順を GOVERNANCE.md に追加する |

守ること（どれも、今の実装か計画に含まれる）：
- 署名は GitHub Actions の中で自動に行わない。本番の秘密鍵を GitHub の secret に置かない。
- 公開するものと署名したものが一致すること：`publish-check` がバイト列を比べ、`release` は SHA-256 を照合し、`verify` が digest を照合する。
- PR の承認が古くなれば拒否する：GitHub の設定に加え、`check-approvals` と、公開の前の再検査。
- 同じ serial を再び公開しない：`publish-check` が「already published」を返す。`release` は、既存の Release で止まる。ruleset B がタグを固定する。
- 公開に失敗しても、自動では削除・上書きしない（GOVERNANCE.md「Failed publication」）。

## 5. 秘密鍵・署名鍵の管理

| 鍵 | 種類 | 置き場所 | 使う主体 | ローテーション・漏えい |
|---|---|---|---|---|
| 本番の index の署名鍵 | Ed25519 | 署名者の手元のオフラインの媒体（暗号化。2 か所以上・2 人以上） | 署名者が手元で `sign` | SIGNING.md・手順書 D 章。漏えいのときは、新しい鍵を組み込んだクライアントを緊急にリリースする |
| Registry Checks App の秘密鍵 | RSA（GitHub App） | environment `registry-checks` の secret | `report` ジョブ | 90 日ごとなど。漏えいのときは、App の設定で鍵を消し、その間にマージされた PR を監査する |
| Registry Publisher App の秘密鍵 | RSA（GitHub App） | environment `publish` の secret（承認の後のジョブにだけ渡る） | `release` ジョブ（計画） | 同上。漏えいのときは、インストールを停止して鍵を消し、`index-*` の Release を監査する |
| テスト用の鍵（すべて） | 上と同じ種類 | テスト用のリポジトリ・テスト用の機械だけ | テストだけ | テストの後に破棄する。本番に流用しない |

## 6. テスト環境と本番環境の分離

| 観点 | 本番 | テスト | 分離の方法 |
|---|---|---|---|
| GitHub のリポジトリ | 公式のレジストリ（名前は未確定。クライアントの `RegistryTrust.OFFICIAL_REPOSITORY`） | テスト専用のリポジトリ（例：`<owner>/CreateScript-Registry-Test`） | 別のリポジトリ。変数・secret・environment・ruleset はリポジトリごと |
| GitHub App の ID | 本番の Checks App・Publisher App | テスト専用の Checks App・Publisher App | 別の App。テスト用の App は、テスト用のリポジトリだけにインストールする |
| App の秘密鍵 | 本番のリポジトリの environment | テスト用のリポジトリの environment | 別の secret。共有しない |
| インストール先 | 本番のリポジトリだけ | テスト用のリポジトリだけ | 「Only select repositories」 |
| index の署名の公開鍵 | `RegistryTrust.PRODUCTION_KEYS`（今は空）と、本番のリポジトリの `keys/trusted-keys.json` | テスト用のリポジトリの `keys/trusted-keys.json`。鍵の ID は `test-only-` で始める | **本番の CLI には入れない。** `RegistryTrust.official()` と、公式のリポジトリの名前の設定は、`test-only-` の鍵を拒否する（実装済み） |
| レジストリの URL | 組み込み（`https://github.com/<公式>/releases/latest/download/…`）。利用者の入力・環境変数では変えられない | テスト用のクライアントのビルドの中で、`RegistryTrust(<テスト用のリポジトリ>, <テスト用の鍵>)` を渡す | テスト用のクライアント `createscript-TEST-REGISTRY`（`./gradlew :createscript-cli:testRegistryCli`。テストのソースだけにあり、本番の配布物に入らない。Phase 10A）。本番の CLI に、URL や鍵を変えるオプション・環境変数を **加えない** |
| Release のアセットの名前 | `index.json`・`index.json.sig` | 同じ | リポジトリが別なので衝突しない |
| キャッシュの置き場所 | `<キャッシュ>/registry/<owner>/<repo>/` | 同じ規則で、テスト用のリポジトリの名前の下（自動で分かれる） | テストでは、さらに `CREATESCRIPT_CACHE_DIR` を専用のディレクトリにする |
| 環境変数 | `CREATESCRIPT_CACHE_DIR`・`CREATESCRIPT_GITHUB_TOKEN`（任意） | 同じ。加えて、テスト用のディレクトリ | 本番の信頼の設定を変える環境変数は無い（加えない） |
| ツールの公開の確認 | `publish-check` はテスト用の鍵を拒否する | テスト用の鍵を受け入れる、テスト用のツールのビルドが必要 | テスト用のツール `createscript-registry-tool-TEST-ONLY`（`./gradlew :createscript-registry-tool:testRegistryTool`。Phase 10A）。本番のツールに、テスト用の鍵を許す CLI のオプションを **加えない**（本番の main は `allowTestKeys` を渡さない） |
| ワークフロー | `TOOL_COMMIT` = 審査済みの本番用のコミット | テスト用のリポジトリのワークフローは、テスト用のツールのビルドを指す | テスト用のリポジトリの中だけで変える |

**本番の信頼の境界は緩めない。** テストのために、本番の CLI・ツールに、信頼する鍵・リポジトリ・URL を変える経路を加えない。

## 7. GitHub 上で検証すべき仕様（前提）

すべて **未検証**。`docs/registry-github-integration-test.md` の試験 ID を示す。

| ID | 前提 | 試験 |
|---|---|---|
| P-1 | `integration_id` を指定した必須チェックは、その App の check run だけで満たされる（GitHub Actions のジョブの同じ名前のチェックでは満たされない） | IT-02 |
| P-2 | Checks App のトークン（checks: write）で、fork の PR の head SHA に check run を作れる | IT-01・IT-02 |
| P-3 | `pull_request_target` のジョブで、environment（deployment branch は main）の secret を使える | IT-01 |
| P-4 | ruleset A（creation）は、admin・write の利用者による `index-*` のタグの作成（git の push・Release の API のどちらでも）を拒否する | IT-21 |
| P-5 | ruleset A の回避者（Integration = Publisher App）は、その App のインストールのトークンによるタグの作成を許す。**下書きを公開するときにタグが作られる場合にも、回避が効く** | IT-21・IT-09 |
| P-6 | 回避は、その ruleset のすべての規則に及ぶ（そのため A と B を分ける） | IT-22 |
| P-7 | ruleset B（回避者なし）は、Publisher App・admin を含むすべての主体による `index-*` の更新・削除を拒否する | IT-22 |
| P-8 | 下書きの Release は、タグを公開するまで作らず、`releases/latest` に現れない | IT-12 |
| P-9 | 下書きのアセットにも `digest`（`sha256:…`）と `state` がある | IT-12・IT-13 |
| P-10 | immutable releases が有効なら、公開の後にアセットとタグを変えられない。下書きから公開への `PATCH` は使える | IT-11 |
| P-11 | `make_latest` は、Contents: write を持つ主体が変えられる | IT-15 |
| P-12 | Publisher App の Contents: write のトークンで、Release の作成・編集ができる | IT-09 |
| P-13 | environment `publish` の secret は、承認の後のジョブにだけ渡る。自分の承認の禁止が効く | IT-23 |
| P-14 | `DELETE /installation/token` で、トークンが直ちに使えなくなる（エンドポイントと 204 の応答は、GitHub の REST の文書で確認した（2026-11-12）。実際の動作は未検証） | IT-08・IT-24 |
| P-15 | `commits/{sha}/pulls` が、squash・rebase のマージの後のコミットから PR を返す。マージの後でも、PR の head を fetch できる | IT-09 |
| P-16 | 外部の協力者の fork の PR のワークフローは、承認まで実行されない | IT-02 |
| P-17 | Release の削除の可否（immutable な Release を含む） | IT-11 |
| P-18 | ジョブの `GITHUB_TOKEN`（`contents: read`）で、`GET /repos/{owner}/{repo}/rulesets` と `…/rulesets/{id}` を読める | IT-21 |
| P-19 | `bypass_actors` が、読み取りのトークンに返されるか。**GitHub の文書では「ruleset に書き込める利用者にだけ返す」**とあり、返されない見込み。返されなければ、公開の前の確認（`check-live-rulesets`）は失敗し、公開は止まる。その場合の確かめ方（例：運営責任者が管理者の権限で確認して記録する手順）は Phase 10B で決める | IT-21 |

## 8. 失敗のときの停止と復旧

| 事象 | 止め方 | 復旧 |
|---|---|---|
| 公開の途中の失敗 | 自動で止まる（下書きのまま、または失敗を記録）。何も自動では消さない | GOVERNANCE.md「Failed publication」 |
| 鍵（どれか）の漏えいの疑い | 該当する App のインストールを停止し、鍵を消す。署名鍵なら、SIGNING.md の漏えいの手順 | 新しい鍵、監査、告知 |
| 不正な `index-*` の Release、latest の変更 | 公開を止める：`publish` のワークフローを無効にし、environment の承認を止める | 正しい latest に戻す（`make_latest`）。経緯を issue に記録し、次の serial で公開し直す |
| 必須チェックの偽装の疑い | マージを止める（ruleset の確認、外部の協力者のワークフローの承認の停止） | 偽装の経路を特定して、設定を直す |
| 緊急の全面的な停止 | `publish`・`validate` を無効にし、両方の App のインストールを停止する | 原因の除去の後に、運営の PR（2 人と運営責任者）で戻す |

## 9. 残存リスク

| ID | リスク | 対策・状態 |
|---|---|---|
| R-1 | **L-5（未解決）**：同じユーザーの権限のプログラムによる symlink の差し替えの競合で、インストール中にプロジェクトの外へ新しいファイル・パッケージのディレクトリが作られる（観測では 0.2〜0.7%）。macOS の JDK 21 には、開いたディレクトリを基準にする API が無い。理論上は、マニフェスト・ロックファイルの置き換えの直前の狭い窓で、別の場所の同じ名前のファイルを置き換えうる（観測されていない） | `L5RaceTest`（`CREATESCRIPT_L5_RACE=1`）で再現・監視する。誤った成功と既存のファイルの変更は 0。**GitHub の統合テストが成功しても、L-5 は解決しない** |
| R-2 | 管理者は ruleset を変えられる | 変更は事前に issue で告知する。Organization で owner を複数にする |
| R-3 | write の権限で、別の名前の Release の作成・latest の変更ができる | 監視。クライアント側の tag の照合（計画 W6） |
| R-4 | 初めて使うとき・キャッシュを消した後は、期限内の古い index を受け入れうる | 有効期間を短くする。docs/registry.md「Security limits」 |
| R-5 | Publisher App の鍵を持つ人は、`index-*` のタグと Release を作れる（署名はできない） | environment の secret、ローテーション、監視 |
| R-6 | Checks App の鍵を持つ人は、必須チェックを作れる | environment の secret、ローテーション |
| R-7 | GitHub の仕様の変更・前提の誤り（7 章） | 統合テストで確かめ、結果に応じて 3 章の代替案を選ぶ |
