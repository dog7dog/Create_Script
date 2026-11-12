# CreateScript-Registry GitHub 実環境テスト手順書

> **この文書は計画です。ここに書いたテストは、どれも実施していません（2026-11-12、S8.3 Phase 10A の時点）。**
>
> - 実施には、運営責任者の明示の許可が必要です。テスト用のリポジトリ・App・鍵の作成も含め、GitHub への変更を伴います。
> - 本番の CreateScript-Registry では行わず、本番の鍵も使いません。
> - 構成は `docs/registry-github-architecture.md`（Phase 9 で採用した、Checks App と Publisher App の 2 つの App の設計）に従います。
> - Publisher App・ruleset A・B・テスト専用のビルドは、Phase 10A で雛形とツールに **ローカルで** 実装しました（`docs/audits/S8.3-Phase10A-Local-Implementation.md`）。GitHub 上では未検証です。
> - 前提の ID（P-1〜P-19）は、architecture の 7 章のものです。
> - 試験の ID は IT-01〜IT-28 です。Phase 8 までの番号との対応は、付録 G にあります。
> - **秘密情報（App の鍵・トークン・署名の鍵）は、その一部であっても、ログ・issue・画面の写し・報告書に残しません。**

## A. テスト環境

| 項目 | 内容 |
|---|---|
| リポジトリ | テスト専用（例：`<owner>/CreateScript-Registry-Test`。public。名前に Test を含める）。本番とは別のリポジトリなので、変数・secret・environment・ruleset・Release はすべて別 |
| Registry Checks App（テスト用） | 例：「CreateScript Registry Checks (Test)」。権限は Checks: read and write だけ。テスト用のリポジトリだけにインストールする。鍵は、テスト用のリポジトリの environment `registry-checks` |
| Registry Publisher App（テスト用） | 例：「CreateScript Registry Publisher (Test)」。権限は Contents: read and write だけ。テスト用のリポジトリだけにインストールする。鍵は、テスト用のリポジトリの environment `publish` |
| 署名の鍵 | テスト専用の Ed25519 の鍵。鍵の ID は `test-only-integration-1`（`test-only-` で始める）。テスト用の機械で作り、テストの後に破棄する。**本番の CLI・ツールの信頼の一覧には入れない** |
| ツール | 本番の `publish-check` は、テスト用の鍵を拒否する。テスト用のリポジトリのワークフローでは、ツールを作る行を `./gradlew --no-daemon --no-build-cache --quiet --dependency-verification strict :createscript-registry-tool:testRegistryTool` に替え、`TOOL` を `tool/createscript-registry-tool/build/test-registry-tool/bin/createscript-registry-tool-TEST-ONLY` にする（テスト用のリポジトリだけで変える。このビルドは、公式のリポジトリの名前の `GITHUB_REPOSITORY` では動かない）。それ以外のワークフローは雛形のまま |
| クライアント | `./gradlew :createscript-cli:testRegistryCli` で作る `createscript-TEST-REGISTRY`。`--test-registry <owner/repo> --test-key-id test-only-… --test-public-key <Base64> --test-cache-dir <絶対パス> install …` で使う。本番の配布物ではない。実行のたびに「TEST-REGISTRY BUILD」と表示する |
| `maintainers.json` | テスト用のアカウント 3 つ以上（2 要素認証あり）。役割を分ける：運営責任者（T-lead）、メンテナー（T-m2・T-m3）、パッケージの持ち主（T-owner）、外部の協力者（T-outside、fork から PR を出す） |
| ruleset | テスト用のリポジトリに、`main`（App の ID はテスト用の Checks App）、A（作成、回避者はテスト用の Publisher App）、B（更新・削除、回避者なし） |
| environment | `registry-checks`（main だけ）、`publish`（承認者は T-m2・T-m3、自分の承認の禁止、main だけ） |
| Release | テスト用のリポジトリの `index-*` だけ |
| 証拠の保存先 | テスト用のリポジトリの issue「Integration test run <日付>」に、試験ごとのコメントとして残す（ワークフローの実行の URL、API の応答の JSON、画面の写し、ログ）。まとめを `docs/audits/` の結果の報告書に残す |

## B. 全体の停止条件（どの試験の途中でも、すぐに止める）

1. 本番のリポジトリ・本番の App・本番の鍵に、何かの操作が及んだ、または及びそうになった。
2. 秘密情報（App の鍵、トークン、署名の鍵）が、ログ・issue・画面の写しに現れた。
   - その鍵を直ちに無効にし（App の鍵の削除、インストールの停止）、試験を中止して、運営責任者に報告する。
3. 「拒否されるはず」の操作が成功した（偽装のチェックでマージできた、Publisher App 以外がタグを作れた、など）。
   - それ以上の操作をせずに、状態を記録して止める。前提（P-x）が誤っていたものとして、architecture の 3 章の代替案を検討する。
4. テスト用の鍵・設定が、本番の信頼の設定（`RegistryTrust.official()`、本番の `keys/trusted-keys.json`）に入っていることが分かった。

各試験の「停止条件」は、上に加えて、その試験に固有のもの。

## C. 試験

判定は、まだすべて **NOT VERIFIED（未実施）**。

### IT-01 正常な PR の検証
- **事前条件：** 雛形・ruleset main・Checks App・environment を適用済み。テスト用のパッケージの immutable な Release がある。
- **操作：** T-owner が登録の PR を出す。T-m2・T-m3 が承認する。ラベル `recheck` を付ける。
- **期待結果：** `registry/validate`・`registry/approvals` が、テスト用の Checks App から head SHA に成功で付く（P-2・P-3）。マージできる。
- **停止条件：** チェックが GitHub Actions の App から付いた。
- **証拠：** 実行の URL、`GET /repos/{r}/commits/{sha}/check-runs` の応答（`app.id`）。
- **判定：** NOT VERIFIED

### IT-02 必須チェックの偽装
- **事前条件：** IT-01 と同じ。外部の協力者のワークフローの実行に、承認を求める設定。
- **操作：**
  1. T-outside の fork から、`registry/validate`・`registry/approvals` という名前のジョブを持つワークフローを加えた PR を出す。
  2. ワークフローの実行を承認する。
  3. 本物の検査が失敗するように、PR を作っておく。
- **期待結果：** 偽のチェックは GitHub Actions の App から付き、必須チェックを満たさない（P-1）。`validate` は `.github/` の変更を拒否する。マージできない。承認の前は、偽のワークフローは動かない（P-16）。
- **停止条件：** マージのボタンが有効になった（**前提 P-1 が誤り**）。
- **証拠：** check run の一覧（`app.id`）、マージの可否の画面、ruleset の評価の記録。
- **判定：** NOT VERIFIED

### IT-03 承認の不足
- **事前条件：** IT-01 と同じ。
- **操作：** T-m2 だけが承認し、`recheck` を付ける。
- **期待結果：** `registry/approvals` が失敗する。GitHub もマージを拒否する。
- **停止条件：** マージできた。
- **証拠：** 実行のログ（`1 of the 2 approvals`）、マージの画面。
- **判定：** NOT VERIFIED

### IT-04 作成者による自己承認
- **事前条件：** T-m2 が作成した運営の PR。
- **操作：** T-m2 が承認しようとする。T-lead と T-m3 が承認する。
- **期待結果：** GitHub が作成者の承認を拒否する。T-lead と T-m3 の承認でマージできる（デッドロックしない）。
- **停止条件：** 作成者の承認が数えられた。
- **証拠：** レビューの一覧、`approvals` のログ。
- **判定：** NOT VERIFIED

### IT-05 利益相反のある承認
- **事前条件：** T-m3 がパッケージの持ち主として登録されている。
- **操作：** そのパッケージの更新の PR を、T-m2 と T-m3 が承認する。
- **期待結果：** `registry/approvals` が、T-m3 を「conflict of interest」で数えず、失敗する。
- **停止条件：** 成功した。
- **証拠：** ログ。
- **判定：** NOT VERIFIED

### IT-06 PR の更新の後の古い承認
- **事前条件：** 承認済みで、チェックが成功している PR。
- **操作：**
  1. 新しいコミットを push する。
  2. 古い実行を「Re-run」する。
  3. 承認し直した後に `recheck` を付ける。
- **期待結果：** GitHub が古い承認を取り消す。古い実行は「outdated」で止まり、チェックを報告しない。承認し直した後だけ成功する。
- **停止条件：** 古い実行が、新しい head にチェックを付けた。
- **証拠：** 実行のログ、check run の `head_sha`。
- **判定：** NOT VERIFIED

### IT-07 Checks App が入っていない
- **事前条件：** テスト用の Checks App のインストールを停止する。
- **操作：** PR を更新する。
- **期待結果：** `report` が「not installed」で失敗し、必須チェックが付かず、マージできない。
- **停止条件：** マージできた。
- **証拠：** ログ。
- **判定：** NOT VERIFIED

### IT-08 Checks App のトークンの失効
- **事前条件：** IT-01 と同じ。テスト用のリポジトリの `validate.yml` の `report` の step に、試験のためだけの確認を加えた版を使う（本番の雛形には加えない）。
- **操作：**
  1. `report` の実行の後に、ログ全体にトークンが無いこと（マスクされた `***` だけであること）を確かめる。
  2. 試験用の確認は、`cleanup` の中で、失効の要求の **後に**、同じシェルの変数のトークンで `GET https://api.github.com/installation/repositories` を呼び、**HTTP のステータスコードだけ** を出力する（例：`curl -s -o /dev/null -w '%{http_code}' …`）。トークン・その一部・ハッシュ・長さは、出力もファイルへの保存もしない。
  3. 失効の要求の応答のステータス（204 を期待）も、コードだけを出力する。
- **期待結果：** 失効の要求は 204。失効の後の要求は 401（P-14）。ログには、トークンのどの部分も現れない。
- **停止条件：** ログ・issue・画面の写しに、トークンの全体または一部が現れた（B の 2。鍵を直ちに消す）。
- **証拠：** ログのステータスコードの行（トークンを含まない）、実行の URL。
- **判定：** NOT VERIFIED

### IT-09 正常な公開（Publisher App）
- **事前条件：** Phase 10A の実装（W1〜W3・W7）。テスト用の署名の鍵。ruleset A・B を適用済み。変数 `REGISTRY_CHECKS_APP_ID`・`REGISTRY_PUBLISHER_APP_ID`、`github-settings/` の A の `actor_id` に、テスト用の App の ID を入れる。
- **操作：**
  1. テスト用のツールで `build-index`・`sign` を行う（テスト用の機械で）。
  2. 公開の PR を出し、2 人が承認し、マージする。
  3. `publish` environment を承認する。
- **期待結果：**
  - `check` の、タグの ruleset の確認（`check-live-rulesets`）が結果を出す（P-18・P-19）。**`bypass_actors` が読み取りのトークンに返されなければ、ここで失敗して止まる**（期待される安全側の動作）。その場合は、それを記録して IT-09 を中断し、確かめ方を決めてから再開する。
  - `check` が成功する（P-15）。
  - `release` が Publisher App のトークンで、下書き → 確認 → 公開を行う（P-5・P-12）。
  - `index-1` が latest になる。
  - `verify` が成功する。
  - ジョブの `GITHUB_TOKEN` は読み取りだけ。
- **停止条件：** `GITHUB_TOKEN` で書き込みが行われた。タグが Publisher App 以外の主体で作られた。
- **証拠：** 実行の URL、Release の JSON（`author`・`immutable`）、タグを作った主体（監査ログ）。
- **判定：** NOT VERIFIED

### IT-10 署名の不正
- **事前条件：** IT-09 と同じ。
- **操作：** 1 バイト変えた署名の公開の PR を出す。
- **期待結果：** `validate`（`publish-check`）が `CS_REG_SIGNATURE_INVALID` で拒否する。マージしても、`check` で止まり、公開されない。
- **停止条件：** Release ができた。
- **証拠：** ログ。
- **判定：** NOT VERIFIED

### IT-11 immutable な Release
- **事前条件：** IT-09 で公開した Release がある。immutable releases が有効。
- **操作：** アセットを差し替える。タグを移す。Release を削除しようとする（admin・メンテナー・Publisher App のそれぞれで）。
- **期待結果：** アセットとタグの変更は拒否される（P-10）。削除の可否を記録する（P-17）。
- **停止条件：** アセットが差し替えられた。
- **証拠：** API の応答。
- **判定：** NOT VERIFIED

### IT-12 下書きの検証の失敗では公開しない
- **事前条件：** IT-09 と同じ。
- **操作：** テスト用のワークフローの変形で、下書きのアセットを 1 つ変える（または 1 つ足す）。
- **期待結果：** `release` が「left as a draft」で止まる。公開されず、latest にならない。タグはまだ無い（P-8・P-9）。要約に記録される。
- **停止条件：** 下書きが公開された。
- **証拠：** Release の一覧、要約。
- **判定：** NOT VERIFIED

### IT-13 アセットの digest の不一致
- **事前条件：** IT-12 と同じ。
- **操作：** 記録した SHA-256 と違う digest になるように、アセットを差し替える（IT-12 と同じ方法）。
- **期待結果：** 公開されない（IT-12 と同じ）。
- **停止条件：** 公開された。
- **証拠：** 下書きの JSON（`digest`）。
- **判定：** NOT VERIFIED

### IT-14 公開の途中の失敗
- **事前条件：** IT-09 と同じ。
- **操作：** `release` の途中で失敗させる。例えば、Publisher App のインストールを一時的に停止する。
- **期待結果：** 失敗が要約に記録され、何も自動では削除されない。下書きが残った場合は、GOVERNANCE.md「Failed publication」の手順で扱う。
- **停止条件：** Release が自動で削除・上書きされた。
- **証拠：** 要約、Release の一覧。
- **判定：** NOT VERIFIED

### IT-15 同じ serial での再実行と latest
- **事前条件：** IT-09 の後。
- **操作：**
  1. 公開のワークフローを再実行する。
  2. 下書きが残った状態でも再実行する。
  3. 次の serial を公開する。
- **期待結果：**
  - 再実行は、`check` の「already published」か、`release` の「already exists」で止まる。
  - 次の serial は latest になる（P-11）。
- **停止条件：** 同じ serial が 2 回公開された。
- **証拠：** ログ、Release の一覧。
- **判定：** NOT VERIFIED

### IT-16 古い serial の拒否
- **事前条件：** serial 2 を公開済み。
- **操作：** serial 1 の index の公開の PR を出す。
- **期待結果：** `publish-check` が拒否する（serial は直前の次でなければならない）。
- **停止条件：** 公開された。
- **証拠：** ログ。
- **判定：** NOT VERIFIED

### IT-17 Linux CI
- **事前条件：** CreateScript のリポジトリへの push の許可。
- **操作：** `ci.yml` を実行する。
- **期待結果：**
  - `verification-metadata.xml` の strict な検証を含めて、全テストが成功する。
  - `WorkflowHarnessTest` が、スキップされずに実行される（ランナーの python3 に PyYAML があるか、確かめる）。
- **停止条件：** 依存の検証の失敗（改ざんの疑い）。
- **証拠：** CI の実行の URL、テストの結果。
- **判定：** NOT VERIFIED

### IT-18 実際の HTTPS でのインストール
- **事前条件：** テスト用のクライアントのビルド（W7）、IT-09 で公開した index。
- **操作：**
  1. `createscript install <name>@<version>` を実行する。
  2. 改ざんを試す（テスト用のリポジトリの別の Release を latest にする）。
  3. ネットワークを切って、オンラインのまま実行する。その後、`--offline` で実行する。
- **期待結果：**
  - インストールが成功する。
  - 古い index を latest にしても、保存した状態のあるクライアントは、巻き戻しとして拒否する。
  - オンラインで失敗したときは `--offline` を案内する。
- **停止条件：** 署名の検証を通らない index でインストールできた。
- **証拠：** コマンドの出力、キャッシュの `trusted.json`。
- **判定：** NOT VERIFIED

### IT-19 yanked・removed のパッケージ
- **事前条件：** 公開済みの index。
- **操作：** 取り下げの PR と削除の PR を公開し、インストールと復元を試す。
- **期待結果：** 新しいインストールは拒否される。取り下げは警告して復元し、削除は復元も拒否する。
- **停止条件：** 削除したパッケージがインストールされた。
- **証拠：** 出力。
- **判定：** NOT VERIFIED

### IT-20 オフラインでの復元と、L-5 の残存リスクの確認
- **事前条件：** ロックファイルのあるプロジェクトと、キャッシュ。macOS と Linux のテスト用の機械。
- **操作：**
  1. ネットワークを切って `install --offline` を実行する。
  2. `CREATESCRIPT_L5_RACE=1 ./gradlew :createscript-install:test --tests '*L5RaceTest*'` を実行する。
- **期待結果：**
  - ロックファイルから復元できる。index が無ければ「確かめられない」と示す。
  - `L5RaceTest` では、誤った成功が 0、既存のファイルの変更が 0。外への書き込みは、起こりうるもの（**L-5 は未解決**）として、回数を記録する。
- **停止条件：** 誤った成功、または既存のファイルの変更が起きた（L-5 の悪化）。
- **証拠：** 出力の `AUDIT-L5` の行。
- **判定：** NOT VERIFIED

### IT-21 Publisher App 以外による `index-*` のタグの作成の拒否
- **事前条件：** ruleset A を適用済み（回避者はテスト用の Publisher App だけ）。
- **操作：** T-m2（write）と T-lead（admin）で、次のことを試す。
  - `git push origin index-99`
  - Release の API で、`tag_name: index-99` の Release を作る（下書き・公開の両方）
  - Checks App のトークンでも同じことを試す
- **期待結果：** すべて拒否される（P-4）。Publisher App のトークンでは作れる（P-5）。あわせて、ジョブの `GITHUB_TOKEN`（`contents: read`）で ruleset の一覧と詳細を読めるか、`bypass_actors` が返されるかを記録する（P-18・P-19）。
- **停止条件：** Publisher App 以外で作れた（**前提 P-4 が誤り**。architecture の 3 章の代替案へ）。
- **証拠：** git・API の応答、ruleset の評価の記録（rule insights）。
- **判定：** NOT VERIFIED

### IT-22 作成済みのタグの更新・削除の拒否
- **事前条件：** 公開済みの `index-*` がある。ruleset B を適用済み。
- **操作：** T-m2・T-lead・Publisher App のそれぞれで、タグの移動（force push）と削除を試す。
- **期待結果：** すべて拒否される（P-6・P-7）。
- **停止条件：** どれかが成功した。
- **証拠：** 応答。
- **判定：** NOT VERIFIED

### IT-23 Publisher App の鍵は、承認の後だけ使える
- **事前条件：** environment `publish`（承認者 T-m2・T-m3、自分の承認の禁止）。
- **操作：**
  1. 承認しないまま、`release` を待たせる。
  2. 公開の PR の作成者が、自分で承認しようとする。
  3. 別のワークフロー（main 以外のブランチから）で、`publish` の secret を読もうとする。
- **期待結果：** secret は承認の後のジョブにだけ渡る。自分の承認は拒否される。main 以外からは使えない（P-13）。
- **停止条件：** 承認の前、または main 以外で、secret が使えた。
- **証拠：** 実行の画面、ログ（secret の値は残さない）。
- **判定：** NOT VERIFIED

### IT-24 Publisher App のトークンの失効
- **事前条件：** IT-09 の後。
- **操作：** IT-08 と同じ方法。
- **期待結果：** 失効している（P-14）。
- **停止条件：** IT-08 と同じ。
- **証拠：** 401 の応答。
- **判定：** NOT VERIFIED

### IT-25 緊急の公開の停止
- **事前条件：** IT-09 の後。
- **操作：** `publish` のワークフローを無効にし、Publisher App のインストールを停止する。公開の PR をマージする。
- **期待結果：** 公開されない。公開済みの Release は残る。元に戻すと、公開できる。
- **停止条件：** 停止中に公開された。
- **証拠：** 画面、ログ。
- **判定：** NOT VERIFIED

### IT-26 Checks App ではタグ・Release を作れない
- **事前条件：** IT-01 と同じ。
- **操作：** テスト用の Checks App のトークン（checks: write）で、Release の作成とタグの作成を試す。
- **期待結果：** 権限が無いので拒否される。
- **停止条件：** 作れた。
- **証拠：** 応答（403）。
- **判定：** NOT VERIFIED

### IT-27 Publisher App では必須チェックを作れない
- **事前条件：** IT-09 と同じ。
- **操作：** テスト用の Publisher App のトークン（contents: write）で、check run の作成を試す。
- **期待結果：** 権限が無いので拒否される。Publisher App の check run は、ruleset main の必須チェックを満たさない（`integration_id` が違う）。
- **停止条件：** 必須チェックが満たされた。
- **証拠：** 応答。
- **判定：** NOT VERIFIED

### IT-28 テスト用の鍵が本番の経路で信頼されないこと
- **事前条件：** 本番の設定の CLI（公式の配布物と同じビルド）。
- **操作：** テスト用のリポジトリの index（テスト用の鍵で署名したもの）を、本番の CLI で使おうとする。
- **期待結果：**
  - 本番の CLI は、テスト用のリポジトリを読まない（URL は組み込み）。
  - 本番の鍵が無い間は、`install <名前>@<版>` を 69 で拒否する。
  - `RegistryTrust.official()` は `test-only-` の鍵を拒否する（ローカルの試験：`RegistryClientTest.productionConfiguration`）。
- **停止条件：** 本番の CLI がテスト用の index を受け入れた。
- **証拠：** 出力。
- **判定：** NOT VERIFIED（ローカルの単体テストは成功している）

## D. 結果の記録の様式

各試験について、次の表を 1 つずつ作り、証拠のリンクを付ける。

| 項目 | 記入 |
|---|---|
| 試験 ID・名前 | |
| 実施日・実施者 | |
| 事前条件（実際の設定の値） | |
| 操作（実行の URL） | |
| 期待結果 | |
| 実際の結果 | |
| 判定 | PASS / FAIL / NOT VERIFIED |
| 証拠の保存場所 | |
| 停止条件に当たったか | |
| 残存リスク | |

### 結果の一覧（記入用）

| ID | 名前 | 判定 | 証拠 | 残存リスク |
|---|---|---|---|---|
| IT-01 | 正常な PR の検証 | NOT VERIFIED（未実施） | | |
| IT-02 | 必須チェックの偽装 | NOT VERIFIED（未実施） | | |
| IT-03 | 承認の不足 | NOT VERIFIED（未実施） | | |
| IT-04 | 作成者による自己承認 | NOT VERIFIED（未実施） | | |
| IT-05 | 利益相反のある承認 | NOT VERIFIED（未実施） | | |
| IT-06 | PR の更新の後の古い承認 | NOT VERIFIED（未実施） | | |
| IT-07 | Checks App が入っていない | NOT VERIFIED（未実施） | | |
| IT-08 | Checks App のトークンの失効 | NOT VERIFIED（未実施） | | |
| IT-09 | 正常な公開（Publisher App） | NOT VERIFIED（未実施。ローカルの模擬の試験は成功） | | |
| IT-10 | 署名の不正 | NOT VERIFIED（未実施） | | |
| IT-11 | immutable な Release | NOT VERIFIED（未実施） | | |
| IT-12 | 下書きの検証の失敗 | NOT VERIFIED（未実施） | | |
| IT-13 | digest の不一致 | NOT VERIFIED（未実施） | | |
| IT-14 | 公開の途中の失敗 | NOT VERIFIED（未実施） | | |
| IT-15 | 同じ serial での再実行と latest | NOT VERIFIED（未実施） | | |
| IT-16 | 古い serial の拒否 | NOT VERIFIED（未実施） | | |
| IT-17 | Linux CI | NOT VERIFIED（未実施） | | |
| IT-18 | 実際の HTTPS でのインストール | NOT VERIFIED（未実施） | | |
| IT-19 | yanked・removed | NOT VERIFIED（未実施） | | |
| IT-20 | オフラインでの復元・L-5 | NOT VERIFIED（未実施。L-5 は未解決） | | |
| IT-21 | Publisher App 以外のタグの作成の拒否 | NOT VERIFIED（未実施。ruleset A のファイルはローカルの検査に通る） | | |
| IT-22 | タグの更新・削除の拒否 | NOT VERIFIED（未実施） | | |
| IT-23 | 鍵は承認の後だけ | NOT VERIFIED（未実施） | | |
| IT-24 | Publisher App のトークンの失効 | NOT VERIFIED（未実施） | | |
| IT-25 | 緊急の公開の停止 | NOT VERIFIED（未実施） | | |
| IT-26 | Checks App ではタグ・Release を作れない | NOT VERIFIED（未実施） | | |
| IT-27 | Publisher App では必須チェックを作れない | NOT VERIFIED（未実施） | | |
| IT-28 | テスト用の鍵が本番で信頼されない | NOT VERIFIED（未実施。ローカルの単体テストは成功） | | |

## E. リスクの登録簿（実環境テストに関わるもの）

| ID | リスク | 影響 | 対応 |
|---|---|---|---|
| RR-1 | **L-5（未解決）**：同じユーザーの権限のプログラムによる symlink の差し替えの競合。Java 21・macOS には開いたディレクトリを基準にする API が無い。インストール中に、プロジェクトの外へ新しいファイル・パッケージのディレクトリが作られる（観測では 0.2〜0.7%）。理論上は、マニフェスト・ロックファイルの置き換えの直前の窓で、既存のファイルを置き換えうる（観測されていない） | 利用者の機械のファイルシステム | `L5RaceTest` で再現・監視する（IT-20）。**GitHub の統合テストが成功しても、L-5 は解決しない**（別の問題） |
| RR-2 | GitHub の前提（P-1〜P-17）が誤っている | 設計の変更 | B の停止条件の 3。architecture の 3 章の代替案 |
| RR-3 | テストの秘密情報の漏えい | テスト環境の乗っ取り | B の停止条件の 2。テストの後に、鍵を破棄・App を削除 |
| RR-4 | テストの設定が本番に混ざる | 本番の信頼の境界の破れ | B の停止条件の 4。IT-28 |
| RR-5 | テストの操作が本番に及ぶ | 本番の破損 | B の停止条件の 1。テスト用のリポジトリの名前に Test を含め、操作の前に対象を確かめる |

## F. 注意

- テスト用の鍵・App・アカウントを、本番の設定に流用しない。テストの後に、App の秘密鍵と署名の鍵を破棄し、そのことを記録する。
- ローカルの模擬の試験（`WorkflowHarnessTest`）は、GitHub の実際の動作の代わりにはならない。

## G. 付録：Phase 8 までの番号との対応

Phase 7・8 の版では、シナリオを 1〜25 の番号で書いていた。今の ID との対応は次のとおり（正式な ID は IT-01〜IT-28 だけ）。

| 旧 | 今 |
|---|---|
| 1〜8 | IT-01〜IT-08 |
| 9 | IT-10 |
| 10 | IT-16 |
| 11〜15 | IT-11〜IT-15 |
| 16 | IT-17 |
| 17 | IT-18 |
| 18 | IT-19 |
| 19・20 | IT-20 |
| 21〜25 | IT-21〜IT-25 |
| （新） | IT-09、IT-26〜IT-28 |

