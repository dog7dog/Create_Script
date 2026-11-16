# CreateScript

[English](README.md)

CreateScript は、Kotlin に似た書き方の、静的型付けの小さなスクリプト言語です。プログラムは、安全に区切られた仮想機械（VM）で、
決まった量ずつ（tick ごとに）実行されます。信頼できないスクリプトを動かすアプリケーションに組み込むことを想定しています。
このリポジトリには、次のものがあります。

- **`createscript-core`**：字句解析・構文解析・意味解析・バイトコードへのコンパイル・VM。Kotlin/JVM のライブラリで、依存は Kotlin の標準ライブラリだけです。
- **`createscript-cli`**：`createscript` コマンド。`.cscr` ファイルとプロジェクトを実行します。行編集つきの対話モード（REPL）もあります。
- **`createscript-project`**：プロジェクトのファイル `createscript.json`・`createscript.lock` の読み取り（厳密な JSON）・検査・正規形、パッケージのアーカイブ、
  計画中のパッケージのレジストリの形式・署名の検証（[docs/ja/registry.md](docs/ja/registry.md)）。ファイルとネットワークには触れません。
- **`createscript-install`**：`createscript install` のための、パッケージの取得・検査・インストール。
- **`createscript-registry-tool`**・**`registry-template/`**（運用していません）：計画中のレジストリを運営するための道具と雛形。`createscript` の配布物には含まれません。

> **CreateScript-Registry はまだ公開していません。メンテナーを募集しています。** クライアント側（Ed25519 の署名を確かめた index を使う
> `createscript install <名前>@<版>`）はありますが、レジストリのリポジトリ・本番の署名鍵・公開した index が無いため、このコマンドは今は通信せずに拒否します。
> パッケージは `createscript install github:owner/repo@v1.2.0` でインストールしてください（[docs/ja/registry.md](docs/ja/registry.md)）。
> 公開前のセキュリティ監査（`docs/audits/`）の指摘はコードで対処しましたが、公開には GitHub の設定、本番の署名鍵、3 人以上のメンテナーがまだ必要です。

> **状態：0.1.0-alpha。** 言語と API は、まだ変わることがあります。
> [v0.1.0-alpha](https://github.com/dog7dog/Create_Script/releases/tag/v0.1.0-alpha) を、GitHub Releases にプレリリースとして公開しています（2026-11-04）。
> Linux x86_64・aarch64 用の `.tar.gz`、署名なしの macOS arm64 用の `.pkg`、`SHA256SUMS` があります。
> その後に加えたモジュール・プロジェクトのファイル・パッケージ・第 9 段階の言語機能は、**まだリリースしていません**（[CHANGELOG.md](CHANGELOG.md) の「Unreleased」）。
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
- まだリリースしていない機能：ラムダを使うリストの関数（`where`・`map`・`group`・`each`・`average`）、パイプライン演算子 `x |> f(a)`、
  エラー時の代替値 `a ?! b`、関数参照とラムダの値（[docs/ja/language.md](docs/ja/language.md)）。
- まだリリースしていない機能：ローカルのモジュール。`import geometry` と書くと、同じプロジェクトの `geometry.cscr` の関数を `geometry.square(5)` の形で使えます。
  ファイルをまたいで型を検査します。モジュールはプロジェクトのディレクトリからだけ読みます。インストールしたパッケージのモジュールは、
  `createscript run [ディレクトリ]` で使えます（[モジュール](docs/ja/language.md#モジュールローカルの-import)）。
- まだリリースしていない機能：プロジェクトのファイルとパッケージ。`createscript init` で、マニフェスト `createscript.json` のあるプロジェクトを作れます。
  `createscript manifest check`・`createscript lock check` で、マニフェストとロックファイルを確かめます。`createscript pack` で、ライブラリのプロジェクトから
  パッケージのアーカイブ（`createscript-package.zip`。同じファイルからは同じバイト列）を作れます。`createscript install github:owner/repo@v1.2.0` で、
  GitHub Releases からパッケージと依存を SHA-256 を確かめてインストールでき、`createscript run` で、通信せずにそれらを使って実行できます。
  import できるのは、直接の依存の公開モジュールだけです（[プロジェクトのファイル](docs/ja/packages.md)）。
  レジストリが公開された後は、`createscript install <名前>@<版>` で、署名済みの CreateScript-Registry の index で審査済みのパッケージを名前でインストールできます
  （まだ使えません。[docs/ja/registry.md](docs/ja/registry.md)）。
- 安全な実行：スタック・呼び出しの深さ・オブジェクト・リストの要素に固定の上限があり、1 tick の命令の数も決まっています。
  入力とプログラムの大きさの上限はホストが選びます。埋め込み先では小さく（4096 文字・4096 命令）、`createscript` コマンドでは大きくなります
  （1 ファイル 1,000,000 文字・モジュール 1024 個・命令数の固定の上限なし。[上限と安全性](docs/ja/language.md#上限と安全性)）。
  ファイル・ネットワーク・OS・JVM には触れられません。
- 診断は、決まったコード（`CS_SEM_TYPE_MISMATCH` など）と行・列で報告します。

## 文書

すべての文書は [docs/ja/README.md](docs/ja/README.md) に一覧があります（日本語版。英語の原文は `docs/` にあります）。主なもの：

- [言語の基本](docs/ja/language.md)
- [`createscript` コマンド・REPL・行編集](docs/ja/cli.md)
- [サンプル](docs/ja/samples.md)
- [プロジェクトのファイル（createscript.json・createscript.lock）](docs/ja/packages.md)
- [Core の組み込み](docs/ja/api/embedding.md)・[プロジェクトのファイルの API](docs/ja/api/project-files.md)・[API の範囲](docs/ja/api/api-surface.md)・
  [互換性の方針](docs/ja/api/compatibility.md)
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
`createscript-cli/packaging/` のスクリプトで作れます（[docs/ja/cli.md](docs/ja/cli.md)）。

## Core をライブラリとして使う

Core は、まだ Maven のリポジトリに公開していません。入口は `com.creatrbypc.createanalytical.script.api.CreateScript` です
（パッケージ名は、CreateScript を作り始めたプロジェクトに由来します）。

```kotlin
val result = CreateScript.compile("println(6 * 7)")
val program = result.program ?: error(result.diagnostics)
val run = CreateScript.start(program, RunOptions(instructionsPerTick = 128, randomSeed = 0)) { line -> println(line) }
while (!run.isFinished) run.tick()
```

モジュール・上限・実行の保存と再開（保存した状態に含まれないもの）・スレッドは、[docs/ja/api/embedding.md](docs/ja/api/embedding.md) で説明しています。
互換性を約束する API の一覧は [docs/ja/api/api-surface.md](docs/ja/api/api-surface.md) にあります。そのほかの公開クラスは内部用で、
`@InternalCreateScriptApi` の印が付いています。

## 対応環境

次の表は、公開済みの v0.1.0-alpha（タグでの CI）の結果です。

| 環境 | 状態（v0.1.0-alpha） |
|---|---|
| Ubuntu x86_64（`ubuntu-24.04`） | CI で確認済み：ビルド・すべてのテスト・サンプル・REPL、`.tar.gz` のインストール・実行・アンインストール |
| Ubuntu aarch64（`ubuntu-24.04-arm`） | CI で確認済み：`.tar.gz` の作成・インストール・実行・アンインストール（単体テストは x86_64 と macOS だけで実行） |
| macOS arm64（Apple Silicon） | CI（`macos-15`）で確認済み：ビルド・すべてのテスト・サンプル・REPL、`.pkg` のインストール・実行・アンインストール。ローカルでも、ビルド・テストと `.pkg` の中身を確認済み（ローカルではインストールしていない） |
| macOS x86_64（Intel） | 未確認（パッケージのスクリプトは対応しているが、実行したことはない） |
| Windows | 未確認（`installDist` が `createscript.bat` を作るが、実行したことはない） |
| macOS の署名・公証 | 未実施。`.pkg` は署名していないため、公開の配布には使えない |

まだリリースしていない変更（モジュール・プロジェクトのファイル・パッケージ・第 9 段階）は、2026-11-15 に Pull Request #1（`9e3c2b3`）と、
その `main` へのマージ（`b80f73d`）の CI を通りました。`ubuntu-24.04` と `macos-15` でのビルド・すべてのテスト・サンプル、
Linux x86_64・aarch64 の `.tar.gz` と macOS arm64 の `.pkg` の作成・インストール・再インストール・実行・アンインストールです。まだ確認していないもの：

- `createscript install` が実際の GitHub の Release から取得すること（キャッシュのアーカイブと、テスト用のサーバーで確認）。
- Windows。インストール中にパスの途中のディレクトリをリンクに差し替えられることへの対策（L-5）は、macOS と Linux だけに実装しています。
- 署名付きの CreateScript-Registry：公開していません（上を参照）。

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
