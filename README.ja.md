# CreateScript

[English](README.md)

CreateScript は、Kotlin に似た書き方の、静的型付けの小さなスクリプト言語です。プログラムは、安全に区切られた仮想機械（VM）で、
決まった量ずつ（tick ごとに）実行されます。もとは Minecraft の MOD「Create: Analytical」のプログラム可能なブロックのために作りました。
このリポジトリは、言語の部分だけを Minecraft から切り離したものです。

- **`createscript-core`**：字句解析・構文解析・意味解析・バイトコードへのコンパイル・VM。Kotlin/JVM のライブラリで、依存は Kotlin の標準ライブラリだけです。
- **`createscript-cli`**：`createscript` コマンド。`.cscr` ファイルを実行します。行編集つきの対話モード（REPL）もあります。

> **状態：0.1.0-alpha。** 言語と API は、まだ変わることがあります。配布用のパッケージは、まだ公開していません。
> [対応環境](#対応環境)を参照してください。

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
- 安全な実行：ソースの長さ・命令・スタック・呼び出しの深さ・オブジェクト・リストの要素に固定の上限があり、1 tick の命令の数も決まっています。
  ファイル・ネットワーク・OS・JVM には触れられません。
- 診断は、決まったコード（`CS_SEM_TYPE_MISMATCH` など）と行・列で報告します。

## 文書

- [言語の基本](docs/language.md)（英語）
- [`createscript` コマンド・REPL・行編集](docs/cli.md)（英語）
- [サンプル](docs/samples.md)（英語）
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
（パッケージ名は、CreateScript を作り始めた Minecraft の MOD に由来します）。

```kotlin
val result = CreateScript.compile("println(6 * 7)")
val program = result.program ?: error(result.diagnostics)
val run = CreateScript.start(program, RunOptions(instructionsPerTick = 128, randomSeed = 0)) { line -> println(line) }
while (!run.isFinished) run.tick()
```

## 対応環境

| 環境 | 状態 |
|---|---|
| macOS（Apple Silicon、Temurin 21）でのビルドとテスト | ローカルで確認済み |
| macOS での `installDist` の `createscript` | ローカルで確認済み |
| macOS の `.pkg`（署名なし） | ローカルで作成し、中身を確認済み。署名・公証をしていないため、公開の配布には使えない |
| Linux でのビルドとテスト | CI に設定済み。まだ確認していない |
| Linux の `.tar.gz` と `install.sh` | CI に設定済み。Linux の機械では、まだ確認していない |
| Windows | 未確認（`installDist` が `createscript.bat` を作るが、実行したことはない） |

## セキュリティ

脆弱性は、公開の場ではなく非公開で報告してください。[SECURITY.md](SECURITY.md) を参照してください。

## ライセンス

CreateScript は [Apache License 2.0](LICENSE) で公開しています。[NOTICE](NOTICE) も参照してください。配布用のパッケージには、
それぞれのライセンスに従う他者の部品（Kotlin の標準ライブラリ・JetBrains の annotations・JLine・OpenJDK のランタイム）も入ります。
[THIRD-PARTY-NOTICES.md](createscript-cli/packaging/common/THIRD-PARTY-NOTICES.md) を参照してください。

Minecraft の MOD「Create: Analytical」は別のプロジェクトで、このリポジトリには含まれません。

## AI の利用

このプロジェクトは、AI のコーディング支援（主に [Claude Code](https://claude.com/claude-code)）を使って開発しました。
その支援を受けたコミットには、`Co-Authored-By` の行が付いています。
