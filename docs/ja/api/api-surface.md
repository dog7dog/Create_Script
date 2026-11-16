# `createscript-core` と `createscript-project` の API の範囲

> **原文（英語）**：[docs/api/api-surface.md](../../api/api-surface.md)（コミット `a945a7b` の時点の英語版に、その後の段階 A5c の修正を反映）。内容が食い違うときは英語版が正です。

状態：**コードに反映し、検査し、文書化した（段階 A4）**。この文書は、2 つのライブラリのモジュールにある、公開されたトップレベルの宣言を
すべて分類します。段階 A1 で分類し、段階 A2 で Kotlin の explicit API モードと 2 つのオプトインの注釈を使ってコードに反映しました
（「A2 での反映」を参照）。段階 A3 では、リポジトリに置いた API の一覧で検査しています（「API の一覧」を参照）。段階 A4 では、
安定 API の部分に KDoc を書きました（「KDoc（A4）」を参照）。

`main` の `b80f73d`（2026-11-15）で棚卸しをしました。公開されたトップレベルの宣言は 172 個（core 122、project 50）でした。
メンバー（クラスの中の関数やプロパティ）は、表で特に書かない限り、それを含む宣言と同じ分類です。

> [!NOTE]
> この文書で使う 3 つの分類：**安定 API**（Stable。互換性を約束する）、**内部 API**（Internal API。公開しているが約束しない）、
> **モジュール内専用**（Module-private。Kotlin の `internal` で、ほかのモジュールからは見えない）。

## 分類（Tier）

| 分類 | 意味 | 印の付け方（A2） |
|---|---|---|
| **安定 API**（Stable） | ホストとツールが使えるように保守する部分。変えるときは下の互換性の規則に従う。 | ふつうの `public`。KDoc が必須（A4）。API の一覧に載る（A3）。 |
| **内部 API**（Internal API） | このリポジトリのほかのモジュールが使うため、または `v0.1.0-alpha` の一部だったために、JVM 上で公開している部分。互換性は約束せず、どのリリースでも変わることがある。 | `@InternalCreateScriptApi`（core）か `@InternalCreateScriptProjectApi`（project）。どちらも `@RequiresOptIn` の注釈で、水準は `WARNING`。CLI・install・registry-tool のモジュールはオプトインしている。 |
| **モジュール内専用**（Module-private） | 自分のモジュールの中だけで使い、`v0.1.0-alpha` にも無かった部分。 | `internal` 修飾子。 |

Kotlin の `internal` は、このリポジトリのモジュールも含め、**ほかのすべてのモジュール** から宣言を隠します。そのため、
`createscript-cli`・`createscript-install`・`createscript-registry-tool` が使う宣言は、`internal` ではなく **内部 API** にしています。

## 互換性の規則

安定 API を変えるときの規則、オプトインの方針、それらの確かめ方は [compatibility.md](compatibility.md) にあります（段階 A5a でそちらへ移しました）。
Core の組み込み方は [embedding.md](embedding.md)、プロジェクトのファイルの API の使い方は [project-files.md](project-files.md) にあります。

## `createscript-core`

### 安定 API

| パッケージ | 宣言 | 補足 |
|---|---|---|
| `script.api` | `CreateScript`, `RunOptions`, `TickReport`, `ScriptRun` | 入口。`ScriptRun` のコンストラクターは `internal` のまま。 |
| `script.compiler` | `CompileResult`, `CompileOptions` | `CompileOptions.DEFAULT`・`EMBEDDED`・`STANDALONE` とコンストラクターの引数。既定値は内部 API の上限のオブジェクトを参照するが、値そのものは `CompileOptions` の側に書いてある。 |
| `script.module` | `ModuleResolver`, `ModuleLookup`, `ModuleAccess`, `ScopedModuleResolver`, `ScopedModuleLookup`, `ModuleDiagnostic`, `ModuleCompileResult` | |
| `script.diagnostic` | `Diagnostic`, `DiagnosticCode`, `DiagnosticPhase`, `DiagnosticSeverity`, `DiagnosticArgument`, `SourcePosition` | |
| `script.vm` | `CompiledProgram`（型だけ）, `VmState`, `ProgramFingerprint` | `CompiledProgram` は中身を見せないハンドル（不透明な参照）として扱う。コンストラクター・`copy`・プロパティ（`instructions`・`constants`・`functions`・`handlers` など）は内部 API。 |
| `script.vm`（保存状態） | `VmSnapshot`, `ObjectSnapshot`, `ListSnapshot`, `CallFrameSnapshot`, `Value`, `SnapshotVerifier.SlotType` | 実行を保存するホストは、これらを項目ごとに読み取り、作り直す（CreateAnalytical もそうしている）。データの形として安定 API。Q2 でそう決めた（「決定事項」を参照）。 |

### 内部 API（オプトインが必要）

| パッケージ | 宣言 | 使う側・理由 |
|---|---|---|
| `script.ast` | 43 個の宣言すべて（`Program`、`Statement`・`Expression` とそのサブクラス、`RangeKind`、演算子の列挙型、`ImportDeclaration`、`LambdaExpression`、`LambdaParameter`、`FunctionReference`、`PipeExpression`、`FallbackExpression`、`FUNCTION_TYPE_NAME`） | cli（`ExpressionStatement`。対話モード）、`v0.1.0-alpha`。構文木は言語の段階ごとに変わる。新しいノードは、`Program` や sealed インターフェースから見えるため `internal` にできない。 |
| `script.lexer` | `Lexer`, `LexResult`, `Token`, `TokenType`, `LexerLimits`, `HARD_KEYWORDS` | cli（対話モード、行編集）、`v0.1.0-alpha` |
| `script.parser` | `Parser`, `ParseResult`, `ParserLimits` | cli、`v0.1.0-alpha` |
| `script.semantic` | 16 個の宣言すべて（`SemanticAnalyzer`、`ScriptType`、`BuiltinFunctions` など。`CollectionOperation`・`InlineFunction`・`CollectionCall` を含む） | cli のテスト（`BuiltinFunctions`）、`v0.1.0-alpha`。新しい 3 つは、ほかの宣言の公開メンバーから見える。 |
| `script.compiler` | `Compiler` | `v0.1.0-alpha`。組み込む側は `CreateScript.compile` を使うこと |
| `script.module` | `ModuleLimits` | `CompileOptions` を通して読む |
| `script.diagnostic` | `DiagnosticLimits`, `DiagnosticCollector`, `truncateUtf16` | `v0.1.0-alpha` |
| `script.scheduler` | `Scheduler`, `PowerLevel`, `ExecutionCondition`, `TickResult` | `v0.1.0-alpha`。ゲームのホストのスケジューラー。`RunOptions.gcBudgetPerTick` の既定値は `Scheduler.GC_BUDGET_PER_VM`（値の 4096 は `RunOptions` に書いてある）。 |
| `script.vm` | `CsVm`, `Console`, `Opcode`, `Instruction`, `Constant`, `HostFunction`, `FunctionInfo`, `ClassInfo`, `FieldInfo`, `ListShape`, `ErrorHandler`, `VmLimits`, `NumericKind`, `MathFunction`, `LinalgFunction`, `MathResult`, `SnapshotVerifier`（`SlotType` を除く）, `SnapshotProblem` | cli（`Opcode`・`Constant`・`HostFunction`・`SnapshotVerifier`）、`v0.1.0-alpha`。`ErrorHandler` は `CompiledProgram.handlers` から見える。 |

### モジュール内専用

ありません。ほかのモジュールが使わない core の公開宣言は、どれも `v0.1.0-alpha` にあるか、公開された宣言から見えます
（例えば `ErrorHandler` は `CompiledProgram.handlers` から、段階 9 の AST のノードは `Program` から）。そのため `internal` にすると、
コンパイルエラーかバイナリ互換性の破壊になります。その後 A2 でメンバー（クラスの中の関数とプロパティ）を調べ、core の 20 個のメンバーを `internal` にしました（「A2 での反映」を参照）。

## `createscript-project`

このモジュールは `v0.1.0-alpha` にはありませんでした。そのため、ここにはまだ、バイナリ互換性を保つ義務のあるものはありません。

### 安定 API

| パッケージ | 宣言 | 補足 |
|---|---|---|
| `project` | `Manifests`, `Manifest`, `ManifestResult`, `Dependency`, `PackageKind` | `Manifests.parse` / `write` と定数（`FILE_NAME`、スキーマの版、大きさの上限）。位置の項目については Q3 を参照。 |
| `project` | `Lockfiles`, `Lockfile`, `LockfileResult`, `LockRoot`, `LockedPackage` | `Lockfiles.parse` / `write` / `check`。 |
| `project` | `ProjectDiagnostic`, `ProjectDiagnosticCode` | |
| `project` | `ProjectRules` | 名前・版・ライセンス・取得元・SHA-256 の値・パスの正しさの検査と、その上限。 |
| `project` | `FilePosition` | A2 で追加（Q3）。`ManifestResult` と `LockfileResult` の位置。 |

### 内部 API（オプトインが必要）

| パッケージ | 宣言 | 使う側・理由 |
|---|---|---|
| `project` | `JsonValue`, `JsonObject`, `JsonArray`, `JsonString`, `JsonInteger`, `JsonBoolean`, `JsonNull`, `JsonParser`, `JsonResult`, `JsonWriter`, `CanonicalJson` | install、registry-tool。これらの形式のための厳密な JSON の読み取り器で、汎用の JSON ライブラリではない。 |
| `project` | `ProjectDiagnostics` | `add(code, at: JsonValue?, …)` が内部 API の型を受け取る。 |
| `project` | `PackageArchives`（`Archive`・`ArchiveResult`・`ArchiveDiagnostic` を含む） | cli、install、registry-tool。規則が固まるまで内部 API（Q4）。後で安定 API にする候補。 |
| `project.registry` | 24 個の宣言すべて（`IndexVerifier`、`IndexSignatures`、`RegistryIndex`、`RegistryKeys`、`TrustedKey`、`RegistryRules`、`RegistryConfig` など） | install、registry-tool。署名付きのレジストリはまだ立ち上げておらず（NO-GO）、形式が変わる可能性がある。レジストリを立ち上げたら安定 API にする。 |

### モジュール内専用

トップレベルにはありません（候補はどれも install か registry-tool が使っています）。A2 で project の 37 個のメンバーを `internal` にしました（「A2 での反映」を参照）。

## 決定事項（A1 のレビュー、2026-11-15）

- **Q1.** `WARNING`：オプトインせずに内部 API を使うと警告になり、コンパイルエラーには決してなりません。そのため、
  `v0.1.0-alpha` に対して書いたコードはそのままコンパイルできます。
- **Q2.** `VmSnapshot`・`ObjectSnapshot`・`ListSnapshot`・`CallFrameSnapshot`・`Value`・`SnapshotVerifier.SlotType` は安定 API です。
  以前のリリースの保存状態も復元できなければならず、互換性のテストを優先します（「A2 での反映」を参照）。
- **Q3.** `ManifestResult` と `LockfileResult` では、`Pair<Int, Int>` の代わりに `FilePosition(line, column)` を使います。
- **Q4.** `PackageArchives` は、当面は内部 API のままです。規則が固まったら安定 API にできます。
- **Q5.** コンパイラーで確かめた結果：`SnapshotVerifier` に印を付けると、`SnapshotVerifier.SlotType` にもオプトインが必要になります。
  そのため、オブジェクトそのものには印を付けず、`SlotType` 以外のメンバーに 1 つずつ印を付けます。
- **Q6.** CreateAnalytical は変えません。CreateAnalytical は Core の自分用の写しをビルドしています。後でこの Core を使ってビルドするようになったら、オプトインします。

## 方法

棚卸しには、リポジトリの外の小さなスクリプトを使いました。`createscript-core/src/main` と `createscript-project/src/main` の、
トップレベルの `class`・`interface`・`object`・`fun`・`val`・`typealias` の宣言を書き出します。それぞれについて、ほかのモジュールの
`src/main` か `src/test` がその名前を（パッケージ付きで）参照しているかと、`v0.1.0-alpha` のソース（`git archive v0.1.0-alpha`）に
同じ名前の公開宣言があるかを記録しました。使われているかの判定は名前の一致で行うので、多めに数えることがあります。
メンバーの単位の分類は、A2 でコンパイラーを使って行いました。

## A2 での反映

コンパイラーと、ビルドした jar に対する `javap` で確かめました（2026-11-15、`b80f73d` の上で）。

- `createscript-core` と `createscript-project` で `explicitApi()` を有効にしました。公開する宣言には、すべて `public`
  （または `internal`）と型を書いています。
- 注釈 `com.creatrbypc.createanalytical.script.InternalCreateScriptApi` と
  `com.creatrbypc.createanalytical.project.InternalCreateScriptProjectApi`（`@RequiresOptIn`、水準は `WARNING`）を、
  上の表の内部 API のトップレベルの宣言 132 個（core 95、project 37）、`SlotType` 以外の `SnapshotVerifier` のメンバー（Q5）、
  `CompiledProgram` のコンストラクター・プロパティ・互換用の隠したコンストラクターと `copy` に付けました。2 つのモジュールは、
  自分自身の中（テストを含む）でオプトインしています。CLI は両方に、install と registry-tool は project の方にオプトインしています。
- 57 個のメンバー（core 20、project 37）を `internal` にしました。ほかのモジュールが使っておらず（cli・install・registry-tool の
  main とテストのコンパイル済みクラスの参照から調べた）、`v0.1.0-alpha` でも公開されていなかった、内部 API の型のメンバーです。
  定数（`const val`）は使う側のコードに値が写されるので、ソースで調べました。インターフェースのメンバーは `internal` にできないので、公開のままです。
- `ManifestResult.entryPosition`・`dependencyPositions`・`modulePositions` と `LockfileResult.rootPosition`・`packagePositions` では、
  `Pair<Int, Int>` の代わりに `FilePosition` を使います。`createscript-project` は `v0.1.0-alpha` に無かったので、リリース済みのコードで
  これらを使うものはありません。CLI とテスト 1 つを直しました。
- JVM のシグネチャ：`v0.1.0-alpha` の Core の公開メンバーは、すべて残っています（A2 の前と同じ 11 行が `javap -public` に出ません。
  `Program` と `CompiledProgram` の互換用の隠したメンバー（`javap -p` では見える）と、Kotlin の合成コンストラクターです）。
  それ以外の変化は、上の位置の型、`internal` にしたメンバー（JVM での名前が `$createscript_core` か `$createscript_project` で終わるようになった）、
  2 つの注釈のクラス、`FilePosition` です。
- 外から使った場合（オプトインしない一時的なモジュールでコンパイル）：安定 API だけを使うファイル（README の例、`VmSnapshot` を項目ごとに作り直すコード、
  `SlotType` に対する `when`、プロジェクトのファイルの API）は、警告なしでコンパイルできます。内部 API を使う 10 か所（`Compiler`、`Opcode`、`VmLimits`、
  `CompiledProgram.instructions`、`copy()`、`component1()`、`SnapshotVerifier.maxSteps`、`PackageArchives.FILE_NAME`、`JsonParser`、
  `CompiledProgram` のコンストラクター）は、どれも警告が出たうえでコンパイルできます。
- 保存状態（Q2）：`BinaryCompatibilityTest.snapshotHostCompiledAgainstV010AlphaStillSavesAndRestores` は、`v0.1.0-alpha` の Core に対して
  コンパイルしたホスト（`compat/v010alpha/OldSnapshotHost`）を動かします。4・5・7・11 tick ごとに保存し、`v0.1.0-alpha` のコンストラクターで
  状態を作り直して再開し、中断なしの実行と同じ出力を得ます。tick をまたぐ命令（`vector(...)` などの線形代数の演算）は、途中までの状態では
  保存されず、復元するとやり直します。命令 1 つごとに復元するホストは、その命令の先へ進めません。これは既存の挙動で、A4 で
  `CreateScript.resume` に書きました（「KDoc（A4）」を参照）。

## API の一覧（A3）

`createscript-core/api/createscript-core.api` と `createscript-project/api/createscript-project.api` は、2 つのモジュールの公開 API を、
JVM から見た形で並べた一覧です。各モジュールの `ApiListingTest` が、コンパイル済みのクラスから一覧を作り直し、違えば失敗します。
そのとき、消えた行（`-`）と増えた行（`+`）を示します。一覧を作るコードは共有していて、`test-support/api-listing/.../ApiListing.kt` を
両方のモジュールのテストのソースに加えています。使うのは JDK だけです（`java.util.spi.ToolProvider` を通した `javap -v -p`。
tool provider が無いときは `$JAVA_HOME/bin/javap`）。

API を意図して変えるときは、変更した後に次を実行します。

```
CREATESCRIPT_API_UPDATE=1 ./gradlew :createscript-core:test --tests '*ApiListingTest*' :createscript-project:test --tests '*ApiListingTest*'
```

（`--tests` は直前のタスクにだけ効きます）。そして、変更と一緒に `.api` ファイルの差分を確認してください。ファイルを書き直すのは、
`CREATESCRIPT_API_UPDATE` が `1` のときだけです。それ以外のときに一覧が無いか違えば、テストの失敗になります。一覧は JDK 21 で作ります
（ほかの版の JDK ではテストが失敗します）。一覧は macOS と Linux で同じになります。

> [!IMPORTANT]
> 一覧の差分は「API が変わった」という合図です。レビューで差分を見て、意図した変更かどうかを確かめてください。

一覧に載るもの：

- 外側のクラスも公開されている公開クラス（合成のものを除く）と、その public と protected のフィールド・コンストラクター・メソッドのうち
  合成でないもの。`javap` が出力する形で、並べ替えてあります。定数のフィールドは値も示します。使う側のコードが値を写すためです。
- `//` の後ろの印：宣言にオプトインが必要なら `opt-in`（宣言そのもの、それを囲むクラス、getter ならそのプロパティに
  `@InternalCreateScriptApi` か `@InternalCreateScriptProjectApi` がある）。Kotlin が `@NotNull` か `@Nullable` を付けた結果には
  `returns !` か `returns ?`。引数には `params` の後に 1 つずつ印（`!`・`?`、どちらでもなければ `-`）。そのため、JVM の記述子には現れない
  null 可否の変化も、一覧の変化になります。

Kotlin の `internal` と JVM：`internal` の関数とプロパティのアクセサーは、JVM 上では公開されていて、名前が `$createscript_core` か
`$createscript_project` で終わります。これらは一覧から外します。`internal` のクラス・コンストラクター・定数は、JVM 上ではそのような名前を持たずに公開されていて、
`javap` では公開の宣言と区別できません（区別には Kotlin のメタデータが必要で、JDK では読めません）。そのため、ほかの JVM 上の公開宣言と同じように一覧に載せます。
A2 の後の時点で、2 つのモジュールには `internal` のクラス・オブジェクト・インターフェースが 14 個、`internal` のコンストラクターが 3 個、
`internal` の定数が 24 個あります。これらの変化も一覧に現れます。

一覧に現れないもの：

- 合成のメンバー。`Program` と `CompiledProgram` の互換用の隠したコンストラクターと `copy`（`@Deprecated(level = HIDDEN)`）も含みます。
  これらは `BinaryCompatibilityTest` が引き続き確かめます。
- 引数の既定値など、Kotlin のメタデータにしか無い事柄。
- `CompiledProgram` の `copy` と `componentN` には、一覧で `opt-in` の印が付きません（コンパイラーが印を付けないため）。ただし、
  型がオプトインを必要とするので、使うときにはオプトインが必要です。

## KDoc（A4）

安定 API のすべての宣言に、英語の KDoc があります。数えると 285 個です。公開されたクラス・関数・プロパティ・列挙値と、
主コンストラクターのプロパティ（`@property`）を 1 つずつ数えています。ただし `DiagnosticCode` と `ProjectDiagnosticCode` の列挙値は除き、
型の側で説明しています（ID の扱い方と、それらに対する `when` には `else` が必要なこと）。これらの宣言を説明していた日本語のコメントは
KDoc に置き換えました。実装・内部 API・診断コードのまとまりについてのコメントは残しています。

KDoc には、コードで確かめた、コードが実際にすることだけを書いています。目的、例外（値を確かめるコンストラクターの
`IllegalArgumentException`、`ScriptRun.tick` の実行中にもう一度 `tick` を呼んだ（再入した）ときの `IllegalStateException`、ホストのコールバックの例外はそのまま伝わること）、
上限（既定値と `CompileOptions.STANDALONE`、コンソールの 32 行・64 UTF-16 コード単位、プロジェクトのファイルの上限）、スレッドです。
スレッドについて：Core には共有される変更可能な状態がありません（どの object・companion object にも変更可能なメンバーが無く、
コンパイルのたびに字句解析器・構文解析器・コンパイラーを新しく作ります）。そのため、コンパイルや別々の実行は別々のスレッドで同時に動かせますが、
1 つの `ScriptRun` はスレッドセーフではありません。

`CreateScript.resume` と `ScriptRun.snapshot` には、保存状態の制限を書いています。

- 保存状態は命令と命令の間のものです。何 tick もかかる命令（ベクトルと行列の演算）は、まだ始まっていないものとして保存されるので、
  再開すると最初からやり直します。結果は同じです。1 tick の命令数をとても少なくして毎 tick 保存と再開を繰り返すと、その命令をいつまでも繰り返すことがあります。
- 途中のガベージコレクションは保存しません。再開した実行は、それを最初からやり直します。
- `resume` が確かめるのは状態の形で、どのプログラムから作った状態かは確かめません。ホストは `ProgramFingerprint.ofProgram` と
  ソースの fingerprint を比べます。
- 乱数の状態とコンソールの履歴は、保存状態のものを使います。`onPrint` には、それより後の行だけが届きます。

`CreateScriptTest.resumeStartsAMultiTickInstructionAgain` は、決まった数の tick で 1 つ目の点を確かめます。`vector(3.0, 4.0)` を 1 歩進めたところで
保存した状態は、元の実行よりも 1 tick 多くかかってその命令を終えます。50 tick のあいだ毎 tick 保存と再開をすると、その命令から先へ進みません。
そして、どちらの実行も、保存しない実行と同じ出力をします。

確かめたこと：唯一のコードの例（`CreateScript` の KDoc の中）は、オプトインしない一時的なモジュールでコンパイルして実行しました（警告なし、
`42` を出力）。API の一覧は変わっていません。2 つのモジュールのコンパイル済みのバイトコードは、行番号を除いて A4 の前と同じです（`javap -c -p`）。
引用した `CS_...` の ID は実在します。
