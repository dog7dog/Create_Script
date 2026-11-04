# CreateScript CLI

## 1. 概要

CreateScript CLI（コマンド名 `createscript`）は、CreateScript のプログラムをターミナルで実行するツールです。

- 言語処理系は `createscript-core` です。Core を組み込んだほかのアプリケーションと、コンパイル・診断・実行の結果は同じになります。
- 依存するのは、Core・Kotlin の標準ライブラリ（と、それが使う JetBrains の annotations）・JLine だけです。

## 2. 正式な拡張子 `.cscr`

CreateScript のソースファイルの正式な拡張子は **`.cscr`** です。

- `createscript run` は、ファイル名が `.cscr` で終わるファイルだけを実行します。
  - 大文字・小文字は区別します（`.CSCR` は受け付けません）。
  - `.cscr` だけの名前は受け付けません。
- 判定はファイル名（パスの最後の要素）だけで行います。ファイルの内容やディレクトリの名前は判定に使いません。
  - 例：`scripts.cs/ok.cscr` は実行できます。`scripts.cscr/ng.txt` は拒否します。
- `.cs` は CreateScript の正式なファイル形式ではありません。C# などのファイルと混同しないためです。
- 今後のファイルの関連付け、エディタの対応（構文の色分けなど）、サンプル、ドキュメントでも、`.cscr` を正式な拡張子として扱います。
- ソースは UTF-8 で書きます。UTF-8 として正しくないファイルは拒否します。先頭に BOM があると、Core が `CS_LEX_BYTE_ORDER_MARK` を報告します。

## 3. Gradle でのビルド

JDK 21 が必要です。リポジトリのルートで、次のコマンドを実行します。

```bash
./gradlew :createscript-cli:installDist
```

`createscript-cli/build/install/createscript/` に、次のものができます。
- `bin/createscript`：macOS・Linux 用の起動スクリプト
- `bin/createscript.bat`：Windows 用の起動スクリプト
- `lib/`：`createscript-cli.jar`・`createscript-core.jar`・`kotlin-stdlib`・`annotations`・`jline-reader`・`jline-terminal`

配布用の zip と tar も作れます（`./gradlew :createscript-cli:distZip :createscript-cli:distTar`。出力先は `build/distributions/`）。どちらも Java 21 が必要です。Java を同梱したインストーラーは、10 章を参照してください。

## 4. macOS・Linux での実行

起動スクリプトは POSIX の `sh` で動き、`JAVA_HOME`（または `PATH` の `java`）の Java 21 以降を使います。

```bash
createscript-cli/build/install/createscript/bin/createscript run createscript-cli/samples/hello.cscr
```

`bin/` を `PATH` に加えると、`createscript run hello.cscr` で実行できます。

## 5. コマンドとオプション

```
createscript                         （対話モード。5.1 を参照）
createscript run <file.cscr> [options]
createscript --help      (-h)
createscript --version
```

`run` は、ファイルを読む → コンパイルする → 成功したら実行する、の順に進みます。

- **標準出力：** `println` の出力を、出力した順にすべて書きます。
  - 行の数に上限はありません。Core のコンソールの履歴は最新の 32 行だけを持ちますが、CLI は 32 行を超えた分も欠かさず書きます。
  - 1 行の長さは、言語の上限と同じく 64 UTF-16 単位までで、超えた分は「…」で切ります。
- **標準エラー出力：** コンパイルと実行の診断を書きます。
- **`waitTicks(n)`：** 論理 tick を n 回数えるだけで、実時間では待ちません。

| オプション | 意味 | 既定値 | 受け付ける値 |
|---|---|---|---|
| `--seed <整数>` | 乱数の最初の状態（`random()` などの列が決まる。プログラムの `randomSeed(…)` で置き換えられる） | `0` | 64 ビットの符号付き整数（-9223372036854775808〜9223372036854775807） |
| `--instructions-per-tick <整数>` | 1 論理 tick に実行する命令の数（命令の予算） | `128` | 1〜4096 |
| `--max-ticks <整数>` | 進める論理 tick の上限。これを超えても終わらなければ、異常終了する | `100000` | 1〜1000000 |

**既定値と上限を決めた理由**
- **`--instructions-per-tick` の既定値 128：** 1 tick あたり 128 命令です。Core の実行速度の最高段階（`PowerLevel.FAST`）と同じ値です。
- **`--instructions-per-tick` の上限 4096：** 1 tick あたり最大 4096 命令です。`--max-ticks` の上限と合わせて、1 回の実行で進む命令の総数を抑えます。
- **`--max-ticks` の上限 1,000,000：** 20 tick/秒なら約 14 時間分です。2 つの上限の組み合わせでは、命令は最大で約 41 億になります。開発機の計測では、終わらないプログラムでも約 25 秒で止まります（既定値なら約 0.2 秒）。
- **`--seed` の既定値 0：** 同じファイルは毎回同じ結果になります。Core を組み込むアプリケーションでは、ホストが種を決めます。
- 回収（GC）の予算は、Core の既定値です（CLI からは変えられません）。

## 5.1 対話モード（REPL）

引数なしで `createscript` を実行すると、対話モードになります。

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

**使い方**
- 文・宣言・式を 1 つずつ入力します。式だけの入力は、その値を表示します（`println` と同じ形）。Unit の式（`println(…)` など）は値を表示しません。
- 変数・関数・クラス・オブジェクト・乱数の状態は、後の入力に引き継がれます。
- `{` `(` `[` が閉じるまで、`...` のプロンプトで続きの行を受け付けます。
  - `else` は `} else {` のように、閉じ括弧と同じ行に書きます。閉じ括弧の行で入力が終わるためです。
- `help` でヘルプを表示します。`exit`・`quit`・EOF（Ctrl+D）で終わります。どれも終了コードは 0 です。
  - 入力の途中で EOF になったときは、その入力は実行しません。
  - `help`・`exit`・`quit` は、入力の最初の行にこの語だけを書いたときにコマンドとして扱います。
- 標準出力にはプロンプトと `println` の出力を、標準エラー出力には診断を書きます。
  - 診断の位置は、入力の中の行と列（`<input>:行:列`）で表示します。

**前の入力は実行し直しません**

1. それまでに受け付けた入力（セッション）の後ろに、新しい入力をつないでコンパイルします。
2. 前の入力の終わりで取った VM の状態（スナップショット）から、続きだけを実行します。前の入力の `println` などの副作用は、くり返されません。
3. スナップショットは、Core の保存・復元と同じ検証（`SnapshotVerifier`）を通してから再開します。

そのため、REPL は区切りとして、各入力の後ろに `waitTicks(0)` を付けます。この区切りは REPL の中だけのもので、`.cscr` の実行には関係しません。

**エラーと上限**
- コンパイルエラー・実行時エラー・実行の上限（1 入力あたり 100,000 tick）に達した入力は、セッションに加えません。セッションは、その入力の前の状態のまま続きます。
  - 実行時エラーの入力では、エラーより前の変数の変更も取り消されます。ただし、エラーより前に表示した出力は残ります。
- 1 tick の命令数は 128（`run` の既定値と同じ）、乱数の種は 0 です。
- **セッション全体が、1 つのプログラムとしての言語の上限に従います。** ソース 4096 文字、変数 64 個、命令 4096、定数 256 などです。
  - 1 入力につき、区切りの分として 14 文字が加わります。例えば `println(…)` だけの入力なら、約 160 個まで入ります。
  - 上限に達すると、それ以上は加えられません（`the session has reached a CreateScript limit` と表示します）。それまでの状態は使い続けられます。新しいセッションにするには、`createscript` を起動し直します。
- 同じ名前の変数・関数・クラスは、宣言し直せません（1 つのプログラムと同じ規則です）。

## 5.2 対話モードの行編集（JLine）

端末から対話モードを起動したときは、JLine 3（3.30.17）で 1 行ずつ読み、行を編集できます。

**行編集を使う条件**

| 標準入力・標準出力 | 端末の種類（`TERM`） | 入力の読み方 |
|---|---|---|
| どちらも端末 | 端末の情報があるもの（`xterm-256color`・`screen-256color`・`vt100` など） | 行編集（JLine） |
| どちらも端末 | 未知の種類（端末の情報がない） | 行編集（JLine の標準の設定。カーソルを動かせない種類なら、行編集なし） |
| どちらも端末 | `dumb`・未設定 | 行編集なし |
| どちらかがパイプ・ファイル | — | 行編集なし |

- 端末かどうかは、Java の `System.console()` で判断します（Java 21 では、標準入力と標準出力がどちらも端末のときだけ null でない）。
- 行編集なしの読み方は、S5.1 までと同じです（表示・終了コードも同じ）。テストや `createscript < input.txt` は、この読み方になります。
- 端末を開けないとき（`stty` がないなど）も、行編集なしで読みます。

**キー操作**

| キー | 操作 |
|---|---|
| ↑ / ↓ | 1 つ前・後の入力（履歴）。入力中の文字があっても、それで始まる履歴を探さずに、1 つ前・後を出す |
| ← / → | カーソルを 1 文字動かす |
| Home / End、Ctrl+A / Ctrl+E | 行の先頭・末尾へ |
| Backspace / Delete | カーソルの前・位置の文字を消す |
| Ctrl+C | 入力中の行を捨てて、次の `>>> ` を表示する。`...` の続きの行の途中なら、その入力全体を捨てる。対話モードは終わらない |
| Ctrl+D | 空の行なら対話モードを終える（終了コード 0。`...` の途中なら、その入力は実行しない）。文字のある行では、カーソルの位置の文字を消す |
| Tab | Tab の文字を入れる（補完はしない） |
| その他 | JLine の emacs のキー操作（Ctrl+B / Ctrl+F・Ctrl+K・Ctrl+U・Ctrl+R の履歴検索など） |

- 日本語などの UTF-8 の文字は、表示の幅（全角は 2 桁）に合わせて編集します。入力・出力の文字コードは、これまでどおり UTF-8 です。
- 履歴はメモリの中だけに持ち、ファイルには保存しません（`createscript` を終えると消える）。`...` の続きの行も、1 行ずつ履歴に入ります。
- 入力の意味を変えないよう、JLine の次の機能は使いません。
  - 履歴の展開（`!!`・`!n`・`^a^b`）。`!` は、これまでどおり CreateScript の演算子です。
  - 貼り付けの一括入力（bracketed paste）。複数の行を貼り付けると、これまでどおり 1 行ずつ読みます。
  - 補完。
- 入力の長さの上限など、言語・セッションの上限は変わりません（ソース 4096 文字など）。

**Ctrl+C と信号**
- 入力中の Ctrl+C は、信号（SIGINT）ではなくキーとして読みます。入力の間だけ端末の `intr` を無効にし、入力の後で元に戻します。
- 実行中の Ctrl+C は、これまでどおり `createscript` を終了します（終了コード 130）。
  - 実行が終わる直前に押した Ctrl+C は、端末の設定を戻す前に届くと、次の入力の Ctrl+C（その行を捨てる）として扱われることがあります。
- JLine には OS の信号の処理を変えさせません。Ctrl+C 以外の信号（外から送られた SIGINT・Ctrl+Z など）の扱いは、S5.1 までと同じです。
  - 端末の大きさは、入力のたびに読み直します。入力中に端末の幅を変えたときは、次の入力から新しい幅で表示します。
  - 終了のときは、JLine が端末の設定を元に戻します。

**JLine が使う OS のコマンド**
- JLine の exec の方式で端末を扱います。端末の設定に、OS の `stty`・`tty`・`test` を固定の引数で実行し、端末の種類の情報がないときは `infocmp` を実行します（`PATH` から探す）。
- CreateScript のプログラムから、これらのコマンドや他のコマンドを実行することはできません。
- JLine のネイティブライブラリ（`jline-native`。すべての OS・CPU 向けのライブラリを含む）は使わず、配布物にも入れません。
- JLine の中のログ（警告など）は表示しません。

## 6. 実行例

```bash
$ createscript run createscript-cli/samples/hello.cscr
Hello, CreateScript!

$ createscript run createscript-cli/samples/features.cscr     # 乱数・Vector・Matrix・isClose
0.7415648787718233
1
5.0
[0.6, 0.8]
[[5.0, 11.0], [11.0, 25.0]]
true
false

$ createscript run createscript-cli/samples/wait.cscr         # waitTicks(20000) は待たずに進む
start
after 20000 ticks

$ createscript run createscript-cli/samples/type_error.cscr   # 終了コード 2
createscript-cli/samples/type_error.cscr:1:14: error CS_SEM_TYPE_MISMATCH ("Int", "String")

$ createscript run createscript-cli/samples/infinite_loop.cscr --max-ticks 1000   # 終了コード 3
createscript-cli/samples/infinite_loop.cscr: stopped after 1000 ticks (--max-ticks); the program did not finish
```

**サンプル（`createscript-cli/samples/`）**

| ファイル | 内容 |
|---|---|
| `hello.cscr` | 1 行を出力する |
| `features.cscr` | 乱数・Vector・Matrix・isClose |
| `many_lines.cscr` | 40 行を出力する（32 行を超える） |
| `wait.cscr` | `waitTicks` |
| `type_error.cscr` | 型の誤りの診断 |
| `infinite_loop.cscr` | 終わらないプログラム（`--max-ticks` で止まる） |

## 7. エラーと終了コード

診断の形式は `ファイル:行:列: error|warning コード (引数)` です。
- 行と列は 1 から数える UTF-16 単位です。
- コードは `CS_SEM_TYPE_MISMATCH` などの診断コードです。CLI はコードと引数を表示します（診断の文章や翻訳は、このリポジトリには含まれません）。
- 使い方とファイルの誤りは `createscript: …` の形で表示します。

| 終了コード | 意味 |
|---|---|
| 0 | 最後まで実行した |
| 1 | 実行時エラー（`CS_RUN_*`）。その時点までの出力は標準出力に残る |
| 2 | コンパイルエラー（実行しない） |
| 3 | `--max-ticks` に達しても終わらなかった |
| 64 | 使い方の誤り（コマンドやオプションの誤り、値の誤りや範囲外、`.cscr` でないファイル） |
| 65 | ファイルの内容を扱えない（UTF-8 でない、大きすぎる。4096 文字を超え得る 12288 バイト超は読まずに拒否する） |
| 66 | ファイルがない、通常のファイルでない、読めない |

64〜66 は `sysexits.h` の EX_USAGE・EX_DATAERR・EX_NOINPUT と同じ番号です。

## 8. 実行の制限と安全対策

- **無限ループ：** `--max-ticks` で必ず止まります（上限 1,000,000）。1 tick の命令数も `--instructions-per-tick`（上限 4096）で制限するので、1 回の実行で進む命令の数には上限があります。
- **VM の制限：** Core の制限がすべてそのまま働きます。
  - ソース 4096 文字、命令 4096、スタック 64、呼び出しの深さ 16、オブジェクト 256、リストの要素など
  - 回収（GC）の予算
- **出力のコールバック：**
  - VM の 1 tick の処理が終わった後に呼ばれます。命令の途中には入りません。
  - コールバックの中から実行を進めることはできません（Core の `ScriptRun` が拒否します）。
- **できないこと：** CLI は、指定された 1 つの `.cscr` ファイルを読むだけです。CreateScript から Java・Kotlin のコード、OS のコマンド、ファイル、ネットワークを使う機能はありません。言語に、そのための命令もありません。

## 9. Core API との関係

CLI は Core の公開 API だけを使います。

| CLI がすること | 使う Core API | 書く先 |
|---|---|---|
| コンパイル | `CreateScript.compile(source)` | 診断は標準エラー出力 |
| 実行の開始 | `CreateScript.start(program, RunOptions(instructionsPerTick, randomSeed)) { line -> … }` | `println` の行は標準出力 |
| 実行を進める | `ScriptRun.tick()` を `--max-ticks` 回まで呼ぶ | — |
| 結果 | `ScriptRun.state` と `error` | 実行時エラーは標準エラー出力 |

CLI に固有の既定値と上限（拡張子・`--max-ticks`・命令数の上限）は、CLI 側（`CliLimits`）で管理します。Core の仕様は変えていません。

## 10. インストーラーと配布パッケージ（macOS・Linux）

Java・Gradle・Kotlin を入れていない環境でも `createscript` を使えるように、Java 21 のランタイムを同梱したパッケージを作れます。

| OS | 配布物 | インストール先 | コマンド |
|---|---|---|---|
| macOS | `createscript-<版>-macos-<CPU>-unsigned.pkg` | `/usr/local/lib/createscript` | `/usr/local/bin/createscript`（リンク） |
| Linux | `createscript-<版>-linux-<CPU>.tar.gz` | `~/.local/lib/createscript` | `~/.local/bin/createscript`（リンク） |

- CPU は、macOS が `arm64`（Apple Silicon）または `x86_64`（Intel）、Linux が `x86_64` または `aarch64` です。CPU ごとに別のパッケージになり、1 つのパッケージには 1 つの CPU 用のランタイムだけが入ります。
- CI で、Linux の `x86_64`・`aarch64` と macOS の `arm64` のパッケージのインストール・実行・アンインストールを確認しています。macOS の `x86_64` は未確認です。
- それぞれの配布物の横に、SHA-256 のチェックサム（`.sha256`）を作ります。
- 入るものは次のとおりです。
  - CLI（`createscript-cli.jar`）
  - Core（`createscript-core.jar`）
  - `kotlin-stdlib-2.0.0.jar`
  - `annotations-13.0.jar`
  - `jline-reader-3.30.17.jar`・`jline-terminal-3.30.17.jar`（対話モードの行編集。5.2）
  - Java のランタイム
  - ライセンスの文書

### 10.1 方式

| 候補 | 判断 |
|---|---|
| `installDist` / `distZip` / `distTar` | Java のランタイムを含まない（利用者に Java 21 が必要）。jar と起動スクリプトの元として使う |
| `jlink` | **採用。** CLI に必要なモジュール（`jdeps` の結果。S5.3 からは JLine のログのための `java.logging` が加わり、`java.base`・`java.logging`）で、最小のランタイム（約 31 MB）を作る |
| `jpackage` | 採用しない。macOS では GUI 用の `.app` を作り、`--type pkg` は `/Applications` に入れる。`/usr/local/bin` のコマンドにはならない。版の形式も数字だけに限られる（`0.1.0-alpha` は使えない） |
| `pkgbuild` / `productbuild` | **採用（macOS）。** シェルの起動スクリプトと jlink のランタイムを `.pkg` にする |
| 自己完結の `.tar.gz` と `install.sh` | **採用（Linux）。** root 権限なしで、利用者のディレクトリに入れる |

起動スクリプト（`bin/createscript`）は POSIX の `sh` で書いてあります。
- シンボリックリンクをたどって、自分のディレクトリを見つけます。
- 同梱のランタイム（`runtime/bin/java`）で CLI を起動します。
- `JAVA_HOME`・`PATH` の `java`・`JAVA_OPTS` は使いません。
- JVM のオプションは `installDist` の起動スクリプトと同じで、何も付けません。

### 10.2 パッケージの作り方

```bash
# macOS（Apple Silicon の JDK 21 なら arm64、Intel の JDK 21 なら x86_64 のパッケージになる）
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
  createscript-cli/packaging/macos/build-pkg.sh

# Linux（Linux 上で、その CPU 用の JDK 21 を使う。GNU tar が必要）
JAVA_HOME=/path/to/jdk-21 createscript-cli/packaging/linux/build-tarball.sh
```

出力先は `createscript-cli/build/packages/`、作業用のディレクトリは `createscript-cli/build/packaging/` です。どちらも Git の管理外です。

**ランタイムの作り方**
- ランタイムは、`JAVA_HOME` の JDK の `jlink` と `jmods` で作ります（`--strip-debug --no-header-files --no-man-pages --compress=zip-6`）。
- JDK の OS と、ビルドする機械の OS が違うときは止まります。そのため、macOS で Linux 用のランタイムは作りません。

**記録とチェック**
- 使った JDK の配布元・版・CPU、モジュール、コミットを、パッケージの中の `BUILD-INFO.txt` に書きます。
- その JDK のソースコードの入手先（JDK の `release` に記録されたリポジトリとコミット、Temurin ならそのビルドのリリースのページ）を、`legal/JAVA-RUNTIME-SOURCE.txt` に書きます。
- jar に、ほかのアプリケーション向けのフレームワークやその設定ファイルがあったら、作るのをやめます。
- jar に CreateScript の外のクラスがあったら、作るのをやめます。
- ソース、Gradle の設定、秘密情報の形式のファイル、絶対パスのシンボリックリンクがあったら、作るのをやめます。

**権限と時刻**
- 権限は、ディレクトリと実行ファイルが 755、ほかのファイルが 644 です。
- ファイルの時刻は、最後のコミットの時刻にそろえます（`SOURCE_DATE_EPOCH` で変えられます）。

**再現性**
- 同じコミットと JDK から作ると、ランタイム・Core の jar・ライセンス・起動スクリプトは、バイト単位で同じになります。
- CLI の jar は、中身が同じでも、zip に記録される時刻が Gradle のビルドごとに変わります。そろえるには Gradle の設定（`preserveFileTimestamps`）の変更が必要ですが、まだ変えていません。
- `.pkg` は、xar の目次に作った時刻が入るため、ファイル全体のチェックサムは毎回変わります。

**macOS の拡張属性**
- macOS は、アプリが作ったファイルに、消せない拡張属性 `com.apple.provenance` を付けます。`pkgbuild` は、これを `._*` ファイルとして payload に入れてしまいます。
- そのため、`build-pkg.sh` は payload と Bom と scripts を cpio と mkbom で作り直します（所有者は root:wheel）。そのあとで、`._*` ファイルがないことと、ファイルの数を確かめます。

### 10.3 macOS でのインストール

```bash
shasum -a 256 -c createscript-<版>-macos-arm64-unsigned.pkg.sha256
sudo installer -pkg createscript-<版>-macos-arm64-unsigned.pkg -target /
createscript --version
```

Finder でダブルクリックしてもインストールできます。`/usr/local/bin` が `PATH` にあれば（macOS の既定）、どのディレクトリからでも `createscript` で起動できます。

- **CPU：** パッケージと違う CPU の Mac では、インストールを止めます（`hostArchitectures` と preinstall の両方で確かめます）。
- **macOS の版：** ランタイムが対応する版（同梱の `java` の最小版。現在は 11.0）より前の macOS には、インストールできません。
- **競合：** 次のときは、何も変えずにインストールを止めます。
  - `/usr/local/bin/createscript` があり、このパッケージが作ったリンク（`../lib/createscript/bin/createscript`）でないとき
  - `/usr/local/lib/createscript` があり、このパッケージの受領記録（`pkgutil --pkg-info com.creatrbypc.createscript`）がないとき
  - `/usr/local/lib/createscript` がシンボリックリンクのとき
- **再インストール・更新：** preinstall が、受領記録にある前のファイルを消してから、新しいファイルを入れます（古い版のファイルを残しません）。受領記録にないファイルは消しません。リンクがすでにこのパッケージのものなら、そのまま使います。
- **`/usr/local/bin` の扱い：** パッケージの payload には入れません。既存の `/usr/local/bin` の所有者や権限を変えないためです。ないときだけ、postinstall が作ります。

### 10.4 macOS でのアンインストール

```bash
/usr/local/lib/createscript/uninstall.sh --dry-run    # 消すものを表示するだけ
sudo /usr/local/lib/createscript/uninstall.sh
```

- 受領記録にあるファイルと、空になったディレクトリを消します。
- `/usr/local/bin/createscript` は、このパッケージが作ったリンクのときだけ消します。
- 最後に `pkgutil --forget com.creatrbypc.createscript` で、受領記録を消します。
- `/usr/local`・`/usr/local/bin`・`/usr/local/lib` は消しません。
- 利用者が `/usr/local/lib/createscript` に足したファイルは残し、そう表示します。

スクリプトを使わずに消す場合は、次のとおりです。
1. `pkgutil --files com.creatrbypc.createscript` で、ファイルを確かめる。
2. `sudo rm /usr/local/bin/createscript` と `sudo rm -r /usr/local/lib/createscript` を実行する。
3. `sudo pkgutil --forget com.creatrbypc.createscript` を実行する。

### 10.5 署名と公証（未実施）

`build-pkg.sh` が作るのは、**署名も公証もしていない**パッケージです。ファイル名の `-unsigned` はそのためです。

- 同じ Mac で作ったものは、ローカルのテストに使えます。
- ブラウザなどでダウンロードしたものは、Gatekeeper が止めます。そのため、**このままでは公開の配布に使えません。**
- 公開する場合は、Apple Developer ID で次の手順を行います。この手順はまだ実施しておらず、`build-pkg.sh` にも組み込んでいません。

1. **ランタイムの署名：** ランタイムのすべての実行ファイルとライブラリに、Developer ID Application の証明書で署名します。Hardened Runtime を有効にし、タイムスタンプを付けます。対象は `runtime/bin/*`、`runtime/lib/**/*.dylib`、`runtime/lib/jspawnhelper` です。

   ```bash
   codesign --force --timestamp --options runtime \
     --entitlements java.entitlements \
     --sign "Developer ID Application: <名前> (<Team ID>)" <ファイル>
   ```

   JVM の JIT のため、`java.entitlements` には `com.apple.security.cs.allow-jit` と `com.apple.security.cs.allow-unsigned-executable-memory` を入れます。
2. **パッケージの作成と署名：** 署名したファイルで `pkgbuild` と `productbuild` を行います。そのあとで、Developer ID Installer の証明書でパッケージに署名します。

   ```bash
   productsign --sign "Developer ID Installer: <名前> (<Team ID>)" unsigned.pkg createscript-<版>-macos-<CPU>.pkg
   ```

3. **公証：** 公証を受けて、結果をパッケージに付けます。

   ```bash
   xcrun notarytool submit createscript-<版>-macos-<CPU>.pkg --keychain-profile <プロファイル> --wait
   xcrun stapler staple createscript-<版>-macos-<CPU>.pkg
   ```

4. **確認：** 次のコマンドで確かめます。

   ```bash
   pkgutil --check-signature createscript-<版>-macos-<CPU>.pkg
   spctl -a -vv -t install createscript-<版>-macos-<CPU>.pkg
   ```

### 10.6 Linux でのインストールとアンインストール

```bash
sha256sum -c createscript-<版>-linux-x86_64.tar.gz.sha256
tar -xzf createscript-<版>-linux-x86_64.tar.gz
cd createscript
./install.sh                          # ~/.local/lib/createscript と ~/.local/bin/createscript
./install.sh --prefix "$HOME/tools"   # 別の場所
~/.local/lib/createscript/uninstall.sh
```

**install.sh の動き**
- root 権限は要りません。`~/.local/bin` が `PATH` にないときは、追加する行を表示します。
- 次のときは、何も変えずに止まります。
  - `~/.local/bin/createscript` があり、CreateScript が作ったリンクでないとき
  - `~/.local/lib/createscript` があり、install.sh が作ったもの（目録 `.createscript-install` がある）でないとき
  - `~/.local/lib/createscript` がシンボリックリンクのとき
  - ランタイムの OS か CPU が、この機械と違うとき
  - インストール先に `:` や改行が入るとき
- 新しいファイルは、まず同じディレクトリの中の一時ディレクトリにコピーし、そこで起動を確かめます。そのあとで、前のインストールと入れ替えます。途中で失敗したときは、前のインストールを元に戻し、一時ディレクトリと、その回に作ったディレクトリを消します。
- 何度実行しても同じ状態になります。同じ場所に前のインストールがあれば、入れ替えます。

**uninstall.sh の動き**
- 目録にあるファイルとディレクトリだけを消します。
- `~/.local/bin/createscript` は、install.sh が作ったリンクのときだけ消します。
- install.sh が作った `~/.local/bin`・`~/.local/lib`・`~/.local` は、空なら消します。前からあったものは消しません。
- 目録にあるディレクトリが、シンボリックリンクなどに置き換わっていたら、何も消しません。
- 利用者が足したファイルは残し、一覧を表示します。

### 10.7 ライセンス

CreateScript 自体（CLI・Core・起動スクリプト・インストール用のスクリプト）は、Apache License 2.0 で公開しています（リポジトリの `LICENSE` と `NOTICE`。パッケージでは `legal/APACHE-2.0.txt` と `legal/NOTICE`）。

パッケージの `legal/THIRD-PARTY-NOTICES.md` に、同梱する他者の部品とライセンスの一覧があります。

| 部品 | ライセンス | 同梱する文書 |
|---|---|---|
| Java ランタイム（Eclipse Adoptium Temurin 21 を jlink で縮小したもの） | GPLv2 と Classpath Exception、OpenJDK の第三者ライセンス | `runtime/legal/`（jlink が入れる）。ソースの入手先は `legal/JAVA-RUNTIME-SOURCE.txt` |
| Kotlin Standard Library 2.0.0 | Apache License 2.0 | `legal/APACHE-2.0.txt` |
| JetBrains Java Annotations 13.0 | Apache License 2.0 | `legal/APACHE-2.0.txt` |
| JLine 3.30.17（`jline-reader`・`jline-terminal`） | BSD 3-Clause License | `legal/BSD-3-CLAUSE-JLINE.txt` |
