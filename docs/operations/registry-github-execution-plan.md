# S8.3 GitHub 実環境の統合試験の実行計画（承認待ち）

> 状態（2026-11-14）: **計画のみ。どの操作も未実施。** 各操作は、実行の前に **操作ごとに** 承認を得る（1 つの承認で次の操作へ進まない）。
> 本番のリポジトリ `dog7dog/Create_Script` と本番のレジストリへの変更は、この計画には含めない（G5 の扱いを参照）。
> 鍵・トークン・JWT・認証ヘッダーは、ログ・報告・会話に出さない。秘密鍵は openssl にパスで渡すだけで、中身を読まない。

## 0. 対象と前提

| 項目 | 値 |
|---|---|
| 対象のリポジトリ | `dog7dog/CreateScript-Registry-Test`（public、TEST ONLY。main = `fb48da0`） |
| 既存の設定 | タグの ruleset A `27176846`（作成）・B `27176940`（更新・削除）・C `27179117`（p20 の探査用）。ブランチの ruleset は 0 個、main の旧式の保護なし |
| App | Checks `5724854`（installation `181170315`、checks:write）、Publisher `5738726`（installation `181470654`、contents:write、selected） |
| 既存のタグ・Release | `index-pt1-ref`・`index-pt1-rel`（どちらも `4bab974`）、Release `443835736`（prerelease） |
| Actions | 有効、既定のトークンは read。実行は 1 回（`43382792677`） |
| 用いる認証 | 所有者の gh（keyring）、App は JWT（秘密鍵はパスで渡す）とインストールのトークン（使い終えたら DELETE で失効） |

**2026-11-14 の読み取りだけの確認（GitHub API の GET のみ）**

| 確認 | 結果 |
|---|---|
| `GET /repos/{r}` | `default_branch: main`、public。`full_name` が一致（版 3 の `--repository-info` の実際の応答として使える形） |
| ruleset の一覧・詳細 | A・B・C の `updated_at` は記録と同じ（変化なし）。ブランチの ruleset は 0 個 |
| `GET /repos/{r}/rules/branches/main` | 0 件（main に効く規則は無い） |
| `GET /repos/{r}/branches/main/protection` | 404（旧式の保護なし） |
| 試験用のワークフロー | main の `ruleset-visibility-probe.yml` の SHA-256 が `b7862475…`（配置したものと同じ） |
| GitHub の公開の API 定義（rest-api-description 1.1.4） | `conditions.ref_name.include` は `~DEFAULT_BRANCH`・`~ALL` を受け付けると記載。`current_user_can_bypass` は `always`・`pull_requests_only`・`never`・`exempt` の 4 値（ツールは `never`／`always` 以外を失敗にするので、残りの 2 値でも誤って通らない） |
| 個人の非公開リポジトリ | ruleset の API は 403（Free では public でないと使えない）。レジストリは public が前提 |

**読み取りでは確かめられないもの（U-2 の前提 1〜4）**: ブランチの ruleset が 1 つも無いため、その応答の形・P-19・P-21・P-20 と、`GITHUB_TOKEN` での `GET /repos` は、下の G1〜G3 が要る。

## 1. 実行の順序

G1 → G3 → G2 → G4 →（任意）G7 → G6。G5 は本番のリポジトリに関わるため別扱い。どの操作でも、想定と違う応答が出たら、その場で止めて報告する（次の操作へ進まない）。

## 2. 操作

### G1. main の ruleset を無効の状態で作る（U-2 の前提 1）
- **操作:** `POST /repos/{r}/rulesets`。本文は `registry-template/github-settings/main-ruleset.json` で、`enforcement: "disabled"`、必須チェックの `integration_id` を `5724854` にしたもの（POST は 1 回だけ）。
- **確かめること:** `GET` の応答（所有者・取得の形）。`conditions.ref_name.include` が `["~DEFAULT_BRANCH"]` のまま返るか、補われる項目、`updated_at` の形。`record-ruleset-snapshot` の `mainProblems` に通るか（無効なので「有効でない」で止まるのが正しい）。
- **影響:** 無効なので、main への push・マージには効かない。
- **復旧:** `DELETE /repos/{r}/rulesets/{id}`（作った id だけ）。

### G3. ブランチの ruleset での P-20（U-2 の前提 3。最も重要）
- **操作:** G1 の ruleset に `PUT` で回避者（Publisher App `5738726`、`bypass_mode: always`）を加え、もう一度 `PUT` で `[]` に戻す（6 項目をすべて送る。タグの ruleset C で行った C-2・C-3 と同じ手順）。
- **確かめること:** 各 `PUT` の後に `updated_at` が変わること（戻しても元の値に戻らないこと）、ほかの項目が変わらないこと、履歴の版の数。
- **影響:** 無効の ruleset なので、push・マージには効かない。
- **復旧:** 記録した G1 の応答の内容で `PUT` し直す。異常があれば `DELETE`。

### G2. 試験用のワークフローの実行（U-2 の前提 2・4、ブランチの ruleset での P-18・P-19・P-21）
- **操作:** `POST /repos/{r}/actions/workflows/ruleset-visibility-probe.yml/dispatches`（`ref: main`、1 回）。配置済みの `fb48da0` の版（`contents: read` だけ）。
- **確かめること:** `GITHUB_TOKEN` で、ブランチの ruleset の一覧・詳細・`rules/branches/main`・`GET /repos` の `default_branch` が読めること。`bypass_actors` が返らないこと、`current_user_can_bypass` が `never` であること。ログにトークンが出ないこと。
- **影響:** 読み取りだけ。実行の記録が 1 つ増える。
- **復旧:** 不要（記録を消すなら `DELETE /repos/{r}/actions/runs/{id}`。別の承認）。

### G4. Publisher App のトークンでの読み取り（ブランチの ruleset の P-21）
- **操作:** JWT で `POST /app/installations/181470654/access_tokens`（`repository_ids` を対象のリポジトリだけ、`contents: write`）→ ruleset の一覧・詳細の `GET` → `DELETE /installation/token`（204、その後 401 を確かめる）。
- **確かめること:** Publisher App から main の ruleset が `current_user_can_bypass: never` に見えること（回避者を戻した後）。`bypass_actors` が返らないこと。
- **影響:** 一時的なトークンを作るだけ。リポジトリは変えない。
- **復旧:** トークンの失効（手順の最後で必ず行う。失敗したら 1 時間で期限切れ）。

### G7（任意）. main の ruleset を有効にする
- **操作:** `PUT` で `enforcement: "active"`。
- **確かめること:** `rules/branches/main` に規則が現れること、所有者の直接の push が拒否されること（GH013）。
- **影響:** **テスト用のリポジトリの main へ、所有者も直接 push できなくなる**（以後の配置は PR と 2 人の承認が要る。1 人体制では事実上マージできない）。G6 の前に行うかどうかは、そのときに決める。
- **復旧:** `PUT` で `enforcement: "disabled"`、または `DELETE`。

### G6. publish.yml の環境の一式（IT-01〜IT-28）
- **操作（それぞれ別の承認）:**
  1. 雛形の `validate.yml`・`publish.yml` をテスト用のリポジトリに追加する（commit・push。`TOOL_COMMIT` の扱いは G5）。
  2. environment `publish`（必須の承認者）と Secret（Checks・Publisher の秘密鍵、設定の証明用の **テスト用** の鍵）を作る。
  3. テスト用の鍵で署名したスナップショット（`github-settings/verified-rulesets.json` と `.sig`）を、PR で追加する。
  4. `docs/operations/registry-github-integration-test.md` の IT-01〜IT-28 を順に行う（最小 3 人の体制が要る試験がある）。
- **影響:** タグ・Release が作られる。**`index-*` のタグは ruleset B で削除できない**（後片付けには B を一時的に変える必要があり、それ自体が別の承認の要る操作）。
- **復旧:** ワークフローのファイルを消す commit、Secret・environment の削除、作った Release の削除。タグは B を `disabled` にしてから削除し、すぐ `active` に戻す（A・B の `updated_at` が変わるので、スナップショットを記録し直す）。

### G5. ツールの版（`TOOL_COMMIT`）の取得元
- 雛形のワークフローは、`dog7dog/Create_Script` の `TOOL_COMMIT` を取得してツールを作る。今の実装はローカルのブランチ `s8.0-local-imports` にだけあり、**push は本番のリポジトリへの変更になる**ため、この計画では行わない。
- **代案（推奨）:** テスト用の別のリポジトリ（例: `dog7dog/CreateScript-Tool-Test`、TEST ONLY）を作り、そこへ push して、テスト用のリポジトリのワークフローの取得元だけをそこに向ける。作成・push・取得元の変更は、それぞれ承認が要る。
- **復旧:** テスト用のツールのリポジトリを削除する（archive でもよい）。

## 3. 後片付け（すべての試験の後。承認が要る）
- ruleset C（`27179117`）、G1 の main の ruleset の削除。
- 試験で作ったタグ・Release（`index-pt1-*` を含む）を残すか消すかの判断（消すには B を一時的に変える）。
- App の installation の解除・App の削除（使い終えたら）。秘密鍵のファイル（`~/.createscript-test-keys/`）の削除は利用者が行う。

## 4. 止める条件
- 想定と違う HTTP の状態・応答の形、`updated_at` が変わらない（G3）、トークンがログに出た、本番のリポジトリに触れる必要が出た。いずれも、その場で止めて報告する。
