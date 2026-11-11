# CreateScript

[English](README.md)

CreateScript は、Kotlin に似た書き方の、静的型付けの小さなスクリプト言語です。プログラムは、安全に区切られた仮想機械（VM）で、
決まった量ずつ（tick ごとに）実行されます。信頼できないスクリプトを動かすアプリケーションに組み込むことを想定しています。
このリポジトリには、次のものがあります。

- **`createscript-core`**：字句解析・構文解析・意味解析・バイトコードへのコンパイル・VM。Kotlin/JVM のライブラリで、依存は Kotlin の標準ライブラリだけです。
- **`createscript-cli`**：`createscript` コマンド。`.cscr` ファイルを実行します。行編集つきの対話モード（REPL）もあります。
- **`createscript-project`**（開発中。v0.1.0-alpha には入っていません）：プロジェクトのファイル `createscript.json`・`createscript.lock` の読み取り（厳密な JSON）・検査・正規形と、計画中のパッケージのレジストリの形式・署名の検証（[docs/registry.md](docs/registry.md)）。ファイルとネットワークには触れません。
- **`createscript-registry-tool`**・**`registry-template/`**（開発中。運用していません）：計画中のレジストリを運営するための道具と雛形。`createscript` の配布物には含まれません。

> **状態：0.1.0-alpha。** 言語と API は、まだ変わることがあります。
> [v0.1.0-alpha](https://github.com/dog7dog/Create_Script/releases/tag/v0.1.0-alpha) を、GitHub Releases にプレリリースとして公開しています（2026-11-04）。
> Linux x86_64・aarch64 用の `.tar.gz`、署名なしの macOS arm64 用の `.pkg`、`SHA256SUMS` があります。[対応環境](#対応環境)を参照してください。

```kotlin
class Counter {
  var value = 0
  fun add(step: Int): Int {
    value = value + step
    return value
  }
}
fun fib(n: Int): Int {
  if (n < 2) { return n }
  return fib(n - 1) + fib(n - 2)
}
val c = Counter()
for (i in 1..3) { c.add(i) }
println(c.value)   // 6
println(fib(10))   // 55
```

## 機能

- 型：`Int`（32 ビット）・`Float`・`Double`・`Boolean`・`String`・リスト・`Vector`・`Matrix`・クラス。型はコンパイルのときに検査します。
- `val` / `var`、`if` / `else`、`while`、整数の範囲の `for`、`break` / `continue`、関数（再帰を含む）、プロパティとメソッドを持つクラス、入れ子のリスト。
- `kotlin.math` と同じ結果の数学関数、種を決めると同じ列になる乱数、小さなベクトルと行列。
- ローカルのモジュール（開発中。v0.1.0-alpha には入っていません）：`import geometry` と書くと、同じプロジェクトの `geometry.cscr` の関数を `geometry.square(5)` の形で使えます。
  ファイルをまたいで型を検査します。インストールしたパッケージのモジュールは、`createscript run [ディレクトリ]` で使えます（[Modules](docs/language.md#modules-local-import)）。
- プロジェクトのファイル（開発中。v0.1.0-alpha には入っていません）：`createscript init` で、マニフェスト `createscript.json` のあるプロジェクトを作れます。
  `createscript manifest check`・`createscript lock check` で、マニフェストとロックファイルを確かめます。`createscript pack` で、ライブラリのプロジェクトから
  パッケージのアーカイブ（`createscript-package.zip`。同じファイルからは同じバイト列）を作れます。`createscript install github:owner/repo@v1.2.0` で、
  GitHub Releases からパッケージと依存を SHA-256 を確かめてインストールでき、`createscript run` で、通信せずにそれらを使って実行できます。
  import できるのは、直接の依存の公開モジュールだけです（[Project files](docs/packages.md)）。
- 安全な実行：スタック・呼び出しの深さ・オブジェクト・リストの要素に固定の上限があり、1 tick の命令の数も決まっています。
  入力とプログラムの大きさの上限はホストが選びます。埋め込み先では小さく（4096 文字・4096 命令）、`createscript` コマンドでは大きくなります
  （1 ファイル 1,000,000 文字・モジュール 1024 個・命令数の固定の上限なし。開発中。[Limits and safety](docs/language.md#limits-and-safety)）。
  ファイル・ネットワーク・OS・JVM には触れられません。
- 診断は、決まったコード（`CS_SEM_TYPE_MISMATCH` など）と行・列で報告します。

## 文書

- [言語の基本](docs/language.md)（英語）
- [`createscript` コマンド・REPL・行編集](docs/cli.md)（英語）
- [サンプル](docs/samples.md)（英語）
- [プロジェクトのファイル（createscript.json・createscript.lock）](docs/packages.md)（英語）
- CLI の詳しい説明（日本語）：[createscript-cli/README.md](createscript-cli/README.md)

## ビルド

JDK 21 が必要です（Gradle の Java toolchain の機能で、自動でダウンロードすることもできます）。Gradle Wrapper（Gradle 8.8）が入っています。

```bash
./gradlew build                                # コンパイルと、すべてのテスト
./gradlew :createscript-cli:installDist        # コマンドを作る
createscript-cli/build/install/createscript/bin/createscript run createscript-cli/samples/hello.cscr
createscript-cli/build/install/createscript/bin/createscript      # 対話モード
```

`installDist` で作る起動スクリプトには、`JAVA_HOME` か `PATH` に Java 21 が必要です。Java を同梱したパッケージは、
`createscript-cli/packaging/` のスクリプトで作れます（[docs/cli.md](docs/cli.md)）。

## Core をライブラリとして使う

Core は、まだ Maven のリポジトリに公開していません。入口は `com.creatrbypc.createanalytical.script.api.CreateScript` です
（パッケージ名は、CreateScript を作り始めたプロジェクトに由来します）。

```kotlin
val result = CreateScript.compile("println(6 * 7)")
val program = result.program ?: error(result.diagnostics)
val run = CreateScript.start(program, RunOptions(instructionsPerTick = 128, randomSeed = 0)) { line -> println(line) }
while (!run.isFinished) run.tick()
```

`import` のあるプログラム（開発中。v0.1.0-alpha には入っていません）では、モジュールのソースをホストがコンパイラーに渡します。Core 自身はファイルを読みません。
`ModuleResolver` はモジュール名（識別子 1 つ）を受け取り、そのソースか、使えない理由を返します。

```kotlin
val modules = mapOf("geometry" to "fun square(x: Int): Int {\n  return x * x\n}\n")
val result = CreateScript.compile("import geometry\nprintln(geometry.square(5))", { name ->
    modules[name]?.let { ModuleLookup.Found(it) } ?: ModuleLookup.NotFound
})
result.diagnostics.forEach { println("${it.module ?: "main"}: ${it.diagnostic.code.id}") }
val program = result.program ?: error("compile failed")
```

`CreateScript.compile(source, CompileOptions.STANDALONE)` は、独立版の大きな上限でコンパイルします（開発中。v0.1.0-alpha には入っていません）。
`CreateScript.compile(source)` と `CompileOptions.EMBEDDED` は、これまでどおりの小さな上限（4096 文字・4096 命令など）です。実行時の上限は、どちらでも変わりません。

`CreateScript.compile(source)` は、これまでと同じです。`import` のあるプログラムには `CS_MODULE_IMPORT_UNAVAILABLE` を報告します。
実行時エラーがどのファイルで起きたかは、`result.locate(run.error!!)` で分かります。

`CreateScript.resume` は、保存した状態の形だけを確かめ、どのプログラムの状態かは確かめません。実行を保存・復元するホストは、
再開の前に 2 つのハッシュを比べてください。`result.sourceFingerprint`（すべてのソースのハッシュ。`import` の無いプログラムでは
`ProgramFingerprint.ofSource(source)` と同じ値）と、`ProgramFingerprint.ofProgram(program)` です。こうすると、import したファイルだけを
変えたときも、前の状態を復元しません。

## 対応環境

次の表は、公開済みの v0.1.0-alpha（タグでの CI）の結果です。開発中の機能（ローカルのモジュール、プロジェクトのファイル、`pack`、`install`、
パッケージを使うプロジェクトの実行）は、今のところ macOS arm64 のローカルだけで確かめています（すべての単体テストと、コマンドの手での実行）。
CI・Linux・Windows では実行しておらず、`install` が実際の GitHub の Release から取得したこともまだありません（キャッシュのアーカイブと、テスト用のサーバーで確認）。
そのため、ファイルのロック・ファイルの同一性（`fileKey`）・原子的な名前の変更・Windows の名前の規則は、Linux と Windows では未確認です。

| 環境 | 状態（v0.1.0-alpha） |
|---|---|
| Ubuntu x86_64（`ubuntu-24.04`） | CI で確認済み：ビルド・すべてのテスト・サンプル・REPL、`.tar.gz` のインストール・実行・アンインストール |
| Ubuntu aarch64（`ubuntu-24.04-arm`） | CI で確認済み：`.tar.gz` の作成・インストール・実行・アンインストール（単体テストは x86_64 と macOS だけで実行） |
| macOS arm64（Apple Silicon） | CI（`macos-15`）で確認済み：ビルド・すべてのテスト・サンプル・REPL、`.pkg` のインストール・実行・アンインストール。ローカルでも、ビルド・テストと `.pkg` の中身を確認済み（ローカルではインストールしていない） |
| macOS x86_64（Intel） | 未確認（パッケージのスクリプトは対応しているが、実行したことはない） |
| Windows | 未確認（`installDist` が `createscript.bat` を作るが、実行したことはない） |
| macOS の署名・公証 | 未実施。`.pkg` は署名していないため、公開の配布には使えない |

CI では、どのジョブも Temurin 21 を使います。

## セキュリティ

脆弱性は、公開の場ではなく非公開で報告してください。[SECURITY.md](SECURITY.md) を参照してください。

## ライセンス

CreateScript は [Apache License 2.0](LICENSE) で公開しています。[NOTICE](NOTICE) も参照してください。配布用のパッケージには、
それぞれのライセンスに従う他者の部品（Kotlin の標準ライブラリ・JetBrains の annotations・JLine・OpenJDK のランタイム）も入ります。
[THIRD-PARTY-NOTICES.md](createscript-cli/packaging/common/THIRD-PARTY-NOTICES.md) を参照してください。

## AI の利用

このプロジェクトは、AI のコーディング支援（主に [Claude Code](https://claude.com/claude-code)）を使って開発しました。
その支援を受けたコミットには、`Co-Authored-By` の行が付いています。
