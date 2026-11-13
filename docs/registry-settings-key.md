# Ruleset スナップショットの署名鍵（設定の証明用の鍵）の運用

> **状態（2026-11-13、S8.3 U-1）：設計を採用し、ツールにローカルで実装しました。**
> - 鍵は、本番もテストも、まだ実ファイルとして作っていません。
> - GitHub 上では、この運用を試していません。
> - 本番の鍵での署名は、鍵の作成の儀式が承認されるまで、ツールが拒否します（`RulesetSnapshots.PRODUCTION_SIGNING_ENABLED = false`）。
> - ツールに固定した信頼済みの指紋（`SettingsKeyPins.OFFICIAL`）は空です。そのため、本番の経路では、どのスナップショットも信頼されず、公開は止まります。
>
> 関連：
> - `docs/registry-github-architecture.md`（案 B'）
> - `docs/audits/S8.3-Phase10B2A-Solo-Integration.md`（P-18〜P-21 の実測）
> - `registry-template/SIGNING.md`（index の署名鍵）

## 1. 採用した方針

| 項目 | 決定 |
|---|---|
| 鍵 | **案 B：** index の署名鍵とは別の、スナップショット専用の Ed25519 鍵。オフラインで保管する |
| 信頼の起点 | **T3：** レジストリの `keys/settings-keys.json` の公開鍵と、ツールのソースに固定した指紋（`SettingsKeyPins`）が、一致すること |
| 期限 | スナップショットの `expires` は、記録から **最大 180 日** |
| main の ruleset | 将来、検証の対象に含める。**まだ実装していない。そのため、main の ruleset の保護については、安全だとは言わない**（6 章） |
| 秘密鍵の置き場所 | GitHub Actions には置かない。ワークフローは公開鍵だけで検証する |

## 2. 形式

**スナップショット（`github-settings/verified-rulesets.json`、`snapshotVersion: 2`）**
- 正規化した JSON（キーを並べ替え、空白を入れない）。
- 項目は、ちょうど次の 6 つ：`snapshotVersion`・`repository`・`recordedAt`・`expires`・`recordedBy`・`rulesets`。
  - `recordedAt` と `expires` は、秒までの UTC（`YYYY-MM-DDTHH:MM:SSZ`）。
- `rulesets` は、A・B の 2 つ。それぞれ 11 項目（`id`・`name`・`target`・`source_type`・`source`・`enforcement`・`conditions`・`rules`・`bypass_actors`・`created_at`・`updated_at`）。
- `record-ruleset-snapshot --valid-days N`（1〜180、既定は 180）で作る。

**期限の検査（`check-ruleset-snapshot`・`sign-ruleset-snapshot`）**
- `recordedAt ≤ 今 + 5 分`（未来の記録は失敗）
- `recordedAt < expires`
- `expires − recordedAt ≤ 180 日`
- `今 < expires`（期限ちょうど、または期限の後は失敗）

**署名の形式**
- 署名ファイル（`github-settings/verified-rulesets.json.sig`）の形は、`index.json.sig` と同じ（`signatureVersion: 1`、署名は最大 4 つ、同じ鍵の ID は 1 回だけ）。
- 署名の対象は、次のバイト列。
  - `CreateScript-Registry ruleset snapshot signature v1`
  - 0x00
  - 鍵の ID
  - 0x00
  - スナップショットのバイト列
- 先頭の文字列は「この署名の方式の版」を表し、スナップショットの版（`snapshotVersion`）とは別。スナップショットの版は、署名されるバイト列の中に含まれる。

**版 1 と版 2 の互換**

| 対象 | 版 1（`18ffc7f`） | 版 2（U-1） | 互換 |
|---|---|---|---|
| スナップショット本体（`verified-rulesets.json`） | 5 項目（`expires` なし）、`recordedAt` は秒以下も可 | 6 項目（`expires` 必須）、時刻は秒までの UTC | **非互換。** 版 2 のツールは版 1 を拒否する（「snapshotVersion must be 2」）。版 1 のツールは版 2 を拒否する（項目が違う）。記録し直して、署名し直す必要がある |
| 署名ファイル（`verified-rulesets.json.sig`） | `signatureVersion: 1`（index と同じ形） | 同じ | 互換（形は変わらない） |
| 署名の対象のバイト列の作り方 | 先頭の文字列・鍵の ID・スナップショットのバイト列 | 同じ | 互換。ただし、署名の対象のスナップショット本体が版で違うので、版 1 の署名は版 2 では使えない |
| 鍵の一覧（`keys/settings-keys.json`） | `trusted-keys.json` と同じ形 | 同じ。本番の鍵は、ツールに固定した指紋とも一致すること（T3） | 形は互換。信頼の条件は厳しくなった |

- 版 1 は、ローカルの実装（`18ffc7f`）とその試験にだけあった。GitHub に置いたり、本物の鍵で署名したりしたスナップショットは無い。そのため、移行の作業は要らない。

**鍵の ID**
- 本番：`createscript-registry-settings-<年>-<番号>`（例：`…-2027-1`）。
- テスト：`test-only-settings-<番号>`。

**指紋**
- 32 バイトの公開鍵の SHA-256 を、小文字の 16 進で表したもの。

## 3. 検証の規則（`check-ruleset-snapshot`）

1. `keys/settings-keys.json` を読む。
   - テスト用の鍵（`test-only-`）は、テスト専用のビルド（`createscript-registry-tool-TEST-ONLY`）でだけ受け入れる。
   - 本番の鍵は、**すべて** `SettingsKeyPins` に同じ指紋で載っていなければならない。
   - 指紋の一覧が空なら、「このビルドは設定の鍵を固定していない」として失敗する。
2. 署名ファイルを読む。
   - 署名は 1 つ以上必要。
   - **並んでいる署名はすべて**、一覧にある鍵の、正しい署名でなければならない。同じ鍵の ID が 2 つある、一覧に無い（失効した）鍵の署名がある、のどちらでも失敗する。
3. スナップショットを検査する。形・版・リポジトリ・期限・方針（雛形の A・B と App の ID）。
4. 実際の ruleset と照合する。id・enforcement・conditions・rules・時刻・`current_user_can_bypass`・余分な ruleset（案 B'）。

## 4. 運用

| 段階 | 手順 |
|---|---|
| 生成 | オフラインの機械で、運営責任者が作り、2 人目のメンテナーが立ち会う（`openssl genpkey -algorithm ed25519`、PKCS#8）。**事前の承認が必要。1 人体制のあいだは、本番の鍵を作らない** |
| 保管 | 暗号化して、2 つのオフラインの媒体に保存する。2 か所に分け、別々のメンテナーが持つ。index の鍵とは別の媒体にする |
| 公開鍵の配布 | ① `keys/settings-keys.json` に、PR で加える<br>② 指紋を `SettingsKeyPins` に加え、CreateScript のリリースを出す<br>③ ワークフローの `TOOL_COMMIT` を、そのリリースのコミットに上げる<br>④ 指紋を SIGNING.md に記録する |
| 記録 | 管理者が、自分のトークンで A・B を GET する → `record-ruleset-snapshot` |
| 署名 | ① スナップショットを媒体でオフラインの機械へ移す<br>② `sign-ruleset-snapshot` を実行する。署名の前に、方針・期限を検査し、要約（スナップショットの SHA-256、リポジトリ、記録した人と時刻、期限、各 ruleset の id・名前・対象・規則・回避者・updated_at、鍵の ID）を表示する。方針や期限に反するものには署名しない。本番の鍵の ID は、儀式の承認まで、鍵のファイルを読む前に拒否する<br>③ 署名する人が要約を確かめる<br>④ `.sig` を戻し、スナップショットと一緒に PR にする |
| 定期の見直し | 期限（最大 180 日）までに、ruleset を見直し、記録・署名し直す。期限が切れると、公開は止まる |
| 鍵の交換 | ① 新しい鍵の儀式<br>② 新しい公開鍵を、一覧と `SettingsKeyPins` に加える<br>③ 新しい鍵で署名する（`--existing-sig` で既存の署名に加えることもできる）<br>④ **古い鍵の署名を外す**<br>⑤ 古い鍵を、一覧と `SettingsKeyPins` から外す<br>⑥ 古い鍵を破棄する<br>**④の前に⑤を行うと、知らない鍵の署名が残り、公開が止まる**（ツールが手順の注意を表示する） |
| 失効（漏えい） | ① 直ちに、その鍵を一覧から外す PR を出す。それだけの署名のスナップショットは通らなくなり、公開が止まる<br>② ruleset の履歴の API（admin）で、記録の後の変更を確かめる<br>③ 新しい鍵の儀式<br>④ 記録・署名し直す<br>⑤ `SettingsKeyPins` を更新したリリースを出す<br>⑥ 公表する |
| 紛失 | 新しい鍵ができるまで、公開は止まる（安全側）。儀式からやり直す |
| ruleset の変更 | `updated_at` が変わり、公開が止まる → 履歴の API で差分を確かめる → 記録 → 署名 → PR。元に戻した変更でも `updated_at` は戻らない（P-20）ので、記録し直す必要がある |
| 誤った署名 | 方針に反する内容は、署名の段階と検証の段階の両方で拒否する。方針には合うが実際と違う内容は、`updated_at` などとの照合で通らない。署名した機械を疑う場合は、鍵を交換する |

**本番とテストの分離**
- テスト用の鍵（`test-only-`）は、本番のツールが拒否する（`keys/settings-keys.json` にあるだけで失敗する）。
- テスト用の鍵は、テスト専用のビルドでだけ使える。テスト専用のビルドは、公式のレジストリのリポジトリでは動かない。
- 本番の鍵は `SettingsKeyPins` に固定する。テスト用の鍵は固定しない（実行のたびに作るため）。
- 雛形には、鍵の一覧もスナップショットも置かない。

## 5. 信頼の起点の限界（TOOL_COMMIT とワークフローの差し替え）

T3 で守れるのは、**レジストリのリポジトリの権限だけを持つ攻撃者**からです。たとえば、管理者のアカウントが乗っ取られた場合を考えます。

- `keys/settings-keys.json` の鍵を自分の鍵に替えても、ツールに固定した指紋と一致しないので、公開は止まる。

しかし、次の方法で信頼の起点を差し替えられます。

1. **ワークフローの `TOOL_COMMIT` を書き換える。** レジストリの `publish.yml` は、`TOOL_REPOSITORY`（`dog7dog/Create_Script`）の `TOOL_COMMIT` からツールを作る。管理者は、main の ruleset を外せば、`TOOL_COMMIT` を別のコミットに替えられる。
   - 替え先は、指紋の固定が無い・別の指紋を固定した、古いコミットやフォーク由来のコミット。
2. **ワークフローそのものを書き換える。** `check-ruleset-snapshot` を呼ばないワークフローにすれば、照合自体を飛ばせる。
3. **`TOOL_REPOSITORY` を、攻撃者の管理するリポジトリに替える。**

**緩和策と、その限界**
- `TOOL_COMMIT`・`TOOL_REPOSITORY`・ワークフローの変更は、main の ruleset（2 人の承認・CODEOWNERS）の対象になる。
- ただし、admin は ruleset を外せる。main の ruleset の変更は、まだ照合していない（6 章）。
- 結局のところ、**レジストリのリポジトリの admin の権限を乗っ取られると、公開の流れの検査は迂回できる**。
- その場合でも、利用者の `createscript` は、組み込んだ index の署名鍵で index を検証する（Phase 3）。そのため、index の署名鍵が無ければ、正しい index は作れない。
- 防げないのは、ruleset で守っている `index-*` のタグや latest の Release の扱い（ロールバック・差し替えの試み）です。ただし、ロールバックは、クライアントが状態を保存していれば拒否する。
- この限界は、index の信頼の設計（`docs/registry-github-architecture.md` 9 章）と同じ前提です。
- ワークフローの変更や `TOOL_COMMIT` の変更を、レジストリの外から見張る仕組み（例：CreateScript の CI が、レジストリの `publish.yml` の SHA-256 を照合する）は、まだありません。

## 6. main の ruleset の検証（未実装）：必要な項目、API の挙動、ギャップ

**記録して照合すべき項目**
- 雛形の `main-ruleset.json` と、`GitHubSettings.rulesetProblems` が見ている項目。
  - 対象：`~DEFAULT_BRANCH`、enforcement、回避者（空）
  - 規則：`deletion`・`non_fast_forward`・`required_linear_history`
  - `pull_request` の付加項目：`required_approving_review_count` が 2 以上、`dismiss_stale_reviews_on_push`・`require_code_owner_review`・`require_last_push_approval`・`required_review_thread_resolution`
  - `required_status_checks`：`registry/validate`・`registry/approvals` と、それぞれの `integration_id` が Checks App であること、`strict_required_status_checks_policy`

**API の挙動**

| API | 分かっていること | 未確認 |
|---|---|---|
| `GET /repos/{r}/rulesets/{id}`（target が branch） | タグの ruleset と同じ形。`bypass_actors` は、書き込める利用者にだけ返る [文書]。読み取りのトークンでの挙動は、タグの ruleset で実測した（P-19・P-21） | ブランチの ruleset で、`GITHUB_TOKEN` に返る項目（同じと推定するが、実測していない） |
| `GET /repos/{r}/rules/branches/{branch}` | そのブランチに有効な規則を返す。evaluate・disabled の ruleset は含まない。ブランチが無くてもよい [文書] | 応答の項目（`ruleset_id`・`ruleset_source` など）は、文書に載っていない。回避者・`current_user_can_bypass` は含まれない [文書]。必要な権限も、文書に書かれていない |
| `pull_request`・`required_status_checks` の付加項目 | 文書の形（上の項目）[文書] | GitHub が既定値を補って返すか（例：`allowed_merge_methods`、`dismissal_restriction`）。補う場合、記録との完全な一致での比較が不安定になる |

**実装の前のギャップ**
1. スナップショットの形を版 3 に上げ、`rulesets` に main を加える必要がある。A・B の 2 つ固定の検査を変えることになる。
2. 方針の検査（`GitHubSettings.rulesetProblems`）を、実際の応答にも使えるようにする必要がある。応答では、知らない付加項目や既定値が加わる可能性がある。
3. `current_user_can_bypass` の期待値：main では、`GITHUB_TOKEN`・Publisher App とも `never` を期待するが、実測していない。
4. ブランチの ruleset の、記録に無いもの（余分なもの）の扱い。タグと同じく、main に当たり得るものは失敗とするか。
5. **実測が必要：** テスト用のリポジトリに main の ruleset を作り（承認が必要）、所有者・App・`GITHUB_TOKEN` での見え方を確かめる。
6. それまでは、**main の ruleset が黙って弱められても、公開の前の検査では見つからない。** これは既知の残存リスクとして扱う。

## 7. 試験

- **単体試験：**
  - `SettingsKeyTest`（7 件）：版 2・期限・時刻の前後関係と境界（5 分の許容・180 日・期限ちょうど）、本番の既定がどの鍵の構成でも失敗すること、T3（固定あり・空・不一致・外された鍵・本番でのテスト用の鍵）、署名の重複・知らない鍵・失効・鍵の交換の順序、署名の関数（別の秘密鍵・一覧に無い鍵の ID・既存の署名への追加・本番の鍵の拒否）、署名のコマンド（要約・期限切れ・方針違反・本番の鍵のファイルを読まないこと）
  - `RulesetSnapshotTest`（8 件、版 2 に合わせた）
  - 鍵は、試験の中でメモリーの中だけに作る。
- **ワークフローの模擬試験（harness）：**
  - 正常系は、テスト専用のビルドと、テスト用の鍵の ID で行う。
  - 本番のツールが、テスト用の鍵を拒否すること、固定していない鍵を拒否すること（T3）を確かめる。
  - harness は、既存（`18ffc7f`）と同じく、作業用の一時ディレクトリで使い捨ての鍵を作り、終わったら消す。
- **未実施：** GitHub の実環境での運用の試験（テスト用の鍵の作成 → 記録 → 署名 → 配置 → 照合用のワークフローの実行）。それぞれ承認が必要。
