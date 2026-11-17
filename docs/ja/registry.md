# CreateScript-Registry（S8.3）

> **原文（英語）**：[docs/registry.md](../registry.md)（コミット `4a2192b` の時点）。内容が食い違うときは英語版が正です。

> **開発中です（S8.3 の Phase 1 から 3 と、Phase 5 の是正の修正）。公式のレジストリは公開していません。** レジストリのリポジトリも、本番の署名鍵も、
> 公開した index もまだ無く、レジストリは **メンテナーを募集しています**（何かを登録・公開する前に、少なくとも 3 人が必要です。
> [レジストリの運営](#レジストリの運営phase-2) を参照）。このページでは、ファイルの形式と検査（Phase 1、`createscript-project`）、
> `createscript-registry-tool` と `registry-template/` の雛形によるレジストリの運営（Phase 2）、`createscript` のクライアント（Phase 3。
> [クライアント](#クライアントcreatescript-install-nameversionphase-3) を参照）を説明します。本番の鍵が組み込まれるまで、
> `createscript install <name>@<version>` はどこにも接続せずに拒否します。下の例でこれを使うものは、どう動く予定かを説明したもので、
> テスト用の鍵とメモリーの中の index でだけ試しました。ここに書いたことを GitHub に対して試したことはありません。

CreateScript-Registry は、GitHub のリポジトリになる予定です。そこでは、審査したプルリクエストを通して、各パッケージ名を、GitHub の取得元と、
審査した各版の SHA-256 に結び付けます。そこから作った署名付きの `index.json` が、どのアーカイブが審査済みかを `createscript` に伝えます。

レジストリは、S8.2 のどの形式も変えません：`createscript.json`、`createscript.lock`、`createscript-package.zip` はそのままで、`source` も
`github:owner/repo` のままです。レジストリの項目は、S8.2 のロックファイルがすでに持っている内容（名前、版、取得元、SHA-256、依存）を、
審査して記録したものにすぎません。

段階：S8.3 はレジストリ（名前、審査済みのハッシュ、署名付きの index）です。S8.4 は依存の管理（版の範囲、解決、`update`）です。

<!-- ja-only -->
| 用語 | 意味 |
|---|---|
| index | レジストリの内容を 1 つにまとめた `index.json`。クライアントが読む |
| 署名 | `index.json.sig`。index が正しい運営者のものであることを示す |
| yank | 版を取り下げること（すでに使っているプロジェクトは使い続けられる） |
| removed | パッケージそのものを取り除くこと |

## リポジトリの構成

```text
registry.json                    settings (index validity, signing key IDs)
reserved-names.json              names nobody can register
packages/<first letter>/<name>.json   one file per package, e.g. packages/v/vector-math.json
```

どのファイルも、`RegistryFiles.write…` が作る正規形とまったく同じでなければなりません：2 文字の空白の字下げ、LF の行末、最後に改行 1 つ、
項目は決まった順、名前と版は並べ替え。それ以外の形のファイルは拒否します（`CS_REG_NOT_CANONICAL`。最初に違う行と一緒に）。こうして、プルリクエストが
変わった書き方の中に内容を隠すことはできません。`createscript.json` の JSON の規則もすべて当てはまります（厳密な RFC 8259、どの深さでも重複したキーを拒否、
バイトオーダーマークの無い UTF-8、整数だけ、16 段まで）。

### `registry.json`

```json
{
  "registryVersion": 1,
  "indexValidityDays": 30,
  "signingKeyIds": [
    "createscript-registry-2026-1"
  ]
}
```

`indexValidityDays` は 1–90 です。`signingKeyIds` は 1–4 個の鍵の ID を挙げます（小文字の英字と数字で、間に 1 つずつハイフン。64 文字まで）。
公開鍵そのものはリポジトリから取りません：クライアントに組み込んであります（Phase 2/3）。

### `reserved-names.json`

```json
{
  "registryVersion": 1,
  "names": {
    "core": "the CreateScript core",
    "createscript": "the CreateScript project"
  }
}
```

名前は 4096 個までで、どれも正しいパッケージ名です。

### パッケージのファイル

```json
{
  "registryVersion": 1,
  "name": "vector-math",
  "source": "github:alice/vector-math",
  "repositoryId": 123456789,
  "owners": [
    {
      "login": "alice",
      "id": 1001
    }
  ],
  "description": "2D and 3D vector helpers",
  "license": "MIT",
  "versions": {
    "1.2.0": {
      "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
      "dependencies": {
        "numbers": "3.0.0"
      },
      "review": {
        "pullRequest": 42,
        "reviewers": [
          "dog7dog"
        ],
        "date": "2026-11-20"
      }
    }
  }
}
```

| 項目 | 規則 |
|---|---|
| `name` | S8.2 のパッケージ名の規則（小文字、ハイフンは 1 つずつ、64 文字まで、Windows の予約名は不可）。ファイルは `packages/<first letter>/<name>.json` になければならない（`CS_REG_ENTRY_PATH_MISMATCH`） |
| `source` | S8.2 と同じ `github:owner/repo` |
| `repositoryId`, `owners[].id` | GitHub の数値の ID。1 から 2^53−1。アカウントやリポジトリの名前を変えても変わらないので、消して同じ名前で作り直したリポジトリを見分けられる |
| `owners` | 1–16 個の GitHub のログイン名とその ID。重複なし（大文字・小文字を区別しない） |
| `description` | 1–200 文字。[テキスト](#テキスト) を参照 |
| `license` | S8.2 と同じ、SPDX の形の式 |
| `removed` | 任意。`{"reason", "date"}`：パッケージが取り除かれた（例えばセキュリティの理由で） |
| `versions` | 1–1000 個の版。S8.2 と同じ厳密な SemVer で、**ビルドメタデータは不可**（`1.0.0+a` と `1.0.0+b` は優先順位が同じで、取り違えやすい） |
| `sha256` | 審査した `createscript-package.zip` の SHA-256（小文字の 16 進数） |
| `dependencies` | 256 個まで。名前 → 厳密な版。範囲は S8.4 |
| `review` | プルリクエストの番号、1–16 人の審査者（GitHub のログイン名）、日付 `YYYY-MM-DD` |
| `yanked` | 任意。`{"reason", "date"}`：その版が取り下げられた |

### テキスト

説明と理由には、どの Unicode の文字も使えますが、テキストを別のものに見せかけられる文字は使えません：制御文字、書式の文字（双方向テキストの上書き U+202E と
U+2066–U+2069、U+200B など）、行と段落の区切り、対になっていないサロゲート、私用の文字、非文字。テキストは Unicode の正規化形式 NFC でなければならず
（同じに見えるテキストは、いつも同じバイト列になる）、空白で始まったり終わったりしてはいけません。文字の分類は Java 21（Unicode 15）のものです。

## パッケージをまたぐ規則

リポジトリから index を作るときと、index を読むたびに確かめます。

- **似た名前**：骨格が同じ 2 つの名前は、両方は存在できません（`CS_REG_SIMILAR_NAME`）。骨格は、ハイフンを除き、`0` を `o` に、`1` を `l` に置き換えたものです。
  そのため `vector-math`、`vectormath`、`vect0r-math` は 1 つの名前です。骨格が予約名の骨格と同じ名前は拒否します（`CS_REG_RESERVED_PACKAGE_NAME`）。
  `RegistryRules.similarNames` は、1 か所違うだけの名前（`vector-maths`、`vetcor-math`）も見つけます。これらはエラーではなく、審査者に見せるためのものです。
- **1 つの取得元、1 つのリポジトリ、1 つのパッケージ**：取得元は大文字・小文字を区別せずに比べ（GitHub の名前は大文字・小文字を区別しない）、
  `repositoryId` は一意でなければなりません。
- **審査済みのパッケージは、審査済みのパッケージだけに依存する**：どの依存も、レジストリにある名前と版でなければならず（`CS_REG_UNKNOWN_DEPENDENCY`）、
  パッケージは自分自身に依存できません。
- **取り除いたパッケージ**：取り除いたパッケージに依存できるのは、取り下げた版か、取り除いたパッケージの版だけです（`CS_REG_DEPENDENCY_ON_REMOVED`）。
  そのため、パッケージを取り除くときは、それに依存するものを同じプルリクエストで取り下げます。取り下げた版にはまだ依存できるので、それをロックファイルに
  持つプロジェクトは動き続けます。
- **単独でインストールできる**：（名前、版）の組の間に依存の循環が無く、1 つの版の依存を最後までたどっても、1 つの名前の 2 つの版を含まず、
  パッケージは 4096 個までです。そのため、審査したどの版とその依存も、正しい S8.2 のロックファイルになります（`RegistryIndex.lockedClosure`）。

## index：`index.json`

index は `RegistryIndexes.build` がリポジトリから作り（Phase 2 では、マージのたびにワークフローで実行する予定）、署名と一緒にリリースのアセットとして公開します。
クライアントが必要とするものを持ちます。

```json
{"expires":"2026-12-20T10:00:00Z","generated":"2026-11-20T10:00:00Z","indexVersion":1,"packages":{"vector-math":{"description":"2D and 3D vector helpers","license":"MIT","repositoryId":123456789,"source":"github:alice/vector-math","versions":{"1.2.0":{"dependencies":{"numbers":"3.0.0"},"pullRequest":42,"sha256":"0123…"}}}},"serial":57}
```

（もちろん `numbers` も `packages` にあるはずです。例は短くしています。）

- `serial`（1 から 2^53−1）は、index を公開するたびに 1 つずつ増えます。
- `generated` と `expires` は秒までの UTC の時刻で、`YYYY-MM-DDTHH:MM:SSZ` です（小数、時差、うるう秒は不可）。`expires` は `generated` より後で、
  最大でも 90 日後です。
- 所有者と審査者はリポジトリに残り、index はプルリクエストの番号を持ちます。
- 上限：16 MiB、パッケージ 20,000 個、パッケージごとに版 1000 個、全部で版 200,000 個、版ごとに依存 256 個。上限は、値を読む前に確かめます。

### index の正規形

`index.json` のバイト列は、RFC 8785（JSON Canonicalization Scheme）の正規の JSON で、index が使う値に限ったものです。

- UTF-8、バイトオーダーマークなし、文字列の外の空白なし、最後の改行なし。
- オブジェクトのキーは UTF-16 のコード単位の順に並べる（index の ASCII のキーでは、ただのバイトの順）。
- 文字列でエスケープするのは `"`、`\`、U+0000–U+001F だけ（`\b \t \n \f \r` か、小文字の 16 進数の `\u00xx` として）。`/`、DEL、U+2028 を含むほかの文字は、
  すべて UTF-8 で書く。
- 整数は 10 進数で、±(2^53−1) の範囲だけ。ほかの数はない。
- 対になっていないサロゲートと正しくない UTF-8 は書けず、読むときに拒否する。

読む側は、内容が正しくても、そのバイト列が自分の内容の正規形と違う index を拒否します（`CS_REG_NOT_CANONICAL`）。そのため、同じ内容はいつも同じバイト列と
同じ SHA-256 になり、2 つの index を SHA-256 で比べられます。

## 署名：`index.json.sig`

どの index も Ed25519（RFC 8032）で署名しなければなりません。検証には Java 21 自身の実装（SunEC。モジュール `jdk.crypto.ec`。パッケージが含む
ランタイムにすでに入っている）だけを使い、暗号のライブラリは加えません。

```json
{"signatureVersion":1,"signatures":[{"keyId":"createscript-registry-2026-1","signature":"<88 characters of Base64>"}]}
```

- このファイルも正規の JSON です（4 KiB まで）。署名は 64 バイトで、パディング付きの標準の Base64 で書きます。同じバイト列のほかの書き方は拒否します。
- **署名する内容**：ASCII のテキスト `CreateScript-Registry index signature v1`、ゼロのバイト、鍵の ID、ゼロのバイト、そして `index.json` のバイト列そのもの。
  前置きで、この署名を、その鍵が署名するかもしれないほかのものと区別し、鍵の ID で、署名を名乗っている鍵に結び付けます。
- **信頼する鍵** は、鍵の ID 付きの 32 バイトの Ed25519 の公開鍵で、クライアントに組み込みます。本番の鍵はまだありません：秘密鍵の生成・保管・利用は
  Phase 2 で設計します。テストは、実行のたびに使い捨ての鍵をメモリーの中で作り（鍵の ID は `test-only-` で始まる）、決して保存しません。
- **鍵の交換**：index は最大 4 つの署名を持てます。クライアントがそれを受け入れるのは、少なくとも 1 つの署名が信頼する鍵によるもので、信頼する鍵による
  どの署名も正しいときです。知らない鍵による署名は無視します。交換するには、レジストリが古い鍵と新しい鍵の両方で署名し、両方を信頼するクライアントを
  リリースし、後で古い鍵を外します。

> [!TIP]
> 鍵の交換の流れ：① 新旧両方の鍵で署名する → ② 両方を信頼するクライアントをリリースする → ③ 古い鍵を外す。

次の場合、index を拒否します。

| 状況 | コード |
|---|---|
| `index.json.sig` が無い、または空 | `CS_REG_SIGNATURE_MISSING` |
| 署名のファイルを読めない | `CS_REG_SIGNATURE_MALFORMED`（理由付き） |
| 信頼する鍵による署名が 1 つも無い | `CS_REG_SIGNATURE_UNKNOWN_KEY` |
| 信頼する鍵による署名が合わない（index が変えられた、署名が変えられたか切り詰められた、別の鍵の ID のために作った署名、前置きの無い index だけへの署名） | `CS_REG_SIGNATURE_INVALID` |

## index の受け入れ

`IndexVerifier.verify` は、`index.json` と `index.json.sig` のバイト列、前回受け入れた index（あれば）、今の時刻を受け取り、次の順に確かめます。

1. 大きさ（16 MiB まで）。
2. 署名。index の JSON を読む **前に** 確かめるので、署名の無い入力が構文解析器に届くことはありません。
3. index そのもの：正規形、値、上限、パッケージをまたぐ規則。
4. 時刻：`expires` が過ぎた index は拒否します（`CS_REG_INDEX_EXPIRED`）。`generated` が 1 日より先の未来の index も拒否します
   （`CS_REG_INDEX_NOT_YET_VALID`）。期限切れの index を受け入れられるのは、オフラインでキャッシュした情報を見せるためだけです（`allowExpired`）。
5. 前に受け入れた index との比較（`IndexHistory.compare`）：

| 拒否するもの | コード |
|---|---|
| より小さい `serial`（古い署名済みの index をもう一度送る） | `CS_REG_INDEX_ROLLBACK` |
| 同じ `serial` で違うバイト列 | `CS_REG_INDEX_SERIAL_REUSED` |
| より早い `generated` | `CS_REG_INDEX_TIME_ROLLBACK` |
| もう無くなったパッケージや版 | `CS_REG_PACKAGE_DISAPPEARED`, `CS_REG_VERSION_DISAPPEARED` |
| 別の SHA-256 を持つ審査済みの版 | `CS_REG_SHA256_CHANGED` |
| 別の依存を持つ審査済みの版 | `CS_REG_DEPENDENCIES_CHANGED` |
| 別の `repositoryId` を持つパッケージ | `CS_REG_REPOSITORY_CHANGED` |

認めるもの：新しいパッケージと版、取り下げと取り除き、ほかの説明、同じ `repositoryId` での別の `source`（名前を変えたリポジトリ）。パッケージと版は
決して消えないので、ある index で版を消して、次の index で別の内容でもう一度加えることはできません。プルリクエストの前と後のリポジトリのファイルの
同じ比較は `IndexHistory.compareEntries` です（Phase 2 のワークフロー用）。

1 つの index だけでは、審査した SHA-256 の値が一度も変わっていないことは証明できません。証明できるのは、前の index との比較だけです。記録を保つのは、
レジストリのリポジトリの履歴と、その必須のチェック（Phase 2）です。

### 審査状況

`RegistryIndex.status(name, version, source, sha256)` は、パッケージ（例えばロックファイルの 1 つの項目）を分類します：`REVIEWED`、`YANKED`、
`REMOVED`、`VERSION_NOT_REVIEWED`、`NOT_IN_REGISTRY`、`NAME_USED_BY_ANOTHER_SOURCE`、`SHA256_MISMATCH`、`NAME_MISMATCH`。クライアント（Phase 3）は、
`SHA256_MISMATCH`・`NAME_MISMATCH`・`REMOVED` を拒否し、`YANKED` の版を新しくインストールすることを拒否し、審査されていないパッケージには警告を出しつつ
`github:owner/repo` で直接指定されたときは認め、どのパッケージの状況も表示します（[クライアント](#クライアントcreatescript-install-nameversionphase-3) を参照）。

## セキュリティ上の限界

これらは設計上の分かっている限界で、後で直すべき不具合ではありません。

- **最初の利用と、失った状態。** 巻き戻しの防止は、前に受け入れた index と比べることで行います。最初に取得するとき、またはローカルの状態が消されたとき
  （新しい機械、消したキャッシュ、キャッシュに書き込める利用者やプログラム）は、クライアントは古い署名済みの index と最新のものを区別できません。
  そのような index も署名され、確かめられており、攻撃者が送れるのはまだ期限切れでないものだけなので、隙間は最大でも `indexValidityDays`（最大 90 日、
  既定は 30 日）です。ローカルの状態を守るのは、利用者のファイルの権限だけです。
- **凍結。** index のダウンロードを妨げられる攻撃者は、クライアントが新しい取り除きや取り下げを知ることを妨げられます。そのとき、オンラインでは
  クライアントは失敗します（保存した index に戻らない。S8.3 Phase 5）。`--offline` では保存した index を使い、それは後から公開された取り除きを知りません。
  保存した状態を持たないクライアントに、古いがまだ期限切れでない署名済みの index を渡せる攻撃者は、その index が期限切れになるまで（最大 `indexValidityDays`）
  取り除きを隠せます。
- **署名の鍵。** 秘密鍵を持つ人は、どんな index でも公開できます。それを守るのは Phase 2 です。盗まれた鍵で、クライアントがすでに受け入れたもの
  （以前の版の SHA-256 の値と依存）を変えることはできませんが、版やパッケージを加えることはできます。そのため、漏れたら鍵を替えなければなりません
  （新しい鍵を持つクライアントのリリース）。
- **時計。** 期限はクライアントの時計を使います。大きく戻した時計では期限切れの index が通り、進めた時計では今の index が期限切れに見えます。
- **審査は安全の証明ではありません。** 審査は、メンテナーがその SHA-256 のパッケージを読んだということです。CreateScript のプログラムはファイル・
  ネットワーク・OS に触れられないので、パッケージにできることは限られますが、審査済みのパッケージでも誤りはあり得ます。
- **似た名前** の検査が捕まえるのは、ハイフン、`0`/`o`、`1`/`l` です。ほかの紛らわしい名前は審査に頼ります。
- **まだしていないこと**：レジストリのリポジトリそのもの、本番の鍵、公開した index。index のダウンロードと保存（Phase 3）は、テスト用の鍵、メモリーの中の
  index、ローカルの HTTP サーバーでだけ試しました。Linux、Windows、実際の GitHub からのダウンロードは、どれについても試していません。

## レジストリの運営（Phase 2）

> Phase 4 の監査（`docs/audits/S8.3-Phase4-Security-Audit.md`、`docs/audits/S8.3-Phase5-Remediation.md`）の後、S8.3 Phase 5 で改めました：
> 必須のチェックはレジストリ自身の GitHub App（`registry/validate`、`registry/approvals`）から来ます。CODEOWNERS はすべてのメンテナーを挙げます。
> どのジョブも古いコミットのための実行を拒否します。承認は公開の前にもう一度確かめます。道具は確かめた Gradle wrapper と厳密な依存の検証でビルドし、
> 書き込みのトークンを持つジョブは何もビルドしません。Phase 7 では、App のトークンを使った後に失効させ、各 index をまず下書きとして公開し、
> 最新にする前にそのアセットを確かめ、承認の基準を生の blob から作り直します（`git archive` を使わない）。ワークフローのスクリプトは、
> `WorkflowHarnessTest` が偽の `gh` と `curl` に対して実行します。どれも GitHub では試していません（`docs/operations/registry-github-integration-test.md` を参照）。

`registry-template/` は、将来のリポジトリ `CreateScript-Registry` です：そのファイル（`registry.json`、`reserved-names.json`、`maintainers.json`、
`keys/trusted-keys.json`、`packages/`、`signed/`）、文書（`README.md`、`POLICY.md`、`CONTRIBUTING.md`、`GOVERNANCE.md`、`SIGNING.md`、`SECURITY.md`）、
`CODEOWNERS`、2 つのワークフロー、`github-settings/` の ruleset の案。レジストリのリポジトリを作るまで、CreateScript のリポジトリに置いておきます。
GitHub がワークフローを実行するのは、リポジトリのルートの `.github/workflows/` からだけなので、雛形のワークフローがここで動くことはありません。さらに、
どちらも `TOOL_COMMIT` がすべてゼロの間は動くのを拒みます。試せるのは明示的に、テスト（`./gradlew :createscript-registry-tool:test`）か、
道具を手で実行することでだけです。

`createscript-registry-tool`（同じ名前の Gradle のモジュール。`createscript` のパッケージには含まれない）が、すべての検査を実装します。

| コマンド | 使う人・もの | すること |
|---|---|---|
| `check-repo` | 誰でも | リポジトリを厳密に読み、パッケージをまたぐ規則を当てはめる |
| `validate-pr` | `validate.yml` | プルリクエストの検査（下） |
| `check-approvals` | `validate.yml`, `publish.yml` | GitHub の設定では確かめられない承認（`registry-template/GOVERNANCE.md`）。公開の前にもう一度 |
| `check-settings` | リーダー | ruleset の案を確かめる：回避なし、2 つの承認、コードオーナー、必須のチェックはレジストリの GitHub App からだけ（S8.3 Phase 5） |
| `build-index` | 署名者 | リポジトリから次の index を作り（serial = 公開済み + 1）、変更を挙げる |
| `sign` | 署名者 | ローカルで署名する。この段階では **ID が `test-only-` で始まる鍵だけ** |
| `verify-index` | 誰でも | 署名した index を、クライアントと同じように検証する |
| `publish-check` | 署名者、`validate.yml`, `publish.yml` | 公開の前の検査（下） |
| `check-release` | `publish.yml` | 公開したリリースが変更不可で、digest が合う |

`maintainers.json`（Phase 2 で新しく加わった）は、リーダー、`minimumMaintainers`（3）、`requiredApprovals`（2）、ログイン名と数値の ID によるメンテナーを、
正規形で持ちます。メンテナーが `minimumMaintainers` より少ない間は、レジストリは閉じています：パッケージのプルリクエストと公開は拒否します。
`keys/trusted-keys.json` は最大 4 つの公開鍵（鍵の ID と Base64 の 32 バイト）を持ち、どれも `signingKeyIds` に挙げてあります。これを使うのは
`publish-check` だけです。クライアントは、自分に組み込んだ鍵を持っているからです。

### プルリクエストの検査（`validate-pr`）

プルリクエストは、チェックアウトした木からデータとして読みます（シンボリックリンクと通常でないファイルは拒否し、中のものは何も実行しません）。
1 つのプルリクエストが変えるのは、1 種類のファイルだけです：`packages/`、`signed/`、またはほかのファイル（保守）。どの種類でも、パッケージをまたぐ規則を
確かめます。パッケージの変更では：

- レジストリが開いていること。審査済みの SHA-256 の値、依存、審査の記録、リポジトリの ID が変わっていないこと。何も消していないこと。
- 新しいパッケージ：作者（数値の ID で）が所有者であること。利用者のリポジトリなら、その利用者が所有者であること。組織のリポジトリなら、審査者に確認を求める。
  既存の名前と 1 か所違うだけの名前を示す。
- 更新：版を加えたり、所有者や取得元を変えたりできるのは所有者だけ。取り下げ、取り除き、説明の変更は、所有者かメンテナーができる。
- 新しい各版：`review.pullRequest` がこのプルリクエストであること。審査者は、作者でも所有者でもないメンテナーで、少なくとも `requiredApprovals` 人いること。
  日付が未来でないこと。取り下げていないこと。GitHub が、リポジトリの数値の ID と今の名前を報告すること。リリース `v<version>` が変更不可であること。
  `createscript-package.zip` の GitHub の digest が登録した SHA-256 であること。ダウンロードしたファイルがその SHA-256 を持つこと。それが、登録した名前・版・
  依存を持ち、ソースがレジストリのソースである、正規形のパッケージのアーカイブ（`createscript pack`）であること。

GitHub は `GitHubReleases`（固定のホスト、上限、数値の ID のための `repositoryInfo`）を通して、トークンなしで読みます。テストでは、代わりにメモリーの中の
GitHub を使います。

### 公開の検査（`publish-check`）

レジストリが開いていること。信頼する鍵があり、そのどれもテスト用の鍵でないこと（テストの中を除く）。署名が正しいこと。公開済みの index と比べて、
巻き戻し・serial の使い回し・SHA-256 や依存の変更・取り除いたものが無いこと。serial がちょうど次のものであること。署名した index が、そのコミットの
リポジトリから作った index と 1 バイトずつ同じであること。署名と公開の手順、鍵の交換、鍵を失ったときや漏れたときにすることは、
`registry-template/SIGNING.md` にあります。

## クライアント：`createscript install <name>@<version>`（Phase 3）

> まだ使えません：`createscript` には本番の鍵が組み込まれていないので、このコマンドは終了コード 69 で止まり、公式のレジストリはまだ公開していない、と伝えます。
> どこにも接続せず、キャッシュも作りません。下の挙動は、テスト用の鍵（`test-only-…`。テストがメモリーの中で作る）と、メモリーの中かローカルの HTTP サーバーの
> index でだけ試しました。

```bash
createscript install vector-math@1.2.0 [directory] [--offline]
```

版は厳密でなければなりません（`1.2.0`。`v` は付けず、範囲も不可。範囲は S8.4）。`@` を含み、`/` も `\` も含まない引数はパッケージ名です。名前に `@` を
含むディレクトリは、パスで書かなければなりません（`./odd@dir`）。

1. **index。** `RegistryClient` は、レジストリのリポジトリの最新のリリース（`https://github.com/<registry>/releases/latest/download/…`）から
   `index.json` と `index.json.sig` を、`GitHubReleases` を通してダウンロードします：HTTPS だけ、パッケージのダウンロードと同じ固定のホスト、転送の検査、
   時間の上限、やり直し。index は 16 MiB まで、署名は 4 KiB までです。リポジトリは組み込みで（`RegistryTrust.OFFICIAL_REPOSITORY`）、オプション・環境変数・
   ファイルで変えることはできません。署名のファイルが無ければ、index は署名の無いものとして扱います。署名が合わなければ、両方のファイルをもう 1 回だけ
   ダウンロードします（2 つのダウンロードの間に、新しい index が公開されたのかもしれないため）。
2. `IndexVerifier` と組み込みの鍵による **検証**：署名、内容、期限、前に受け入れた index との比較。拒否した index は決して使わず、そこから何もインストールしません。
3. インストールを始める前の **計画**：名前が index にあり、取り除かれていないこと。版が審査済みで、取り下げられていないこと。その閉包
   （`RegistryIndex.lockedClosure`）のどのパッケージも審査済みで、取り下げられておらず、取り除かれていないこと。その後、閉包のどのパッケージについても、
   登録した `source` と審査済みの SHA-256 を使います。
4. 残りは **S8.2 のインストーラー** が、審査済みの SHA-256 の値を固定して行います：アーカイブ（SHA-256 があればキャッシュから、なければダウンロード）は、
   ちょうどその SHA-256 を持たなければならず、解決したパッケージは、index の閉包とちょうど同じでなければなりません（名前、版、取得元、SHA-256 の値、依存）。
   別の SHA-256 を持つロックファイルの項目があれば、何かをダウンロードする前に止まります。`createscript.json` と `createscript.lock` は S8.2 の形式で書きます
   （依存は `github:` の取得元で記録します）。トランザクション、ジャーナル、復旧も同じです。
5. **インストールの後**、マニフェスト、ロックファイル、パッケージのディレクトリをもう一度読み、index と比べます。

index を使えなければ、何も変えずにコマンドは失敗します：69 はレジストリが設定されていない、届かない（オンラインでは、前に index を保存していても。
メッセージは `--offline` を勧める）、`--offline` で保存した index が期限切れか無い、オフラインでアーカイブがキャッシュに無い。65 は index を拒否した、
または名前や版が認められない。73 はキャッシュのディレクトリが安全でない。75 は別の `createscript` がキャッシュのロックを 30 秒より長く持っている。

<!-- ja-only -->
| 終了コード | 主な意味 |
|---|---|
| 69 | レジストリを使えない（未設定・届かない・保存した index が使えない） |
| 65 | index を拒否した、または名前や版が認められない |
| 73 | キャッシュのディレクトリが安全でない |
| 75 | キャッシュのロックを 30 秒より長く取れない |

### ほかのインストールの審査状況

`createscript install github:owner/repo@v1.2.0` と `createscript install`（ロックファイルからの復元）は、S8.2 と同じように動きます。レジストリが設定されて
いれば、これらも index を調べ（`--offline` ならキャッシュから）、パッケージごとに `registry: …` を表示します。

| 状況 | 直接のインストール（新規） | `createscript.lock` からの復元 |
|---|---|---|
| 審査済み | インストールする | 復元する |
| 取り下げ（yanked） | 拒否する | 警告付きで復元する |
| 取り除き（removed） | 拒否する | 拒否する |
| SHA-256 か名前がレジストリと違う | 拒否する | 拒否する |
| 審査されていない（レジストリに無い、または別の版） | 警告付きでインストールする | 復元する（状況を表示する） |

使える index が無いとき、状況は「審査されていない」ではなく、**確かめられなかった** と報告します：直接のインストールでは警告、復元ではレジストリが
設定されているときだけです。S8.2 の挙動はレジストリに依存しません：ロックファイルは、index が無くても、オフラインでキャッシュから復元します。
`createscript run` は、ネットワークもレジストリも決して使いません。

### 鍵

`RegistryTrust.official()` が、ただ 1 つの本番の設定です。その鍵の一覧は、本番の鍵を作って承認するまで空です（`registry-template/SIGNING.md` を参照）。
空の間、クライアントは接続せず、キャッシュにも触れません。ID が `test-only-` で始まる鍵は拒否し、公式のリポジトリのための設定は、どれもそれを拒否します。
テストは、自分の `RegistryTrust`（別のリポジトリ名、メモリーの中で作ったテスト用の鍵）を渡します。交換：クライアントは鍵の一覧を信頼し、index は複数の署名を
持てます（[署名](#署名indexjsonsig) を参照）。

### 保存した index

受け入れた index は `<cache>/registry/<owner>/<repository>/`（`docs/packages.md` のキャッシュのディレクトリ）に保存します。

- `index-<SHA-256>.json` と `signature-<SHA-256>.sig`：ダウンロードしたとおりのバイト列で、SHA-256 で名前を付けます。
- `trusted.json`：履歴。`{"format":1,"generated":…,"indexSha256":…,"repository":…,"serial":…,"signatureSha256":…}`。
- `lock`：履歴を読んで更新する間、保持します。

保存するのは、受け入れた index だけです。保存では、まず 2 つのファイルを書き（一時ファイルに書いてディスクに書き出し、原子的に名前を変える）、次に
`trusted.json` を原子的な名前の変更で置き換えます。どの時点でプロセスが止まっても、古い状態か新しい状態のどちらかが残ります（各手順で止めてテストし、
macOS では各手順で別の JVM を SIGKILL で止めてテストしました）。残った一時ファイルは、次の更新が消します。読むたびに、ファイルの SHA-256 を確かめ、
今の鍵で署名をもう一度検証します。

- ファイルが壊れた保存した index は使いません。オフラインではエラーで、オンラインでは、新しい index を `trusted.json` の serial・SHA-256・時刻と比べます。
- 壊れたか変えられた `trusted.json` があると、コマンドは止まり（73）、やり直す方法を伝えます（ディレクトリを消す。そうすると巻き戻しの防止も消える）。
  黙ってリセットすることはありません。
- 保存したものより古い index（小さい serial）は拒否します。保存したものがまだ有効なら、代わりにそれを使います（2 つの `createscript` のプロセスが同時に
  キャッシュを更新したときも、こうなります）。同じ serial で違うバイト列のものは拒否します。
- `--offline` では、期限切れでない保存した index だけを使い、次の注意を表示します：
  `offline: using the registry index saved before (…); packages removed and versions yanked after … are not known`。
- オンラインで index をダウンロードできないとき、保存した index は **使いません**（S8.3 Phase 5。Phase 4 の監査の指摘 P4-M2）：`install <name>@<version>` は
  69 で失敗して `--offline` を勧め、直接のインストールと復元は、審査状況を確かめられなかったと報告します。`createscript.lock` からの復元は動き続けます
  （もともと index に依存していない）。
- `registry`、所有者、リポジトリのディレクトリは権限 0700 で作り、シンボリックリンクであってはならず、その利用者のもので、グループやほかの人が書き込めては
  いけません。そうでなければキャッシュを使いません（73）。ファイルはリンクをたどらずに読みます。キャッシュのルートそのものは、アーカイブのキャッシュと同じく、
  リンクでもかまいません。

限界：履歴を守るのは、利用者のファイルの権限だけです（[セキュリティ上の限界](#セキュリティ上の限界) を参照）。同じ利用者として動くプログラムは、
キャッシュを消したり置き換えたりできます。その後は、次の index を最初の利用と同じように受け入れます。
