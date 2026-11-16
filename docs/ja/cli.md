# `createscript` コマンド

> **原文（英語）**：[docs/cli.md](../cli.md)（コミット `4a2192b` の時点）。内容が食い違うときは英語版が正です。

```
createscript                         interactive mode (REPL)
createscript run <file.cscr> [options]
createscript init [directory] [--name <name>]
createscript manifest check [directory]
createscript lock check [directory]
createscript pack [directory] [--output <file>]
createscript --help      (-h)
createscript --version
```

日本語の詳しい説明書は [createscript-cli/README.md](../../createscript-cli/README.md) です。

> [!NOTE]
> **訳注**：上の一覧（原文のまま）には `createscript install` と `createscript run [directory]`（プロジェクトの実行）が載っていませんが、
> どちらも使えます。下の「プロジェクトを実行する」と「プロジェクトのファイル」の節で説明しています。

## ファイルを実行する

`createscript run` は、`.cscr` のファイルを 1 つ読み、コンパイルし、エラーが無ければ実行します。

- 受け付けるのは、名前が `.cscr`（小文字）で終わるファイルだけです。ファイルは正しい UTF-8 で、大きさは
  3,000,000 バイトまでです（コマンドは 1 ファイル 1,000,000 文字まで受け付けます。これは開発中の機能で、v0.1.0-alpha では
  4096 文字・12288 バイトまでです）。
- 標準出力には、`println` の行がすべて順に出ます。標準エラー出力には診断が `file:line:column: error|warning CODE (arguments)`
  の形で出ます。行と列は UTF-16 の単位で、1 から数えます。
- `waitTicks(n)` は論理的な tick を数えます。CLI は実際には待ちません（スリープしません）。
- コマンドは、独立版の上限でコンパイルします。1 ファイルあたり 1,000,000 文字・250,000 トークン・構文木のノード 250,000 個、
  モジュールは 1024 個まで、すべてのファイルを合わせて 8,000,000 文字・1,000,000 トークン、命令の数には固定の上限がありません
  （開発中。v0.1.0-alpha は 4096 文字・4096 命令です）。実行時の上限は変わりません。[上限と安全性](language.md#上限と安全性) を参照してください。
- 大きなプログラムには、メモリーが必要です（上限に近いプログラムで約 600 MiB）。Java のヒープは `JAVA_OPTS` で設定します。
  例：`JAVA_OPTS=-Xmx2g`。

| オプション | 意味 | 既定値 | 使える値 |
|---|---|---|---|
| `--seed <integer>` | 乱数の最初の状態（プログラムの中の `randomSeed(...)` がこれを置き換える） | `0` | 符号付き 64 ビットの任意の整数 |
| `--instructions-per-tick <integer>` | 論理的な tick 1 回あたりに実行する命令の数 | `128` | 1–4096 |
| `--max-ticks <integer>` | この数の tick が過ぎてもプログラムが終わらなければ、終了コード 3 で止める | `100000` | 1–1000000 |
| `--project-root <directory>` | `import name` が `name.cscr` を探す場所 | 実行するファイルのディレクトリ | そのファイルを含む、存在するディレクトリ |

| 終了コード | 意味 |
|---|---|
| 0 | プログラムが終わった |
| 1 | 実行時エラー（`CS_RUN_*`）。それまでの出力は残る |
| 2 | コンパイルエラー（何も実行しない） |
| 3 | `--max-ticks` に達した |
| 64 | 使い方の誤り（知らないコマンドやオプション、値の誤り、`.cscr` でないファイル） |
| 65 | ファイルを使えない（UTF-8 でない、大きすぎる） |
| 66 | ファイルが存在しない、通常のファイルでない、読めない。プロジェクトのルートが存在しない、ディレクトリでない |
| 130 | 実行中に Ctrl+C で中断した |

### プロジェクトを実行する

`createscript run [directory]`（開発中、S8.2 Phase 4）は、プロジェクトの `createscript.json` の `entry` を実行します。
ディレクトリを省くと、今いるディレクトリを使います。インストールしたパッケージを import するときは、こちらを使います。

```text
$ createscript install github:owner/vector-math@v1.2.0
$ createscript run
25
```

- コンパイルの前に、`createscript manifest check` と `createscript lock check` と同じようにプロジェクトを確かめます。さらに、
  `createscript.lock` にあるすべてのパッケージが、正確にインストールされていなければなりません。具体的には：
  - `.createscript/packages/<name>/<version>/` のファイルが、その印（marker）と一致すること（足りないファイル・余分なファイル・変わったファイルが無く、シンボリックリンクも無い）
  - 印が、ロックファイルのアーカイブ（`sha256`）を指していること
  - ファイルから作り直した正規形のアーカイブが、その SHA-256 を持つこと（そのため、ファイルと印を一緒に書き換えても見つかります。
    [正規形](packages.md#正規形) を参照）
  - パッケージの `createscript.json` がロックファイルと合っていること

  どれかが成り立たなければ、何もコンパイル・実行せず（終了コード 65）、`createscript install` を勧めます。中断したインストールがあるときも止まります。
- プロジェクトを確かめている間、`run` は `.createscript/install.lock` に共有ロックをかけます（読み取り専用で開き、ファイルを作ることはありません）。
  `createscript install` はインストールの間ずっとこのファイルをロックしているので、次のようになります。
  - インストールの最中なら、`run` はそれを待ちます（1 秒たつとそう表示します）。最大 10 秒待って、終わらなければ終了コード 75 で止まり、やり直すよう伝えます。
  - インストールが動いておらず、`.createscript/transaction.json` があるときだけ、中断したインストールとして報告します（終了コード 65）。
  - ロックはプログラムをコンパイル・実行する前に外すので、プログラムの実行中に `install` を始められます。
  - `install.lock` が無ければ（一度もインストールしていないプロジェクトや、それを含まない写し）、`run` はこれまでどおりに確かめます。
  - ロックを試せないとき（ファイルを読めない、ファイルシステムがロックに対応しない）は、`run` は「インストールは動いていない」とは決めつけません。
    ジャーナルがあれば、動いているのか中断したのか分からないと伝えて、終了コード 65 で止まります。ジャーナルが無ければ、これまでどおりに確かめます
    （コンパイルするのは、確かめたバイト列だけです）。
  - `install` は、確かめている最中の run と、別のインストールを、最大 30 秒待ちます（その後は終了コード 75）。インストールが待ち始めたら、
    新しい run はその後ろで待ちます。そのため、何度も始まる run がインストールをいつまでも締め出すことはありません。
- ネットワークもキャッシュも使わず、ファイルも書きません。コンパイルするのは、確かめたソースそのものです。
- ローカルのモジュールは、エントリーのファイルのディレクトリで探します。どのモジュールを import できるかは
  [パッケージのモジュール](language.md#パッケージのモジュール) で説明しています。
- `--seed`・`--instructions-per-tick`・`--max-ticks` は、ファイルの場合と同じように使えます。`--project-root` は使えません。
- `createscript run <file>.cscr` は変わりません。`createscript.json` を読まず、パッケージも使いません。
- `entry` の無いライブラリは実行できません（終了コード 65）。

> [!TIP]
> 終了コード **75** は「今は混んでいるので、少し待ってからやり直す」という意味です。**65** は「プロジェクトの状態を直す必要がある」という意味です。

### `import` のあるファイル

ファイルは、同じプロジェクトのほかの `.cscr` ファイルを import できます（開発中。v0.1.0-alpha には入っていません。
[言語の説明のモジュール](language.md#モジュールローカルの-import) を参照）。

```text
$ ls my-project
geometry.cscr  main.cscr
$ createscript run my-project/main.cscr
25
$ createscript run my-project/apps/tool.cscr --project-root my-project
```

- `import name` が読むのは、プロジェクトのルートの直下にある `name.cscr` だけです。サブディレクトリも、探す場所の一覧も、ネットワークも使いません。
  プロジェクトのルートは、実行するファイルのディレクトリか、`--project-root` で指定したディレクトリです。
  `--project-root` を使うとき、実行するファイルはそのディレクトリの中になければなりません（そうでなければ終了コード 64）。
- ファイル名は、大文字・小文字も含めてモジュール名と完全に一致しなければなりません。大文字・小文字だけが違うファイルが複数あると
  （`Geometry.cscr` と `geometry.cscr`）、import はあいまいになります（`CS_MODULE_AMBIGUOUS`）。
- モジュールのファイルをシンボリックリンクにできるのは、それがプロジェクトのルートの中の通常のファイルを指すときだけです。
  外を指すリンクは `CS_MODULE_OUTSIDE_PROJECT` になります。壊れたリンク、ディレクトリ、UTF-8 でないファイル、3,000,000 バイトより大きいファイルは
  `CS_MODULE_UNREADABLE` になります。モジュール名は識別子 1 つなので、`/`・`..`・絶対パスを含められません。
- モジュールの中の診断は、モジュールのファイルを示します。例：
  `my-project/geometry.cscr:2:12: error CS_SEM_TYPE_MISMATCH ("Int", "String")`。モジュールの関数の中の実行時エラーも、
  モジュールのファイル・行・列を示します。
- import の解決のエラーはコンパイルエラーです（終了コード 2）。例：`my-project/main.cscr:1:8: error CS_MODULE_NOT_FOUND ("geometry")`。
- すべてのファイルを 1 つのプログラムにコンパイルし、1 つの仮想機械で実行します。そのため、`--instructions-per-tick`・`--max-ticks`・
  言語の上限は、プログラム全体に適用されます。
- `import` の無いファイルは、これまでとまったく同じにコンパイルします。

`createscript install`（開発中）は、パッケージをダウンロードして `.createscript/packages/` にインストールします。
`createscript run [directory]` がそれを使います（[プロジェクトを実行する](#プロジェクトを実行する) を参照）。

## プロジェクトのファイル

開発中です（v0.1.0-alpha には入っていません）。形式は [プロジェクトのファイル](packages.md) を参照してください。

- `createscript init [directory] [--name <name>]` は、`createscript.json` と `src/main.cscr` を作ります。`.gitignore` が無ければ、
  `/.createscript/` という行を持つ `.gitignore` も作ります（既存の `.gitignore` は決して変えません）。ファイルを上書きすることはなく、
  途中で失敗したら、自分が作ったものだけを消します（ファイルを作れないときは終了コード 73）。
- `createscript manifest check [directory]` は、`createscript.json` とそのエントリーのファイルを確かめます。
- `createscript lock check [directory]` は、`createscript.lock` を `createscript.json` と照らし合わせて確かめます。
- `createscript pack [directory] [--output <file>]` は、ライブラリのプロジェクト（スキーマの版 2）から `createscript-package.zip` を作ります。
  同じファイルからは、いつも同じバイト列になります。既存のファイルは決して上書きしません。
- `createscript install [github:<owner>/<repo>@v<version>] [directory] [--offline]` は依存をインストールし、パッケージを指定したときは、
  それを `createscript.json` に加えます（[パッケージのインストール](packages.md#パッケージのインストールs82-phase-3) を参照）。
- `createscript install <name>@<version> [directory] [--offline]` は、署名付きの CreateScript-Registry の index で審査されたパッケージをインストールします
  （S8.3 Phase 3）。**公式のレジストリはまだ公開していない** ので、今は通信せずに終了コード 69 で止まります
  （[registry.md](registry.md#クライアントcreatescript-install-nameversionphase-3) を参照）。
- ディレクトリを省くと、今いるディレクトリを使います。確かめるコマンドは、ファイルを読むだけです。`createscript run [directory]` はこれらのファイルを読みますが、
  `createscript run <file>.cscr` は読みません。

| 終了コード | 意味 |
|---|---|
| 0 | 問題なし |
| 64 | 使い方の誤り |
| 65 | `createscript.json` か `createscript.lock` の問題（`file:line:column: error CS_PKG_…` の形で表示） |
| 66 | `createscript.json` かディレクトリが存在しない、またはファイルを読めない |
| 69 | `install`：ネットワークを使えない。または `--offline` でパッケージがキャッシュに無い |
| 73 | `init`：ファイルを作れない（すでにある、または書き込みに失敗した）。`install`：ファイルが想定外の状態（シンボリックリンク、変わったファイル、終わらせることも元に戻すこともできない中断したインストール） |
| 75 | `install`：プロジェクトで別のインストールが動いているか、`run` が確かめている最中で、30 秒以内に終わらなかった。`run`（プロジェクトの実行）：プロジェクトでインストールが動いていて、10 秒以内に終わらなかった |

## 対話モード（REPL）

```text
$ createscript
CreateScript 0.1.0-alpha
Type "help" for help, "exit" to quit.
>>> 1 + 1
2
>>> var counter = 0
>>> counter = counter + 1
>>> counter
1
>>> fun square(n: Int): Int {
...   return n * n
... }
>>> square(7)
49
>>> exit
```

- 1 回に、文・宣言・式を 1 つずつ入力します。式の値は表示されます。
- 変数・関数・クラス・オブジェクト・乱数の状態は、後の入力のために保たれます。前の入力をもう一度実行することはありません。
  REPL は、検証した仮想機械のスナップショットから続きを実行します。
- `{`・`(`・`[` があると、閉じるまで `...` の行で入力が続きます。`} else {` は 1 行に書いてください。
- `help` でヘルプを表示します。`exit`・`quit`・入力の終わり（Ctrl+D）で、終了コード 0 で抜けます。
- コンパイルエラー、実行時エラー、100,000 tick を超えた入力は、セッションに加えません。
- セッション全体が 1 つのプログラムなので、言語の上限に従います（例えばソースは 1,000,000 文字、変数は 64 個、定数は 256 個）。
  上限に達したら REPL はそれを伝え、前の状態を保ちます。
- 対話モードでは `import` を使えません。`import` のある入力は `<input>:1:8: error CS_MODULE_IMPORT_UNAVAILABLE ("geometry")` になり、
  セッションに加えません。

### 行編集

標準入力と標準出力がどちらも端末のとき、REPL は JLine 3（3.30.17）で行を読みます。それ以外のとき（パイプ、ファイル、`TERM=dumb`）は、
行編集なしで読みます。

| キー | 動作 |
|---|---|
| ↑ / ↓ | 履歴の前 / 次の入力 |
| ← / → | カーソルを動かす |
| Home / End, Ctrl+A / Ctrl+E | 行の先頭 / 末尾 |
| Backspace / Delete | カーソルの前 / 位置の文字を消す |
| Ctrl+C | 今の入力（書きかけの `...` の行も含む）を捨てる。REPL は続く |
| Ctrl+D | 空の行なら REPL を抜ける。そうでなければカーソルの位置の文字を消す |
| Tab | タブを入れる（補完はない） |

- 履歴はメモリーの中だけにあり、ファイルには書きません。
- 履歴の展開（`!!`）、括弧付きの貼り付け（bracketed paste）、補完は無効にしています。そのため、行編集が無いときと同じ意味の入力になります。
- プログラムの実行中の Ctrl+C は、これまでどおり `createscript` を終了コード 130 で終わらせます。
- JLine は、端末を設定するために、システムの `stty`・`tty`・`test` と、必要なら `infocmp` を、決まった引数で実行します。
  CreateScript のプログラムがコマンドを実行することはできません。JLine のネイティブライブラリは使いません。

## 安全性

CLI が読むのは、指定されたファイルと、`import` のためにプロジェクトのルートの直下にある `name.cscr` のファイルだけです（ルートの中に
とどまることを確かめた後）。CreateScript のプログラムは、ファイル・ネットワーク・OS・Java や Kotlin のコードに触れられません。
`--max-ticks` と `--instructions-per-tick` がどの実行の仕事の量も抑え、言語の上限がメモリーを抑えます。

## Java を同梱したパッケージ

`createscript-cli/packaging/` は、`jlink` で作った最小の Java 21 のランタイムを含むパッケージを作ります。そのため、利用者は Java を用意しなくて済みます。

| OS | パッケージ | インストール先 |
|---|---|---|
| macOS（arm64 か x86_64） | `createscript-<version>-macos-<arch>-unsigned.pkg` | `/usr/local/lib/createscript`、コマンドは `/usr/local/bin/createscript` |
| Linux（x86_64 か aarch64） | `createscript-<version>-linux-<arch>.tar.gz`（`install.sh` 付き） | `~/.local/lib/createscript`、コマンドは `~/.local/bin/createscript` |

```bash
# macOS（JDK の CPU が、パッケージの CPU を決める）
JAVA_HOME=/path/to/jdk-21 createscript-cli/packaging/macos/build-pkg.sh

# Linux（その CPU 用の JDK 21 を使って Linux で実行する。GNU tar が必要）
JAVA_HOME=/path/to/jdk-21 createscript-cli/packaging/linux/build-tarball.sh
```

- CI で確認済み：Linux のパッケージは x86_64 と aarch64、macOS のパッケージは arm64（インストール・実行・アンインストール）。
  macOS x86_64 のパッケージは確認していません。README の対応環境の節を参照してください。
- macOS のパッケージは **署名も公証もしていません**。ローカルでのテスト用で、ダウンロードしたものは Gatekeeper が止めます。
- ランタイムは `JAVA_HOME` の JDK から作ります。パッケージは、その JDK を `BUILD-INFO.txt` に記録し、
  `legal/JAVA-RUNTIME-SOURCE.txt` にそのソースコードの入手先を書きます。
- 同梱した部品のライセンス：パッケージの中の `legal/THIRD-PARTY-NOTICES.md`。
