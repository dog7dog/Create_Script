# CreateScript-Registry GitHub 実環境テスト手順書

> **この文書は計画です。ここに書いたテストは、どれも実施していません（2026-11-11、S8.3 Phase 7 の時点）。**
>
> - 実施するには、運営責任者の明示の許可が必要です。テスト用のリポジトリ・App・鍵の作成も含め、GitHub への変更を伴います。
> - 本番の CreateScript-Registry では行わず、本番の鍵も使いません。
> - 前提になる文書：`docs/registry-production-setup.md`、`registry-template/GOVERNANCE.md`・`SIGNING.md`、`docs/audits/S8.3-Phase7-Remediation.md`。

## A. テスト環境

| 項目 | 内容 |
|---|---|
| リポジトリ | 本番とは別の **テスト専用のリポジトリ**（例：`<owner>/CreateScript-Registry-Test`。public。名前に Test を含める）。テストが終わったら削除するか、アーカイブする |
| GitHub App | テスト専用の App（例：「CreateScript Registry Checks (Test)」）。権限は Checks: read and write だけ。テスト用のリポジトリだけにインストールする。本番の App は使わない |
| 署名の鍵 | テスト専用の Ed25519 の鍵。鍵の ID は `test-only-` で始める（例：`test-only-integration-1`）。テスト用の機械で作り、テストの後に破棄する。**本番の公開鍵・秘密鍵は使わない**。本番の `RegistryTrust.official()` は `test-only-` の鍵を拒否する |
| テストの鍵の信頼 | テスト用のリポジトリの `keys/trusted-keys.json` に、テスト用の公開鍵を入れる（`publish-check` は、テストの鍵を拒否する。そのため、公開の流れの試験には、テスト用のツールのビルド（`allowTestKeys` を有効にした試験用の構成）が必要。これはコードの変更を伴うので、別に承認を得る） |
| クライアント | 試験用のビルド：`RegistryTrust` に、テスト用のリポジトリとテスト用の鍵を渡したもの（本番の配布物ではない） |
| メンテナー | テスト用のアカウントを 3 つ以上（2 要素認証あり）。作成者・承認者・パッケージの持ち主・外部の協力者（fork）の役を分ける |
| Release | テスト用のリポジトリの `index-*` の Release だけ。本番のレジストリの Release とは混ぜない |
| 記録 | 各テストの証拠（画面の写し・API の応答・ワークフローの実行の URL・ログ）を、テスト用のリポジトリの issue と、`docs/audits/` の結果の報告書に残す |

## B. テストのシナリオ

| # | シナリオ | 事前条件 | 操作 | 期待する結果 |
|---|---|---|---|---|
| 1 | 正常な PR の検証 | 雛形・ruleset・App・environment を適用済み。テスト用のパッケージの immutable な Release | 登録の PR を出し、2 人が承認し、`recheck` を付ける | `registry/validate`・`registry/approvals` が App（`integration_id`）から成功で付き、マージできる |
| 2 | 必須チェックの偽装 | 外部の協力者のアカウント（fork） | `registry/validate`・`registry/approvals` という名前のジョブを持つワークフローを、fork の PR に加える | ワークフローの実行は承認待ちになる。実行させても、必須チェックは満たされない（App が違う）。`validate` は `.github/` の変更を拒否する |
| 3 | 承認の不足 | 1 と同じ | 承認 1 人で `recheck` | `registry/approvals` が失敗。GitHub もマージを拒否する |
| 4 | 作成者による自己承認 | メンテナーが作成した PR | 作成者が承認しようとする | GitHub が拒否する。`check-approvals` も作成者を数えない |
| 5 | 利益相反のある承認 | パッケージの持ち主であるメンテナー | その持ち主が、自分のパッケージの更新を承認する | `registry/approvals` が「conflict of interest」で数えない |
| 6 | PR の更新の後の古い承認 | 承認済みの PR | 新しいコミットを push する。古い実行を再実行する | GitHub が古い承認を取り消す。古い実行は「outdated」で止まり、チェックを報告しない |
| 7 | GitHub App が入っていない | App をアンインストールする | PR を更新する | `report` が「not installed」で失敗し、必須チェックが付かず、マージできない |
| 8 | App のトークンの失効 | 1 と同じ | `report` の実行の後に、ログに残ったトークンが無いことを確かめる。トークンを（テストの範囲で）記録した場合は、その後に使えないことを確かめる | ログにはマスクされた値だけがある。実行の後、トークンは失効している（API が 401） |
| 9 | 署名の不正 | テスト用の鍵 | 1 バイト変えた署名の公開の PR | `validate`（`publish-check`）が拒否する |
| 10 | index の serial の巻き戻し | serial 2 を公開済み | serial 1 の index の公開の PR | 拒否される（serial は直前の次でなければならない） |
| 11 | immutable な Release | immutable releases が有効 | 公開した後に、アセットを差し替えようとする・Release を編集する | GitHub が拒否する。`verify` が `immutable: true` を確認する |
| 12 | Release のアセットの digest の不一致 | （再現方法は要検討。GitHub が digest を正しく計算する限り、通常は起きない） | 下書きの段階でアセットを差し替える | `release` が下書きのまま止まり、公開されず、latest にならない |
| 13 | 公開の途中の失敗 | 公開の PR をマージ済み | `release` の途中で失敗させる（例：一時的に権限を外す） | 失敗が記録される。下書きは公開されない。何も自動では削除されない（GOVERNANCE.md「Failed publication」） |
| 14 | 同じ serial での再実行 | 13 の後、または正常な公開の後 | ワークフローを再実行する | `check` が「already published」で止まるか、`release` が「already exists」で止まる。同じ serial は再び公開されない |
| 15 | `latest` の更新 | 公開済みの index | 正常な公開 | 新しい `index-<serial>` が latest になる。書き込み権限で古い Release を latest にした場合に、保存した状態のあるクライアントが巻き戻しとして拒否することも確かめる（GOVERNANCE.md「Index tags and releases」） |
| 16 | Linux CI | CreateScript のリポジトリ（push の許可が必要） | `ci.yml` を実行する | `verification-metadata.xml` の strict の検証を含めて、全テストが成功する。`WorkflowHarnessTest` が実行される（スキップされない）ことも確かめる |
| 17 | 実際の HTTPS でのインストール | 試験用のクライアントのビルド | `createscript install <name>@<version>` | 成功する。改ざん・期限切れ・ネットワーク障害（オンラインでは失敗し、`--offline` を案内する）が、ドキュメントどおり |
| 18 | yanked・removed のパッケージ | 公開済みの index | 取り下げ・削除の PR を公開する | 新しいインストールを拒否する。復元は、取り下げなら警告して行い、削除なら拒否する |
| 19 | オフラインでの復元 | ロックファイルのあるプロジェクトと、キャッシュ | ネットワークを切って `install --offline` | ロックファイルから復元できる。index が無ければ「確かめられない」と示す |
| 20 | L-5 の残存リスクの確認 | macOS・Linux のテスト用の機械 | 同じユーザーの権限での symlink の差し替えの競合（Phase 4・5 の方法） | 誤った成功が 0 であることを確かめる。プロジェクトの外への書き込みは、起こりうるもの（未解決）として記録する。GitHub のテストとは別の問題として扱う |

## C. テスト結果の記録の様式

各シナリオについて、次の表を 1 つずつ作る。

| 項目 | 記入 |
|---|---|
| シナリオ | （#と名前） |
| 実施日・実施者 | |
| 事前条件 | （設定の状態。ruleset・App・environment の値） |
| 操作 | （手順。実行した URL） |
| 期待する結果 | （B 章の記載） |
| 実際の結果 | |
| 判定 | PASS / FAIL / NOT VERIFIED |
| 証拠の保存場所 | （issue のリンク・ワークフローの実行・API の応答の保存先） |
| 残存リスク | |

### 結果の一覧（記入用）

| # | シナリオ | 判定 | 証拠 | 残存リスク |
|---|---|---|---|---|
| 1 | 正常な PR の検証 | NOT VERIFIED（未実施） | | |
| 2 | 必須チェックの偽装 | NOT VERIFIED（未実施） | | |
| 3 | 承認の不足 | NOT VERIFIED（未実施） | | |
| 4 | 作成者による自己承認 | NOT VERIFIED（未実施） | | |
| 5 | 利益相反のある承認 | NOT VERIFIED（未実施） | | |
| 6 | PR の更新の後の古い承認 | NOT VERIFIED（未実施） | | |
| 7 | GitHub App が入っていない | NOT VERIFIED（未実施） | | |
| 8 | App のトークンの失効 | NOT VERIFIED（未実施） | | |
| 9 | 署名の不正 | NOT VERIFIED（未実施） | | |
| 10 | serial の巻き戻し | NOT VERIFIED（未実施） | | |
| 11 | immutable な Release | NOT VERIFIED（未実施） | | |
| 12 | digest の不一致 | NOT VERIFIED（未実施） | | |
| 13 | 公開の途中の失敗 | NOT VERIFIED（未実施） | | |
| 14 | 同じ serial での再実行 | NOT VERIFIED（未実施） | | |
| 15 | `latest` の更新 | NOT VERIFIED（未実施） | | |
| 16 | Linux CI | NOT VERIFIED（未実施） | | |
| 17 | 実際の HTTPS でのインストール | NOT VERIFIED（未実施） | | |
| 18 | yanked・removed | NOT VERIFIED（未実施） | | |
| 19 | オフラインでの復元 | NOT VERIFIED（未実施） | | |
| 20 | L-5 の残存リスク | NOT VERIFIED（未実施。L-5 自体は未解決） | | |

## D. 注意

- テスト用の鍵・App・アカウントを、本番の設定に流用しない。テストの後に、App の秘密鍵と署名の鍵を破棄し、そのことを記録する。
- ローカルの模擬の試験（`WorkflowHarnessTest`：ワークフローの `run` を偽物の `gh`・`curl` で動かすもの）は、GitHub の実際の動作の代わりにはならない。ここでの実地の試験で確かめる。
- **L-5 は、GitHub の設定とは別の、利用者の機械のファイルシステムの問題です。** 実環境のテストが成功しても、L-5 が解決したことにはならない。
