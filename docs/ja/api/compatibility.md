# 互換性の方針

> **原文（英語）**：[docs/api/compatibility.md](../../api/compatibility.md)（コミット `4a2192b` の時点）。内容が食い違うときは英語版が正です。

この方針は、`createscript-core` と `createscript-project` の **安定 API（Stable）** に適用します。どの宣言が安定 API かは
[api-surface.md](api-surface.md) で分類しています。方針は段階 A1 で決め、A2〜A4 でコードに反映しました。

> [!NOTE]
> 「安定 API」は互換性を約束する部分、「内部 API（Internal API）」は同じリポジトリのほかのモジュールが使うために公開しているだけで、
> 約束しない部分です。この 2 つの違いが、この文書の前提です。

## 規則

- **1.0 より前**は、リリースで安定 API を壊すことがあります。ただしその場合は必ず、`CHANGELOG.md` の `### Changed` か
  `### Removed` の見出しの下に、壊した内容と **代わりに使うもの** を書きます。
- `v0.1.0-alpha` にあった安定 API の宣言は、**JVM のシグネチャを保ちます**（バイナリ互換性）。既存の `Program` と
  `CompiledProgram` の互換用コンストラクター（`@Deprecated(level = HIDDEN)`）が、そのやり方の例です。
- 安定 API のデータクラスに新しいプロパティを足すときは、**既定値を付けて既存のプロパティの後ろ** に置きます。こうすると
  コンストラクターと `copy` は今までどおり使え、古い JVM シグネチャは隠したオーバーロードが残します。
- **保存状態**：以前のリリースが作った `VmSnapshot` も、`CreateScript.resume` で受け付けなければなりません
  （言語の変更は、保存状態が使い続けられるように行っています。例えば段階 9.0 と 9.2。
  [../language.md](../language.md) を参照）。
- **診断コードの ID**（`CS_…`・`CS_PKG_…`）は安定 API です。一度使った ID を、別の意味で使い直すことはありません。
  新しいコードはどのリリースでも増えることがあるので、`DiagnosticCode` や `ProjectDiagnosticCode` に対する `when` には
  `else` が必要です。`DiagnosticPhase` も同じです（`DiagnosticPhase.MODULE` を足したときに、すでにお知らせしています）。
- **内部 API** は、CHANGELOG に書かずに、どのリリースでも変わることがあります。

> [!TIP]
> 利用者から見たまとめ：互換性を約束するのは安定 API だけです。`DiagnosticCode` などに対する `when` には `else` を書いてください。
> 安定 API のデータクラスの新しいプロパティは、既定値付きで既存のものの後ろに加わります。

## 内部 API とオプトイン

このリポジトリのほかのモジュールが使うため、または `v0.1.0-alpha` で公開されていたために公開しているだけの宣言には、
`@InternalCreateScriptApi`（core）か `@InternalCreateScriptProjectApi`（project）の印が付いています。
これを使うと、呼び出し側が `@OptIn(...)` か `-opt-in=...` でオプトインするまで、コンパイラーが **警告** を出します（エラーではありません）。
そのため、`v0.1.0-alpha` に対して書かれ、これらを使っているコードも、そのままコンパイルできます。

<details>
<summary>警告を消したいとき（内部 API と分かったうえで使う場合）</summary>

使う側のファイルや関数に `@OptIn(InternalCreateScriptApi::class)` を付けるか、ビルドの設定でコンパイラーに
`-opt-in=com.creatrbypc.createanalytical.script.InternalCreateScriptApi` を渡します。どちらの場合も、内部 API は予告なく変わることがある点は同じです。

</details>

## 規則の確かめ方

- `api/createscript-core.api` と `api/createscript-project.api` は、JVM から見た公開 API の一覧です。オプトインの印、
  null 可否（nullability）、定数の値も載せています。コンパイルした API がこの一覧と違うと、`ApiListingTest` が失敗します。
  API を **意図して** 変えるときは、`CREATESCRIPT_API_UPDATE=1` を付けてテストを実行して一覧を作り直し、その差分を確認します
  （[api-surface.md](api-surface.md) の「API の一覧（A3）」の節を参照）。
- `BinaryCompatibilityTest` は、`v0.1.0-alpha` と S8.2 Phase 3 のモジュールの API に対してコンパイルしたコードを、今の Core で動かします。
  実行を保存し、項目ごとに作り直して再開するホストも含みます。
- `CreateScriptTest` は、保存と再開について文書に書いた挙動を確かめます。
