# CreateScript の Core を組み込む

> **原文（英語）**：[docs/api/embedding.md](../../api/embedding.md)（コミット `4a2192b` の時点）。内容が食い違うときは英語版が正です。

この案内では、ホストのアプリケーションが `createscript-core` を使って CreateScript のプログラムをコンパイルし、実行する方法を説明します。
詳しい仕様は、安定 API の KDoc（一覧は [api-surface.md](api-surface.md)）が正です。API を変えるときの規則は
[compatibility.md](compatibility.md) にあります。

> [!NOTE]
> **ホスト** とは、CreateScript のプログラムを動かす側のアプリケーション（ゲームの MOD、CLI など）のことです。
> Core はファイルもネットワークも読まないので、プログラムやモジュールのソースは、すべてホストが渡します。

## ライブラリの入手

Core は、まだ Maven のリポジトリに公開していません。このリポジトリから JDK 21 でビルドしてください。

```bash
./gradlew :createscript-core:jar
```

できた `createscript-core/build/libs/createscript-core.jar` を使います。依存するのは Kotlin の標準ライブラリ 2.0.0
（と、それが使う JetBrains の annotations）だけです。パッケージ名は `com.creatrbypc.createanalytical.script` で始まります
（CreateScript を作り始めたプロジェクトの名前に由来します）。

## コンパイルと実行

```kotlin
import com.creatrbypc.createanalytical.script.api.CreateScript
import com.creatrbypc.createanalytical.script.api.RunOptions

fun runOnce() {
    val result = CreateScript.compile("println(6 * 7)")
    val program = result.program ?: error(result.diagnostics)
    val run = CreateScript.start(program, RunOptions(instructionsPerTick = 128, randomSeed = 0)) { line -> println(line) }
    while (!run.isFinished) run.tick()
}
```

- `CreateScript.compile` は、プログラムに問題があっても **例外を投げません**。問題は `result.diagnostics` に入ります。
  それぞれに安定したコード（`diagnostic.code.id`。例えば `CS_SEM_TYPE_MISMATCH`）と位置があります。エラーがあると、
  `result.program` は `null` です。
- 実行（run）は、`tick()` を呼ぶまで何もしません。1 回の tick で実行するのは、最大 `instructionsPerTick` 個の命令と、
  決まった量までのガベージコレクション（GC）です。こうして、スクリプトが 1 回にどれだけ仕事をしてよいかを、ホストが決めます。
- `onPrint` は、プログラムが出力したすべての行を、tick の後に受け取ります。`run.console` は最後の 32 行を持ちます。
- `run.state`・`run.error`・`run.isFinished` で、プログラムが待機中か、終わったか、実行時エラー（`CS_RUN_...`）で止まったかが分かります。

<!-- ja-only -->
| 知りたいこと | 使うもの |
|---|---|
| コンパイルの成否 | `result.program`（`null` ならエラー）・`result.diagnostics` |
| 実行の状態 | `run.state`・`run.isFinished` |
| 出力 | `onPrint`（すべての行）・`run.console`（最後の 32 行） |
| 止まった理由 | `run.error`（実行時エラーの診断） |

## 上限

`CreateScript.compile(source)` は `CompileOptions.DEFAULT` を使います。信頼できないスクリプトをたくさん動かすホスト向けの、
小さな上限です（ソースは 4096 UTF-16 コード単位、トークンと構文木のノードは 2048 個、命令は 4096 個）。
`CompileOptions.STANDALONE` は `createscript` コマンドが使う設定です（1 ファイル 1,000,000 コード単位、命令数の上限なし、
モジュール 1024 個）。設定が決めるのは、どのプログラムを受け付けるかだけです。実行時の上限（スタック、呼び出しの深さ、
オブジェクト、リストの要素）は、どの設定でも同じです。スクリプトは、ファイル・ネットワーク・OS・JVM には触れられません。

> [!IMPORTANT]
> `CompileOptions` が決めるのは、どのプログラムを受け付けるかだけです。上限を広げても、実行時の上限（スタックやオブジェクトなど）は緩みません。

## モジュール

Core はファイルを読みません。`import` のあるプログラムでは、モジュールのソースをホストが `ModuleResolver` で渡します。

```kotlin
import com.creatrbypc.createanalytical.script.api.CreateScript
import com.creatrbypc.createanalytical.script.module.ModuleLookup

fun compileWithModules() {
    val modules = mapOf("geometry" to "fun square(x: Int): Int {\n  return x * x\n}\n")
    val result = CreateScript.compile("import geometry\nprintln(geometry.square(5))", { name ->
        modules[name]?.let { ModuleLookup.Found(it) } ?: ModuleLookup.NotFound
    })
    result.diagnostics.forEach { println("${it.module ?: "main"}: ${it.diagnostic.code.id}") }
    val program = result.program ?: error("compile failed")
}
```

- resolver を渡さない `CreateScript.compile(source)` は、`import` のあるプログラムに `CS_MODULE_IMPORT_UNAVAILABLE` を報告します。
- `ModuleAccess` は、どのファイルがどのモジュールを import してよいかを決めます（許されなければ `CS_MODULE_NOT_VISIBLE`）。
- `ScopedModuleResolver` を使う `CreateScript.compileScoped` は、import を書いたファイルごとにモジュールを探し、モジュールを ID で区別します。
  そのため、別々のパッケージにある同じ名前のモジュールを、1 つのプログラムに入れられます。
- モジュールのあるプログラムの実行時エラーは、プログラム全体の中の位置を持ちます。どのファイルかは `result.locate(run.error!!)` で分かります。

## 実行の保存と再開

再起動をまたいで実行を続けたいホストは、`run.snapshot()` で **保存状態** を取り、後で `CreateScript.resume` で続けます。
`VmSnapshot` とその中身は、ただのデータです。ホストは、それを項目ごとに自分の形式で保存します。

```kotlin
import com.creatrbypc.createanalytical.script.api.CreateScript
import com.creatrbypc.createanalytical.script.api.RunOptions
import com.creatrbypc.createanalytical.script.api.ScriptRun
import com.creatrbypc.createanalytical.script.vm.CompiledProgram
import com.creatrbypc.createanalytical.script.vm.ProgramFingerprint
import com.creatrbypc.createanalytical.script.vm.VmSnapshot

class SavedRun(val state: VmSnapshot, val source: ByteArray, val program: ByteArray)

fun save(run: ScriptRun, source: String, program: CompiledProgram): SavedRun? =
    run.snapshot()?.let { SavedRun(it, ProgramFingerprint.ofSource(source), ProgramFingerprint.ofProgram(program)) }

fun resume(saved: SavedRun, source: String, program: CompiledProgram): ScriptRun? {
    // resume が確かめるのは状態の形だけ。どのプログラムの状態かは、ホストが確かめる
    if (!saved.source.contentEquals(ProgramFingerprint.ofSource(source))) return null
    if (!saved.program.contentEquals(ProgramFingerprint.ofProgram(program))) return null
    return CreateScript.resume(program, saved.state, RunOptions(instructionsPerTick = 128, randomSeed = 0))
}
```

- `import` のあるプログラムでは、`ofSource` の代わりに `ModuleCompileResult.sourceFingerprint` を保存してください。
  こうすると、モジュールだけを変えたときも、前の状態を再開しません。
- 状態がプログラムに合わなければ、`resume` は `null` を返します。
- 乱数の状態とコンソールの履歴は、保存状態のものを使います。`onPrint` には、再開した後の行だけが届きます。
- 保存状態は、いつも **命令と命令の間** のものです。何 tick もかかる命令（`vector(...)` などのベクトルと行列の演算）は、
  まだ始まっていないものとして保存され、再開するとその命令を最初からやり直します。結果は同じです。途中の GC も保存しません。
  そのため、1 tick の命令数をとても少なくして、毎 tick 保存と再開を繰り返すと、そのような命令をいつまでもやり直し続けることがあります。
- 以前のリリースで保存した状態も再開できます（[compatibility.md](compatibility.md) を参照）。

> [!WARNING]
> `resume` は「その状態がどのプログラムのものか」を確かめません。上の例のように、ソースとプログラムの fingerprint
> （SHA-256 のハッシュ）を一緒に保存し、両方が一致したときだけ再開してください。

<details>
<summary>保存状態に含まれないもの（まとめ）</summary>

- 何 tick もかかる命令の途中経過（再開すると、その命令を最初からやり直す）
- 途中のガベージコレクション（再開すると、最初からやり直す）
- どのプログラムの状態かという情報（ホストが fingerprint で確かめる）

</details>

## スレッド

Core には、共有される変更可能な状態がありません。コンパイルや、別々の実行は、別々のスレッドで同時に動かせます。
1 つの `ScriptRun` はスレッドセーフではありません。1 度に 1 つのスレッドから使ってください。`tick()` を、その実行自身の
`onPrint` の中から呼んではいけません。

## 内部 API

一部の公開クラス（字句解析器・構文解析器・コンパイラー・VM・バイトコードと AST の型、`CompiledProgram` の中身）は、
このリポジトリのほかのモジュールが使うために公開しているだけです。これらには `@InternalCreateScriptApi` の印があり、
オプトインしないで使うと警告が出ます。どのリリースでも変わることがあります。[api-surface.md](api-surface.md) を参照してください。
