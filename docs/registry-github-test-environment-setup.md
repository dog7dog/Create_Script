# CreateScript-Registry テスト環境の構築手順（GitHub 実環境テスト用）

> **状態（2026-11-12、S8.3 Phase 10B-1）：手順書です。ここに書いた構築は、どれも実施していません。**
>
> - リポジトリ・App・ruleset・environment・secret・鍵は、まだ何も作っていません。
> - 構築（Phase 10B-2）には、運営責任者の明示の許可が必要です。
> - GitHub の仕様の記述は、2026-11-12 に読んだ公式の文書に基づきます（参照先は末尾の H 章）。文書で確かめられなかったものは「未確認」と書きます。
> - 試験の内容は `docs/registry-github-integration-test.md`（IT-01〜IT-28）、構成は `docs/registry-github-architecture.md` にあります。

## A. 必要なリソース

| リソース | 名前（仮） | 本番との分離 |
|---|---|---|
| テスト専用のリポジトリ | `<owner>/CreateScript-Registry-Test`（public） | 本番のリポジトリとは別。2026-11-12 に、`dog7dog/CreateScript-Registry-Test` と `dog7dog/CreateScript-Registry` が公開されていないこと（HTTP 404）を、読み取りだけで確かめた（作成はしていない） |
| テスト用の Registry Checks App | 「CreateScript Registry Checks (Test)」 | 本番の App とは別。テスト用のリポジトリだけにインストールする |
| テスト用の Registry Publisher App | 「CreateScript Registry Publisher (Test)」 | 同上 |
| テスト用の Ed25519 の署名鍵 | 鍵の ID `test-only-integration-1` | 本番の CLI・ツールは `test-only-` の鍵を信頼しない（実装済み・試験済み） |
| テスト用の environment | `registry-checks`、`publish` | テスト用のリポジトリの中だけ |
| テスト用の ruleset | main、A（`index-tags-creation-ruleset.json`）、B（`index-tags-protection-ruleset.json`） | テスト用のリポジトリの中だけ。App の ID はテスト用のもの |
| テスト用のパッケージ | 例：`<owner>/createscript-test-numbers`（`createscript pack` で作った ZIP を、immutable な Release に置く） | 本番のレジストリには登録しない |
| テスト専用のビルド | `createscript-TEST-REGISTRY`（`./gradlew :createscript-cli:testRegistryCli`）、`createscript-registry-tool-TEST-ONLY`（`./gradlew :createscript-registry-tool:testRegistryTool`） | テストのソースだけにあり、本番の配布物に入らない（Phase 10A で確認） |
| 人 | 下の B 章 | — |

## B. 必要な人員と権限

**人数の要件**
- GitHub の利用規約では、1 人が持てる無料のアカウントは 1 つだけ。マシンアカウントは自動の作業にだけ使える。
- そのため、承認の試験に使う「別々の承認者」は、別々の **人** でなければならない。

| 役 | 人数 | テスト用のリポジトリの権限 | 使う試験 |
|---|---|---|---|
| T-lead（運営責任者） | 1 | admin | すべての構築。admin による拒否の試験（IT-21・IT-22） |
| T-m2・T-m3（メンテナー） | 2 | write | 2 人の承認（IT-01・IT-03〜IT-06）。write の権限でタグを作れないこと（IT-21）。`publish` の承認者（IT-23。自分の承認の禁止があるので、公開の PR の作成者以外が承認する） |
| T-owner（パッケージの持ち主） | 1（T-m3 が兼ねてよい。利益相反の試験 IT-05 では、兼ねることが条件） | なし（または write） | IT-05 |
| T-outside（外部の協力者） | 1（write の権限を持たない別の人） | なし（fork から PR を出す） | 必須チェックの偽装（IT-02） |

- **最小の人数：** 3 人（T-lead・T-m2・T-m3）。偽装の試験（IT-02）まで行うなら 4 人。
- T-lead 1 人だけでできる試験もある（D 章の PT-1・PT-3〜PT-6 の一部）。
- **App の権限（最小）：** 下の表。
  - GitHub の文書（「Permissions required for GitHub Apps」）によれば、Release の作成は Contents: write、ruleset の読み取り（一覧・詳細）は Metadata: read。ruleset の作成・更新・削除と履歴は Administration: write。
  - どちらの App にも、Administration は **付けない**。

| App | リポジトリの権限 | Webhook | インストールできる範囲 | インストール先 |
|---|---|---|---|---|
| Checks (Test) | Checks: read and write（Metadata: read は自動） | 無効 | Only on this account | テスト用のリポジトリだけ |
| Publisher (Test) | Contents: read and write（Metadata: read は自動） | 無効 | Only on this account | テスト用のリポジトリだけ |

## C. GitHub の画面での設定の手順（Phase 10B-2 で行う。今回は行わない）

画面の名前は、2026-11-12 に読んだ文書のもの。実際の画面と違えば、その違いを記録する。

1. **リポジトリ：** 空の public リポジトリ `CreateScript-Registry-Test` を作る。
2. **雛形：** CreateScript の `registry-template/` の中身を、ルートに置く。
   - ワークフローの変更は、テスト用のツールのビルドを指すようにするところだけにする（`docs/registry-github-integration-test.md` の A 章）。
   - `TOOL_COMMIT` は審査済みのコミットにする。`WRAPPER_JAR_SHA256` は、公式の checksum と照合した値にする。
3. **立ち上げのコミット：** `maintainers.json`（T-lead・T-m2・T-m3 のログイン名と数値 ID）、`.github/CODEOWNERS`（`* @T-lead @T-m2 @T-m3`）。`check-repo` が `OK` になること。
4. **App の登録**（Settings → Developer settings → GitHub Apps → New GitHub App）：
   - 名前（34 文字以内）、Homepage URL（テスト用のリポジトリの URL でよい）を入れる。
   - Webhook の「Active」を外す。
   - 権限は B 章の表のとおり。
   - 「Where can this GitHub App be installed?」は「Only on this account」。
   - 2 つの App を、それぞれ登録する。
5. **秘密鍵：** それぞれの App の設定で秘密鍵（PEM）を 1 つ作る。テスト用のリポジトリの environment の secret に登録した後、手元のファイルは安全に消す。
   - 秘密鍵の作り方・数は、今回読んだ文書の範囲では確かめていない（未確認）。
6. **インストール：** それぞれの App を、「Only select repositories」でテスト用のリポジトリだけにインストールする。
7. **変数：** リポジトリの変数 `REGISTRY_CHECKS_APP_ID`・`REGISTRY_PUBLISHER_APP_ID`。
8. **environment**（Settings → Environments）：
   - `registry-checks`：Deployment branches は `main` だけ。secret `REGISTRY_CHECKS_APP_KEY`。
   - `publish`：Required reviewers は T-m2・T-m3（文書によれば最大 6、1 人の承認で進む）。「Prevent self-review」を有効にする。Deployment branches は `main` だけ。secret `REGISTRY_PUBLISHER_APP_KEY`。
   - 文書によれば、environment を参照するジョブは、保護の規則を満たすまで、その secret を使えない。
9. **immutable releases**（Settings → Releases →「Enable release immutability」）：有効にする。
   - 文書によれば、有効にした後の Release だけが対象。
10. **ruleset**（Settings → Rules → Rulesets）：
    1. App の ID を `github-settings/` の 3 つのファイルに入れる。
    2. 手元で `check-settings --ruleset … --tag-creation-ruleset … --tag-protection-ruleset … --checks-app-id … --publisher-app-id …` が `OK` になることを確かめる。
    3. main・A・B を作る（JSON の取り込み、または画面）。
11. **Actions の設定：** SHA で固定したアクションだけを許す。外部の協力者のワークフローの実行には、承認を求める。workflow permissions は読み取りだけ。
12. **テスト用のパッケージ：** 別のテスト用のリポジトリに、`createscript pack` で作った ZIP を `v<版>` の immutable な Release として置く。

## D. Phase 10B-2 で最初に行う試験（優先試験 PT-1〜PT-8）

28 の試験（IT-01〜IT-28）の前に、設計の前提を確かめる。どれも、テスト用のリポジトリだけで行う。

共通の事項：
- **全体の停止条件：** `docs/registry-github-integration-test.md` の B 章（本番に及ぶ操作、秘密情報の露出、拒否されるはずの操作の成功、テスト用の設定の本番への混入）。
- **証拠の保存：** テスト用のリポジトリの issue「PT run <日付>」に、試験ごとのコメントとして、操作・API の応答（JSON。トークンや鍵を含まないもの）・ワークフローの実行の URL・ruleset の評価（Rule insights）の画面の写しを残す。秘密情報は、その一部も残さない。

| ID | 確かめること | 操作 | 期待結果 | 停止条件 | 対応する IT・前提 |
|---|---|---|---|---|---|
| PT-1 | Publisher App だけが `index-*` のタグを作れる | Publisher App のインストールのトークンで、`POST /repos/{r}/releases`（`tag_name: index-900`、`draft: true`）→ `PATCH`（`draft: false`）。あわせて `git push` でタグを作る | 作れる（ruleset A の回避が効く）。下書きを公開するときのタグの作成でも、回避が効く | Publisher App でも作れない場合：試験を止め、architecture の 3 章の代替案を検討する | IT-21・IT-09、P-5・P-12 |
| PT-2 | write の権限の利用者は作れない | T-m2（write）と T-lead（admin）で、`git push origin index-901`。Release の API で `tag_name: index-901` の下書き・公開を試す | すべて拒否される | 作れた（**前提 P-4 が誤り**） | IT-21、P-4 |
| PT-3 | Checks App は作れない | Checks App のトークン（checks: write）で、Release の作成を試す | 権限が無いので拒否される（403） | 作れた | IT-26 |
| PT-4 | 作成済みのタグの更新・削除は拒否される | PT-1 の `index-900` を、Publisher App・T-lead・T-m2 で、別のコミットへ移す（force push）、または削除する | すべて拒否される（ruleset B） | どれかが成功した | IT-22、P-6・P-7 |
| PT-5 | ruleset の API で `bypass_actors` を取得できるか | `GET /repos/{r}/rulesets` と `…/rulesets/{id}` を、①ジョブの `GITHUB_TOKEN`、②Checks App、③Publisher App、④T-lead の読み取りのトークン、⑤T-lead の admin の権限で読む。それぞれで、`bypass_actors`・`current_user_can_bypass`・`updated_at` が返るかを記録する。さらに、⑤で回避者を変えて、`updated_at` が変わるかを記録する | 文書どおりなら、`bypass_actors` は ⑤だけに返る。`current_user_can_bypass` は、A では ③が `always`、①②が `never`。B ではすべて `never`。`updated_at` は、回避者の変更で変わる（W3 の推奨案の前提） | なし（記録が目的）。ただし、③で A が `always` でなければ、PT-1 の結果と合わせて検討する | IT-21、P-18〜P-21 |
| PT-6 | Publisher App のトークンが限定される | `release` ジョブと同じ要求（`repositories: [テスト用]`、`permissions: {contents: write}`）でトークンを取り、応答の `permissions`・`repository_selection`・`repositories` を記録する（トークンは記録しない）。そのトークンで、別のリポジトリへの書き込みと、check run の作成を試す。最後に `DELETE /installation/token` の後で 401 になることを、ステータスコードだけで確かめる | 応答は `contents: write`（と `metadata: read`）、`selected`、テスト用のリポジトリだけ。ほかは拒否。失効は 204、その後は 401 | 応答の形が違う（ワークフローの照合が通らない）、または権限が広い | IT-24・IT-27、P-12・P-14 |
| PT-7 | environment の承認の前に、鍵を使えない | 公開の PR をマージして、`release` を承認待ちにする。承認しないまま待つ。PR の作成者（T-m2）が自分で承認しようとする。main 以外のブランチのワークフローで `publish` を参照する | 承認の前・main 以外では、ジョブが始まらず、secret は渡されない。自分の承認は拒否される | 承認の前に secret が使えた | IT-23、P-13 |
| PT-8 | 下書きの公開の処理が安全に止まる | テスト用のワークフローの変形で、下書きのアセットの digest を合わなくする（例：アセットを差し替える step を、試験のためだけに加える）。また、同じ serial の下書きを残してから、もう一度実行する | 「left as a draft」で止まり、公開されず、latest にならない。2 回目は「already exists」で止まる。何も自動で削除されない | 下書きが公開された | IT-12・IT-14・IT-15、P-8・P-9 |

**注意：** W3 の確認（`check-live-rulesets`）は、今の実装のままでは、`bypass_actors` を読めないので失敗し、IT-09（正常な公開）を止める見込み。PT-5 の結果を受けて、W3 の推奨案（監査報告書の 4 章）を実装してから、IT-09 を行う。

## E. API による確認の方法（読み取りだけ。構築の前後に使う）

構築の前には、公開の情報だけを読む（認証なし）。構築の後は、試験の範囲の権限で読む。

```sh
# リポジトリが無いこと・あること（認証なし。公開のものだけ）
curl -s -o /dev/null -w '%{http_code}\n' https://github.com/<owner>/CreateScript-Registry-Test

# ruleset の一覧と詳細（Metadata: read で読める。bypass_actors は、書き込める利用者にだけ返る）
gh api "repos/<owner>/CreateScript-Registry-Test/rulesets?per_page=100"
gh api "repos/<owner>/CreateScript-Registry-Test/rulesets/<id>" --jq '{name, target, enforcement, updated_at, current_user_can_bypass, has_bypass_actors: has("bypass_actors")}'

# App のインストール（App の JWT が必要）・Release（読み取り）
gh api "repos/<owner>/CreateScript-Registry-Test/releases/tags/index-<serial>" --jq '{tag_name, draft, immutable, assets: [.assets[] | {name, digest}]}'
```

- 出力を issue に貼るときは、トークン・鍵を含まないことを確かめる。
- `gh` の認証の情報は、ログ・画面の写しに残さない。

## F. テスト用の鍵の扱い

| 鍵 | 作る場所 | 保管 | 破棄 |
|---|---|---|---|
| テスト用の Ed25519 の署名鍵（`test-only-integration-1`） | テスト用の機械。`openssl genpkey -algorithm ed25519` | テスト用の機械の中だけ。リポジトリ・issue・secret には置かない。公開鍵だけを、テスト用のリポジトリの `keys/trusted-keys.json` と、テスト用のクライアントの引数に使う | 試験の後に消し、消したことを issue に記録する |
| テスト用の App の秘密鍵（2 つ） | App の設定の画面 | テスト用のリポジトリの environment の secret だけ。手元のファイルは登録の直後に消す | 試験の後に、App の設定で鍵を消し、App を削除する |

本番の鍵・本番の App・本番の信頼の設定（`RegistryTrust.official()`）には、何も加えない。

## G. 費用とプランの制限

| 項目 | 文書で確かめたこと | 未確認・注意 |
|---|---|---|
| GitHub Actions | public のリポジトリで、標準の GitHub ホストのランナーは **無料**（含まれる使用量も減らさない） | — |
| environment（required reviewers・prevent self-review・deployment branches・secret） | GitHub Free では **public のリポジトリでだけ** 設定できる（「Deployment protection rules for public repositories」） | 個々の機能の、プランごとの可否の細目は未確認 |
| repository ruleset（ブランチ・タグ） | 文書の「About rulesets」は、Organization のリポジトリの ruleset について、Team・Enterprise のプランを挙げている。個人のアカウントの public のリポジトリで、GitHub Free で使えるかの明記は **見つからなかった** | **未確認**。プランの一覧の「public のリポジトリでは全機能」という記述から、使える可能性はあるが、推測なので構築の最初に確かめる（使えなければ、有料のプランか Organization の検討が必要） |
| GitHub App の登録 | 個人のアカウントで登録できる。1 つのアカウントで最大 100 個。費用の記載は無い | — |
| immutable releases | Settings → Releases で有効にする。有効にした後の Release だけが対象。削除はできるが、同じタグの名前は再び使えない | プランごとの可否は未確認 |
| 保存の容量 | GitHub Free の成果物（artifact）の保存は 500 MB（Packages と共有） | このテストは artifact を使わないので、影響は小さい見込み |
| 有料のプランが要る条件 | 上の ruleset が使えない場合。または、テスト用のリポジトリを private にする場合（environment が使えなくなる） | 料金は今回は確かめていない |

**結論（暫定）：** public のテスト用のリポジトリなら、Actions・environment・App は無料で使える。**ruleset が無料で使えるかは未確認**で、これが無料で実施できるかの分かれ目になる。

**維持の負担：**
- 2 つの App と鍵の管理（試験の期間だけ）。
- テスト用の署名鍵の保管と破棄。
- 3〜4 人の協力（承認の試験の日程）。
- テスト用のリポジトリの監視（公開の Release が、本番と誤解されないよう、README に「TEST ONLY」と明記する）。

## H. 構築の前の確認事項

- [ ] 運営責任者の許可（GitHub への変更、テスト用の鍵の生成）。
- [ ] 協力者の確保：T-m2・T-m3（必須）、T-outside（IT-02 を行う場合）。それぞれの数値 ID と 2 要素認証。
- [ ] リポジトリの名前が空いていること（読み取りで確認）。名前に Test を含め、README に「TEST ONLY — not the CreateScript-Registry」と書く。
- [ ] ruleset がそのアカウント・プランで使えること（構築の最初に確かめる。使えなければ止める）。
- [ ] `TOOL_COMMIT` に使うコミットと、`WRAPPER_JAR_SHA256` を公式の checksum と照合すること。
- [ ] テスト専用のビルド（`testRegistryCli`・`testRegistryTool`）を、そのコミットから作れること。
- [ ] 証拠を残す issue の用意。
- [ ] 本番の設定（`RegistryTrust.official()`・本番の鍵の一覧）に、何も加えないことの確認。

## I. ロールバック・削除の手順（試験の後、または停止条件に当たったとき）

1. **すぐに止める：** テスト用のリポジトリの Actions を無効にする。両方の App のインストールを停止（Suspend）する。
2. **秘密情報：** 両方の App の秘密鍵を App の設定で消す。environment の secret を消す。テスト用の Ed25519 の秘密鍵を、テスト用の機械から消す。
3. **App：** 両方のテスト用の App を削除する。
4. **リポジトリ：** 証拠を `docs/audits/` の結果の報告書に移した後、テスト用のリポジトリをアーカイブするか削除する。
   - immutable な Release のタグの名前は、削除した後も再び使えない（文書）。同じ名前のリポジトリを作り直しても、その制限が残る。
   - 本番では、テスト用と同じ名前を使わない。
5. **記録：** 消したもの・日時・実施者を、結果の報告書に残す。
6. **確認：** 本番の CLI・ツールの信頼の設定に、テスト用のものが残っていないこと（`RegistryTrust.official()` の鍵は空のまま。`TestRegistryCliTest`・`TagRulesetsTest` の分離の試験が成功すること）。

## J. 参照した公式の文書（2026-11-12 に読んだもの）

- REST API：GitHub Apps のインストール（`DELETE /installation/token`）— https://docs.github.com/en/rest/apps/installations
- REST API：GitHub Apps（インストールのトークンの作成、有効期限 1 時間）— https://docs.github.com/en/rest/apps/apps
- REST API：Repository rules（ruleset、`bypass_actors` の可視性、`current_user_can_bypass`、履歴）— https://docs.github.com/en/rest/repos/rules
- GitHub Apps に必要な権限 — https://docs.github.com/en/rest/authentication/permissions-required-for-github-apps
- About rulesets — https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/about-rulesets
- Creating rulesets for a repository — https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/creating-rulesets-for-a-repository
- Managing environments for deployment — https://docs.github.com/en/actions/managing-workflow-runs-and-deployments/managing-deployments/managing-environments-for-deployment
- Immutable releases — https://docs.github.com/en/code-security/supply-chain-security/understanding-your-software-supply-chain/immutable-releases
- Preventing changes to your releases — https://docs.github.com/en/code-security/supply-chain-security/understanding-your-software-supply-chain/preventing-changes-to-your-releases
- About billing for GitHub Actions — https://docs.github.com/en/billing/managing-billing-for-your-products/managing-billing-for-github-actions/about-billing-for-github-actions
- GitHub's plans — https://docs.github.com/en/get-started/learning-about-github/githubs-plans
- Registering a GitHub App — https://docs.github.com/en/apps/creating-github-apps/registering-a-github-app/registering-a-github-app
- GitHub Terms of Service（アカウントの数・マシンアカウント）— https://docs.github.com/en/site-policy/github-terms/github-terms-of-service
