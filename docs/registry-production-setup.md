# CreateScript-Registry 本番環境の構築手順書

> **この手順書は準備のための文書です。** 2026-11-11（S8.3 Phase 6）の時点で、ここに書いた作業は **どれも実施していません**。
> 未実施のもの：リポジトリ・GitHub App・ruleset・environment・本番の鍵・共同メンテナー。
> 各手順の実施には、運営責任者（dog7dog）と共同メンテナーの承認が必要です。GitHub の画面や API の名前は 2026-11 時点の理解に基づいており、実地で確かめていません。
>
> 関連する文書：
> - `registry-template/GOVERNANCE.md`・`SIGNING.md`・`SECURITY.md`
> - `docs/registry.md`
> - `docs/audits/S8.3-Phase4-Security-Audit.md`・`S8.3-Phase5-Remediation.md`・`S8.3-Phase6-Release-Readiness.md`

## A. 事前準備

### A-1. 共同メンテナー 3 人以上の確保

- 運営責任者を含めて 3 人以上（`minimumMaintainers`）。それぞれについて、次を確認します。
  - GitHub のログイン名と **数値 ID**：`https://api.github.com/users/<login>` の `id`。本人が確認する。
  - 2 要素認証を有効にしていること。
  - 本人の書面（issue のコメント）での同意。
  - 利益相反（自分が持ち主のパッケージ、雇用関係）の申告。
- 3 人に満たない間は、レジストリは「閉じた」状態です。パッケージの登録・更新と index の公開は、ツールが拒否します。

### A-2. 役割と責任

| 役割 | 人数 | 責任 |
|---|---|---|
| 運営責任者（lead） | 1 | 運営の変更の承認者または作成者、GitHub の設定の管理、鍵の儀式の取りまとめ |
| メンテナー | 3 人以上（lead を含む） | PR の審査（作成者以外の 2 人の承認）、`publish` environment の承認 |
| 署名者（signer） | 2 人以上を推奨 | オフラインの鍵で index に署名する。紛失に備えて、鍵の保管者を複数にする |
| App の鍵の管理者 | lead | GitHub App の秘密鍵の登録とローテーション |

**同じ人の兼務で、2 人承認が崩れないようにします。** 公開の PR の作成者（署名者）は、自分の PR を承認できません。

### A-3. GitHub Organization の必要性

| 項目 | 個人アカウント | Organization |
|---|---|---|
| リポジトリの最高権限 | 持ち主 1 人（設定を常に変えられる） | 複数の owner に分散できる |
| チーム単位の CODEOWNERS | 使えない（個人を並べる。ツールは個人だけを受け入れる） | 使える（ただし現在のツールはチームを拒否する。使う場合はツールの変更が必要） |
| ruleset の回避の監視 | 監査ログは限定的 | Organization の監査ログ |

**推奨：** Organization に置き、owner を 2 人以上にする。ただし、Organization でも owner は ruleset を変えられます（GOVERNANCE.md）。

### A-4. 本番の GitHub リポジトリの作成条件（すべてを満たしてから作成する）

- A-1 の 3 人がそろい、同意が記録されている。
- リポジトリの名前を確定し、クライアントの `RegistryTrust.OFFICIAL_REPOSITORY` と一致させる（現在は仮の値 `dog7dog/CreateScript-Registry`。変える場合はコードの変更と審査が必要）。
- `TOOL_COMMIT` に使う、審査済みの CreateScript のコミットが決まっている（そのコミットで、全テストと Linux CI が成功していること）。

## B. GitHub リポジトリ

1. **作成：** 空の public リポジトリを作る。README の自動作成はしない。
2. **テンプレートの配置：** CreateScript の `registry-template/` の中身を、リポジトリのルートに置く。`registry-template/.github/` はルートの `.github/` になる。
3. **立ち上げのコミット：** ruleset を適用する前に、運営責任者が行う。これだけが、2 人の承認を経ないコミットです。issue に記録する。
   - `maintainers.json` に 3 人（ログイン名・数値 ID）を書く。
   - `.github/CODEOWNERS` を `* @lead @m2 @m3` にする。
   - 確認：`createscript-registry-tool check-repo --repo .` が `notice: the registry is open (3 maintainers)` と `OK` を出すこと。
4. **Releases：** Settings で immutable releases を有効にする。
5. **Actions の権限：**
   - workflow permissions：read-only（既定）。PR の作成・承認を許さない。
   - 許すアクション：「SHA で固定したアクションだけ」。checkout と setup-java。
   - fork の PR のワークフロー：**すべての外部の協力者** の実行に承認を求める。
6. **Collaborators：** メンテナーだけを `write` にし、運営責任者を admin にする。deploy key は作らない。
7. **Private vulnerability reporting：** 有効にする。
8. **environment：**
   - `registry-checks`：deployment branches は `main` だけ、承認者なし。中身は C 章の App の秘密鍵。
   - `publish`：必須の承認者はメンテナー、「prevent self-review」を有効、deployment branches は `main` だけ。
9. **ruleset：** C-4 の後に適用する。
   - `github-settings/main-ruleset.json`：`~DEFAULT_BRANCH`、2 承認、コードオーナーの承認、古い承認の取り消し、最後の push の承認、スレッドの解決、strict な必須チェック（App の ID つき）、線形の履歴、force push の禁止、削除の禁止、回避者なし。
   - `github-settings/index-tags-ruleset.json`：`index-*` のタグの削除・更新を禁止。
     - 作成は制限していない。制限すると、回避者のいない ruleset では、公開のワークフローもタグを作れなくなる。
     - 公開専用の App を回避者にする案と、設定だけでは防げないこと（書き込み権限で latest を変えられる）は、GOVERNANCE.md の「Index tags and releases」に記載。
10. **CODEOWNERS：** `* @…` の 1 行で、`maintainers.json` のメンテナーと完全に一致させる（ツールが検査する）。
11. **ワークフローの設定：** 審査を受ける PR で行う。この時点で ruleset が効いている。
    - `TOOL_COMMIT` を、審査済みのコミットにする。
    - `WRAPPER_JAR_SHA256` を、そのコミットの `gradle/wrapper/gradle-wrapper.jar` の SHA-256 にする。**Gradle が公開している、その版の wrapper の checksum と照合する**（Phase 6 では未照合）。

## C. GitHub App（CreateScript Registry Checks）

### C-1. 作成

- 運営責任者のアカウント（または Organization）で、GitHub App を作る。
  - Webhook：無効。
  - Callback URL：なし。
  - ユーザーの認可：なし。
  - インストールできる範囲：「このアカウントだけ」。

### C-2. 必要な権限（最小）

- Repository permissions：**Checks: Read and write** だけ。
  - Metadata: read は GitHub が必須として付ける。
  - 「Pull requests」などのほかの権限は付けない。PR の head は、ジョブの `GITHUB_TOKEN` で読む（GOVERNANCE.md の表で、App の権限とジョブの `GITHUB_TOKEN` の権限を分けて示している。Phase 7 で P6-L2 を修正）。
  - 権限やインストール先を変えるのは運営の変更で、承認を経てから行う（GOVERNANCE.md）。
- ワークフローは、さらにトークンを `{"repositories": ["<名前>"], "permissions": {"checks": "write"}}` に絞って発行する。

### C-3. インストール

- **レジストリのリポジトリだけ** にインストールする（「Only select repositories」）。

### C-4. App の ID と integration_id

1. App の設定のページで App ID を確認する。
2. リポジトリの変数 `REGISTRY_CHECKS_APP_ID` に、その ID を設定する。
3. `github-settings/main-ruleset.json` の 2 つの `integration_id`（雛形では `0`）を、その ID にする。
4. `createscript-registry-tool check-settings --ruleset github-settings/main-ruleset.json` が `OK` を出すことを確かめる。
5. ruleset を適用する。

### C-5. 秘密鍵の登録

1. App の秘密鍵（PEM）を 1 つ作る。
2. environment `registry-checks` の secret `REGISTRY_CHECKS_APP_KEY` に登録する。
3. **ダウンロードした PEM はすぐに安全に消す。** 手元にもリポジトリにも残さない。

### C-6. 秘密鍵のローテーション

- 新しい鍵を作り、secret を差し替える。そのあと、PR で `registry/validate` が報告されることを確かめてから、古い鍵を App の設定から削除する。
- 定期的なローテーション（例：90 日）と、メンテナーが交代したときのローテーションを行う。
- **漏えいの疑いがあるとき：**
  1. 直ちに App の設定で鍵を削除する。
  2. 未マージの PR のチェックを確かめ直す。
  3. 鍵の漏えいの期間にマージされた PR を監査する。

### C-7'. 公開専用の GitHub App（Phase 8 の推奨案。**未採用**）

- `index-*` のタグを作れる主体を限定するための案です。採用するには、承認、`publish.yml` と ruleset の変更、GitHub 上での試験（`docs/registry-github-integration-test.md` のシナリオ 21〜25）が必要です。
- 案の内容：
  - 2 つめの App「CreateScript Registry Publisher」を作る。権限は Contents: read and write だけで、レジストリのリポジトリだけにインストールする。
  - 秘密鍵は environment `publish` の secret にだけ置く（必須の承認者・自分の承認の禁止・main だけ）。
  - タグの ruleset を 2 つに分ける。
    - 更新・削除・non-fast-forward の禁止：回避者なし。
    - 作成の禁止：公開専用の App だけが回避者。
- この案でも残ること：別の名前の Release と `latest` の変更は防げない。管理者は ruleset を変えられる。App の鍵を持つ人はタグを作れる。
- 詳細は GOVERNANCE.md の「Proposed: a publishing GitHub App」。

### C-7. トークンの扱い

- インストールのトークンの有効期間は、GitHub が決める（約 1 時間）。
- ワークフローは、トークンを `::add-mask::` で隠し、チェックを 2 つ作るだけに使う。
- step を終えるとき（正常に終えたとき・失敗したとき・途中で抜けたときのすべて）に、`DELETE /installation/token` でトークンを失効させる（Phase 7、P6-L1）。失効に失敗したときは、秘密情報を含まない警告を出す。トークンはファイルに書かない。

## D. Ed25519 の署名

> **本番の鍵の生成と署名は、承認を得てから行う。Phase 6 では行っていない。**
> クライアントとツールには、本番の鍵を使うためのコードの変更が必要です（下の D-4）。

### D-1. 本番の鍵の安全な生成（鍵の儀式）

1. **場所と立会い：** ネットワークにつながっていない専用の機械（または、起動直後のライブ OS）で行う。署名者 2 人以上が立ち会い、手順を記録する。
2. **生成：** `openssl genpkey -algorithm ed25519 -out createscript-registry-key-1.pem`（PKCS#8 の PEM。ツールの `sign` が読める形式）。
3. **公開鍵：**
   - `openssl pkey -in createscript-registry-key-1.pem -pubout -outform DER | tail -c 32 | base64` で、32 バイトの公開鍵を Base64 にする。
   - 立会人が、それぞれ SHA-256 の指紋を記録する。
4. **鍵の ID：** `createscript-registry-key-1`（`registry.json` の `signingKeyIds` と一致させる。`test-only-` で始めない）。
5. **小さな位数の鍵の確認（P4-I1）：** 公開鍵が、すべて 0 などの特殊な値でないことを、目視とツールで確かめる。

### D-2. 秘密鍵のオフラインの保管

- 暗号化した媒体（例：パスフレーズつきの PKCS#8、またはハードウェアの鍵）に保存する。
- 2 か所以上の物理的に離れた場所に置き、保管者を 2 人以上にする。
- **GitHub（secret を含む）・クラウド・ネットワークにつながった機械には置かない**（SIGNING.md）。

### D-3. 公開鍵の配布

- `keys/trusted-keys.json` に `{"keyId": "createscript-registry-key-1", "publicKey": "<32 バイトの Base64>"}` を加える。これは運営の PR で行い、2 人が承認する。
- 指紋を、レジストリの README と CreateScript のリリースノートに載せる。

### D-4. クライアントへの組み込み（コードの変更と審査が必要）

- `createscript-install` の `RegistryTrust` の `PRODUCTION_KEYS` に、同じ鍵 ID と公開鍵を加える。
  - `official()` は `test-only-` の鍵を拒否する。
- `createscript-registry-tool` の `IndexPublishing.PRODUCTION_SIGNING_ENABLED` を `true` にする。
- どちらも、CreateScript のリポジトリの審査を経てリリースする。
- テスト：本番の鍵の ID と公開鍵でしか通らないこと。テスト用の鍵が混ざらないこと。

### D-5. 鍵のローテーション

1. 新しい鍵を D-1 の手順で作る。
2. `trusted-keys.json` と、クライアントの `PRODUCTION_KEYS` に、新旧の両方を載せた版を出す。
3. しばらくの間、新旧の両方で署名する（index は最大 4 つの署名を持てる）。
4. 古いクライアントの利用が十分に減ったら、古い鍵を外す。

### D-6. 鍵の漏えいのときの対応

1. 新しい鍵を作る。
2. 古い鍵を外したクライアントを、緊急のリリースとして出す。
3. `trusted-keys.json` から古い鍵を外し、新しい鍵で署名した index を公開する。
4. 漏えいの期間に公開された index と Release を監査する。
5. 利用者に告知する（SECURITY.md）。

- 漏れた鍵でも、クライアントが受け入れ済みの SHA-256 と依存は変えられません（履歴の比較）。しかし、新しい版の追加はできます。

## E. 公開の前の実地の検証（**試験用のリポジトリ** で行う。本番のリポジトリでは行わない）

| # | 検証 | 期待する結果 |
|---|---|---|
| E-1 | 正常な登録の PR（テスト用のパッケージ、2 人の承認、`recheck`） | `registry/validate`・`registry/approvals` が App から成功で付き、マージできる |
| E-2 | 偽装のチェック：外部の協力者の fork から、`registry/validate`・`registry/approvals` という名前のジョブを持つワークフローを加えた PR | ワークフローは承認待ちになる。実行させても、必須チェックは満たされない（App の ID が違う）。`validate` は `.github/` の変更を拒否する |
| E-3 | 承認が 1 人 | `registry/approvals` が失敗し、マージできない |
| E-4 | 承認の後に push する | GitHub が古い承認を取り消す。古い実行を再実行すると「outdated」で失敗し、何も報告しない |
| E-5 | 作成者・パッケージの持ち主の承認 | 数えない |
| E-6 | 運営責任者が作成した PR | ほかの 2 人のメンテナーの承認でマージできる（デッドロックしない） |
| E-7 | 署名が不正な index の公開の PR（試験用の鍵。`trusted-keys.json` の値も試験用のもの） | `publish-check` が拒否する |
| E-8 | serial を巻き戻した、または再利用した index | 拒否する |
| E-9 | 公開 | `publish` environment の承認の後に Release ができ、`verify` で immutable と digest が確認できる。アセットの差し替え・削除ができないこと |
| E-10 | 同じ serial の再公開・失敗の後の再実行 | `publish-check` が「already published」で止まる。`release` は、同じ serial の Release（下書きを含む）があれば何も作らずに止まる。タグの ruleset が更新を拒否する。途中で止まったときの対応は GOVERNANCE.md の「Failed publication」 |
| E-11 | GitHub API の制限・失敗 | ジョブが失敗し、チェックは成功にならない |
| E-12 | Linux CI | `ci.yml` が、`verification-metadata.xml` の strict の検証で成功する |
| E-13 | 実際の GitHub の HTTPS でのインストール（試験用のクライアントのビルドに、試験用のリポジトリと試験用の鍵を組み込む） | `createscript install <名前>@<版>` が成功する。改ざん・期限切れ・ネットワーク障害の動作が、ドキュメントどおり |

- 試験用の鍵は `test-only-` で始め、本番には使わない。
- 試験が終わったら、試験用のリポジトリと App を削除する。

## F. 正式公開の GO / NO-GO チェックリスト

すべてに「はい」と記録できたときだけ GO。1 つでも「いいえ」や「未確認」があれば NO-GO。

| # | 項目 | 根拠として残すもの |
|---|---|---|
| F-1 | 共同メンテナー 3 人以上が確定し、`maintainers.json` と CODEOWNERS が一致している | issue、`check-repo` の出力 |
| F-2 | 組織の形（個人か Organization か）と owner が決まっている | 決定の記録 |
| F-3 | リポジトリの名前が、クライアントの `OFFICIAL_REPOSITORY` と一致している | コードの差分 |
| F-4 | GitHub App が最小の権限で、このリポジトリだけにインストールされ、鍵が environment にだけある | 設定の画面の記録 |
| F-5 | ruleset の `integration_id` が App の ID で、`check-settings` が OK | 出力 |
| F-6 | ruleset・タグの ruleset・environment・immutable releases・Actions の設定が適用されている | 設定の記録 |
| F-7 | `TOOL_COMMIT` と `WRAPPER_JAR_SHA256` が設定され、wrapper が公式の checksum と一致している | PR、照合の記録 |
| F-8 | E-1〜E-13 のすべてが、試験用のリポジトリで期待どおりだった | 試験の記録 |
| F-9 | 本番の鍵の儀式が承認され、記録され、公開鍵の指紋が公開されている | 儀式の記録 |
| F-10 | クライアントとツールに本番の鍵を組み込んだ版が、審査されてリリースされている | リリース |
| F-11 | Linux CI の全テストが成功している | CI の記録 |
| F-12 | 未解決の指摘（L-5 など）を、既知の制限として文書に載せ、受け入れることにメンテナーが合意している | 合意の記録 |
| F-13 | 最初の index（serial 1）を、2 人の承認と `publish` environment の承認で公開する手順を、立会いのもとで行う | 記録 |
