# CreateScript 言語（基本）

> **原文（英語）**：[docs/language.md](../language.md)（コミット `4a2192b` の時点の英語版に、その後の段階 R4-A の修正を反映）。内容が食い違うときは英語版が正です。

CreateScript は、Kotlin に似た書き方の、静的型付けの小さな言語です。プログラムはバイトコードにコンパイルされ、論理的な **tick** ずつ進む、
安全に区切られた仮想機械（サンドボックス）の上で動きます。このリポジトリには、言語そのもの（`createscript-core`）と、
コマンドラインのツール（`createscript-cli`）があります。

このページでは、基本的な機能を説明します。下の例はどれも `createscript run` で実際に実行したもので、本当の出力を載せています。
ソースファイルの拡張子は `.cscr`、文字コードは UTF-8 です。

> [!TIP]
> 各節は「コード → その出力 → 説明」の順に並んでいます。まずコードと出力を見比べ、気になったら説明を読むと分かりやすいです。

## 値と変数

```kotlin
val answer = 42           // Int（32 ビット）
var name = "CreateScript" // String
val ready = true          // Boolean
val half = 0.5            // Double
val small = 1.5f          // Float
var count: Int = 0
count = count + 1
println(answer)
println(name)
println(!ready)
println(half * 3)
println(small + 1)
println(count)
println(7 / 2)
println(7 % 3)
println(7.0 / 2)
println(answer.toDouble() / 8)
```

```text
42
CreateScript
false
1.5
2.5
1
3
1
3.5
5.25
```

- `val` は読み取り専用の変数を、`var` はもう一度代入できる変数を宣言します。型は書くこともでき（`var count: Int = 0`）、
  初期値から推論させることもできます。
- 型：`Int`（32 ビットの符号付き整数）、`Float`、`Double`、`Boolean`、`String`、`List<T>`、`MutableList<T>`、`Vector`、
  `Matrix`、利用者が定義したクラス。
- `Int` / `Float` / `Double` を混ぜた計算は、Kotlin と同じように動きます。明示的な変換は `x.toInt()`・`x.toFloat()`・`x.toDouble()` です。
- `Int` のあふれ（オーバーフロー）は実行時エラー（`CS_RUN_INTEGER_OVERFLOW`）です。黙って一周することはありません。
- 文字列は `println` で出力します。`+` による文字列の連結はありません。文字列リテラルのエスケープは
  `\"`・`\\`・`\$`・`\'` です。
- コメントは `//` で始めます。ブロックコメント（`/* */`）と文字リテラル（`'a'`）には対応していません。

> [!NOTE]
> `7 / 2` が `3` になるのは、`Int` どうしの割り算は整数の割り算だからです。小数の結果が欲しいときは `7.0 / 2` のように `Double` を使います。

## 制御構文

```kotlin
var total = 0
for (i in 0 until 10) {
  if (i % 2 == 0) {
    continue
  }
  total = total + i
}
println(total)
var n = 10
while (n > 0) {
  n = n - 3
  if (n < 4) { break }
}
println(n)
if (total > 20 && n != 0) {
  println("big")
} else {
  println("small")
}
```

```text
25
1
big
```

`if` / `else`、`while`、`for (i in a until b)` と `for (i in a..b)`（整数の範囲。`until` は `b` を含まず、`..` は含みます）、
`break`、`continue`、そして演算子 `&&`・`||`・`!`・`==`・`!=`・`<`・`<=`・`>`・`>=` が使えます。

<!-- ja-only -->
| 書き方 | 範囲 | 例 |
|---|---|---|
| `a until b` | `a` 以上 `b` 未満 | `0 until 3` → 0, 1, 2 |
| `a..b` | `a` 以上 `b` 以下 | `1..3` → 1, 2, 3 |

## 関数

```kotlin
fun square(x: Int): Int {
  return x * x
}
fun fib(n: Int): Int {
  if (n < 2) { return n }
  return fib(n - 1) + fib(n - 2)
}
fun greet(who: String) {
  println(who)
}
println(square(7))
println(fib(10))
greet("there")
```

```text
49
55
there
```

関数は、定義より前で呼べます。再帰もできます（下の呼び出しの深さの上限まで）。引数と戻り値には、ほかの型も使えます。
例えば `Double`、`List<Int>`、クラスです。

## モジュール（ローカルの `import`）

プログラムは、同じプロジェクトのほかの `.cscr` ファイルの関数を使えます。この機能は 0.2.0-alpha で加えたもので、v0.1.0-alpha には入っていません。
モジュールは、プロジェクトのファイルか、`createscript install` でインストールしたパッケージのモジュールです
（[パッケージのモジュール](#パッケージのモジュール) を参照）。

```text
my-project/
  main.cscr
  geometry.cscr
```

`geometry.cscr`：

```kotlin
fun square(x: Int): Int {
  return x * x
}
```

`main.cscr`：

```kotlin
import geometry

println(geometry.square(5))
```

```text
$ createscript run my-project/main.cscr
25
```

規則：

- `import name` は、プロジェクトのルートの直下にある `name.cscr` のファイルから、モジュール `name` を読み込みます。
  `createscript` コマンドでは、プロジェクトのルートは実行するファイルのディレクトリか、`--project-root <dir>` で指定したディレクトリです
  （[cli.md](cli.md) を参照）。名前は識別子 1 つです。ファイル名は大文字・小文字も含めて完全に一致しなければなりません。
  Windows で予約された名前（`con`、`prn`、`aux`、`nul`、`com0`–`com9`、`lpt0`–`lpt9`。大文字・小文字を問わない）は、
  `createscript` コマンドでは import できません（`CS_MODULE_UNREADABLE`。[packages.md](packages.md#windows-で予約された名前) を参照）。
- `import` の行は、ファイルの最初に、ほかのどの文や宣言よりも前に書きます。
- モジュールの関数は、モジュール名を付けて呼びます：`geometry.square(5)`。import した関数は自分の名前の中には加わらないので、
  自分の `square` と `geometry.square` は別の関数です。
- 引数と戻り値は、自分の関数と同じように、ファイルをまたいで型を検査します。
- モジュールのファイルに書けるのは、`import` の行と `fun` の宣言だけです。モジュールの中では、モジュール名を付けない呼び出し（`helper()`）は
  同じモジュールの関数を呼び、`other.f()` はこのモジュールが import したモジュールを呼びます。モジュールからは、それを import したファイルの
  変数・関数・クラスは見えません。
- 各モジュールは、複数のファイルが import しても 1 回だけ読み込みます。同じファイルで同じモジュールを 2 回 import しても、警告になるだけです。
- すべてのファイルを合わせて 1 つのプログラムになります。そのため、下の上限（定数、変数、呼び出しの深さ、…）はプログラム全体に適用され、
  ソースの長さとトークンの上限はファイルごとに適用されます。import できるモジュールは、埋め込み向けの上限では 16 個まで、
  `createscript` コマンドでは 1024 個までです（[上限と安全性](#上限と安全性) を参照）。
- `import` は予約語ではありません。`import` という名前の変数も使えます。

まだ対応していないもの：`import a.b`、`import a.*`、`import a as b`、パス（`import "../a"`）、モジュールの中のクラス・トップレベルの文・変数、
モジュール名を値として使うこと（`println(geometry)`）、対話モードでの import、Java/Kotlin のクラスの import。import はプログラムを
コンパイルするときに解決します。実行中に動的に import することはありません。

エラー（ファイル・行・列と一緒に表示します）：

| コード | 例 |
|---|---|
| `CS_MODULE_NOT_FOUND` | プロジェクトのルートに `geometry.cscr` が無いのに `import geometry` |
| `CS_MODULE_CYCLE` | `a.cscr` が `b` を、`b.cscr` が `a` を import している（引数は `a -> b -> a`） |
| `CS_MODULE_AMBIGUOUS` | `Geometry.cscr` と `geometry.cscr` の両方がある |
| `CS_MODULE_OUTSIDE_PROJECT` | `geometry.cscr` がプロジェクトのルートの外のファイルへのシンボリックリンク |
| `CS_MODULE_UNREADABLE` | モジュールのファイルが正しい UTF-8 でない、大きすぎる、ディレクトリ、壊れたリンク |
| `CS_MODULE_TOO_MANY` | モジュールが 16 個（`createscript` コマンドでは 1024 個）より多い |
| `CS_MODULE_PROGRAM_TOO_LARGE` | すべてのファイルを合わせて 8,000,000 文字か 1,000,000 トークンより多い（`createscript` コマンド） |
| `CS_MODULE_BUILTIN_NAME` | `import println` |
| `CS_MODULE_TOP_LEVEL_STATEMENT` / `CS_MODULE_CLASS_UNSUPPORTED` | トップレベルに `println(1)` や `class Point` のあるモジュール |
| `CS_MODULE_IMPORT_UNAVAILABLE` | モジュールを読み込めない場所での `import`（対話モード、`CreateScript.compile(source)`） |
| `CS_MODULE_NOT_VISIBLE` | ホストが許さない `import`（パッケージの非公開のモジュールや推移的な依存のモジュール。[パッケージのモジュール](#パッケージのモジュール) を参照） |
| `CS_PARSE_INVALID_IMPORT` | `import geometry.square`、`import geometry as g`、`import "../geometry"` |
| `CS_PARSE_MISPLACED_IMPORT` | ほかの文の後や、ブロックの中の `import` |
| `CS_PARSE_DUPLICATE_IMPORT`（警告） | 1 つのファイルに同じ `import` が 2 回 |
| `CS_PARSE_IMPORT_NAME_CONFLICT` | 同じファイルに `import geometry` と `val geometry = 1` |
| `CS_PARSE_INVALID_MODULE_REFERENCE` | `println(geometry)`、`geometry.PI` |

呼び出しの型の誤りには、いつものコードを使います。例えば `geometry.square("5")` は `CS_SEM_ARGUMENT_TYPE_MISMATCH` に、
`geometry.cube(2)` は `CS_SEM_UNKNOWN_FUNCTION` になります。

### パッケージのモジュール

0.2.0-alpha で加えました（S8.2 Phase 4。v0.1.0-alpha には入っていません）。プロジェクトを `createscript run [directory]` で実行するとき
（[cli.md](cli.md#プロジェクトを実行する) を参照）、`import name` は、上と同じようにエントリーのファイルのディレクトリで `name` を探すか、
`createscript.lock` に載っていて `.createscript/packages/` にインストールされたパッケージの中で探します
（[パッケージのインストール](packages.md#パッケージのインストールs82-phase-3) を参照）。

- パッケージの **公開モジュール** は、その `modules` の一覧にあるものです。`modules/` にあるほかのファイルは **非公開** です。
- プロジェクト（エントリーのファイルとローカルのモジュール）は、**直接の** 依存、つまり自分の `dependencies` にあるパッケージの公開モジュールを import できます。
- パッケージは、自分のモジュール（公開・非公開とも）と、自分の直接の依存の公開モジュールを import できます。プロジェクトのローカルのモジュールは import できません。
- 依存の依存にすぎないパッケージ（推移的な依存）は、直接 import できません。使うには、それを `dependencies` に加えてください。
- 各ファイルには自分の **スコープ** があります。プロジェクトなら、ローカルのモジュールと直接の依存の公開モジュール。パッケージなら、
  自分のモジュールと直接の依存の公開モジュールです。`import` は、それを書いたファイルのスコープの中だけで探します。そのため、別々のパッケージにある
  同じ名前のモジュール（例えば 2 つのパッケージそれぞれの非公開の `util`）や、ローカルのモジュールとパッケージの非公開のモジュールを、
  1 つのプログラムで使えます。エラー（`CS_MODULE_AMBIGUOUS`）になるのは、1 つのスコープに同じ名前が 2 つある場合だけです
  （ローカルのファイルと直接の依存の公開モジュール、同じ公開モジュールを持つ 2 つの直接の依存、パッケージ自身のモジュールとその依存の公開モジュール）。
  どちらかが勝手に選ばれることはありません。ローカルのファイルと同じく、大文字・小文字だけが違う名前は同じ名前として数えます。
- エントリーのファイルと同じ名前のパッケージのモジュール（例えば非公開の `main`）は別のモジュールで、import の循環にはなりません。
  循環は、経路にあるモジュールと一緒に報告します。パッケージのモジュールは `package/module` と表します。
- 「非公開」が制限するのは `import` だけです。非公開のモジュールのソースもパッケージのアーカイブに入っていて、パッケージを持っている人なら誰でも読めます。

> [!WARNING]
> 非公開のモジュールは「隠されたもの」ではありません。秘密の情報を入れないでください。

| コード | 例 |
|---|---|
| `CS_MODULE_NOT_VISIBLE` | `import vm_internal`（パッケージの非公開のモジュール）。または、`numbers` が依存の依存にすぎないのに `import numbers` |
| `CS_MODULE_AMBIGUOUS` | `src/vector_math.cscr` があり、直接の依存にも公開モジュール `vector_math` がある |

ホスト向けに、Core には import を制御する方法が 2 つあります。既存の `compile` 関数と `ModuleResolver` は変わらず、すべての import を許します。

- `ScopedModuleResolver`（`resolve(importer, name)`。`createscript run [directory]` が使う）を使う
  `CreateScript.compileScoped(source, resolver, entryModule, options)`。`importer` は `import` を書いたモジュールの ID です
  （エントリーのファイルなら `null`）。結果は `ScopedModuleLookup.Found(id, source)`、`Failed(lookup)`（`ModuleLookup` の失敗：見つからない、
  あいまい、プロジェクトの外、読めない）、`NotVisible`（`CS_MODULE_NOT_VISIBLE`）のどれかです。ID はプログラムの中でモジュールを表します。
  同じ ID のモジュールは 1 つのモジュールで、関数の名前は `id.function` になります。そのため、ID を変えれば、同じ名前のモジュールを一緒に使えます。
  ID は 1〜128 文字の `A-Z a-z 0-9 _ / : @ + -` で、先頭は英字・数字・`_` です（`.` は使えません）。循環は、resolver に問い合わせた後に ID で確かめます。
  エントリーのファイル自身を意味する名前には、ID `entryModule` を返してください。`ModuleCompileResult.modules`・`ModuleDiagnostic.module`・
  `sourceFingerprint` は ID を使います。ID を名前と同じにすれば、結果（命令・診断・fingerprint）は既存の `compile` と同じになります。
- `ModuleAccess`（`allows(importer, module)`。`importer` は `import` を書いたモジュール、エントリーのファイルなら `null`）を使う
  `CreateScript.compile(source, resolver, entryModule, options, access)`。resolver に問い合わせる前に、すべての `import` について確かめます。
  モジュール名は 1 つの名前空間のままです。

## クラス

```kotlin
class Counter {
  var value = 0
  fun add(step: Int): Int {
    value = value + step
    return value
  }
}
val c = Counter()
c.add(2)
println(c.add(3))
println(c.value)
```

```text
5
5
```

クラスは、プロパティ（初期値付きの `val` / `var`）とメソッドを持ちます。`Counter()` でインスタンスを作ります。
使われなくなったオブジェクトは、少しずつ進むガベージコレクターが回収します。

## リスト

```kotlin
val xs = mutableListOf(3, 1, 4)
xs.add(1)
xs[0] = 9
println(xs)
println(xs.size)
xs.removeAt(1)
println(xs)
val grid = listOf(listOf(1, 2), listOf(3, 4))
println(grid[1][0])
```

```text
[9, 1, 4, 1]
4
[9, 4, 1]
3
```

`listOf(...)` は読み取り専用のリストを、`mutableListOf(...)` は `add`・`removeAt`・`clear` と添字による代入ができるリストを作ります。
リストは入れ子にできます。

## リストの関数とラムダ

```kotlin
val readings = listOf(3, 8, 1, 6, 5)
println(readings.where { it > 4 })
println(readings.map { it * 10 })
println(readings.group { it % 2 })
readings.each { println(it) }
println(readings.average())
```

```text
[8, 6, 5]
[30, 80, 10, 60, 50]
[[3, 1, 5], [8, 6]]
3
8
1
6
5
4.6
```

- `where { … }` は、ラムダが `true` を返す要素を残します。`map { … }` は、結果を並べたリストを作ります。
- `group { … }` は、ラムダが返すキーごとにまとめたグループのリスト（`List<List<T>>`）を作ります。並びは、キーが最初に現れた順です。
  キーは `Int`・`String`・`Boolean` です。
- `each { … }` は、すべての要素についてラムダを実行します。`average()` は `Int`・`Float`・`Double` のリストで使え、`Double` で足し合わせ、
  空のリストでは Kotlin と同じく `NaN` を返します。
- これらの関数は `List` と `MutableList` で使え、元のリスト（レシーバー）は変えません。結果は新しい `List` です。

<!-- ja-only -->
| 関数 | すること | 上の例の結果 |
|---|---|---|
| `where` | 条件に合う要素を残す | `[8, 6, 5]` |
| `map` | 各要素を変換する | `[30, 80, 10, 60, 50]` |
| `group` | キーごとにまとめる | `[[3, 1, 5], [8, 6]]` |
| `each` | 各要素に処理をする | 1 行ずつ出力 |
| `average` | 平均を求める | `4.6` |

ラムダは `{ 引数 -> 文 }` の形です。引数が 1 つのラムダは引数を省き、`it` を使えます。最後の式の値が結果です。
引数の型はラムダを使う場所から決まり、結果の型は本体から推論します。呼び出しやメンバー名の後に書いたラムダは、最後の引数として渡されます
（`xs.map { … }` は `xs.map({ … })` と同じ）。ラムダの中では `return` を使えません。`break` と `continue` は、ラムダの中のループにだけ効きます。

ラムダは `val` に入れて呼べます。関数は `::` で参照として渡せます。

```kotlin
fun twice(x: Int): Int {
    return x * 2
}

val square = { x: Int -> x * x }
val isSmall: (Int) -> Boolean = { it < 3 }
val numbers: MutableList<Int> = mutableListOf()
numbers.add(1)
numbers.add(4)
println(numbers.map(square))
println(numbers.map(::twice))
println(numbers.where(isSmall))
println(square(5))
```

```text
[1, 16]
[2, 8]
[1]
25
```

関数の型の注釈が無いとき、`val` に入れるラムダの引数には型が必要です。空の `listOf()` や `mutableListOf()` は、宣言した型、
代入先、戻り値の型から要素の型を決めます。

ラムダは、使う場所で展開されます（Kotlin の `inline` のように）。そのため、プログラムと保存状態は、これまでと同じ種類のバイトコードのままです。
ラムダの値は `val` で、それを宣言した関数（またはトップレベル）の中でしか使えず、リストやプロパティに入れたり、返したり、出力したり、
利用者の関数に渡したりはできません。関数の型を書けるのは、そのような `val` だけです。プロパティの初期化式にはラムダを書けません。

> [!NOTE]
> ラムダの値は「その場で展開される名前付きの部品」と考えると分かりやすいです。ほかの値のように持ち運ぶ（保存する・返す）ことはできません。

## パイプライン `|>`

```kotlin
fun scale(x: Int, k: Int): Int {
    return x * k
}

val result = listOf(3, 8, 1, 6, 5)
    |> where { it > 2 }
    |> map { scale(it, 10) }
println(result)
9.0 |> sqrt |> println
```

```text
[30, 80, 60, 50]
3.0
```

`x |> f` は `f(x)`、`x |> f(a, b)` は `f(x, a, b)` です。名前がリストの関数（`where`・`map`・`group`・`each`・`average`）で、その名前の関数・メソッド・
ラムダの値が無いときは、`xs |> map { … }` は `xs.map { … }` になります。モジュールの関数には括弧が必要です：`xs |> stats.odds()`。
`|>` は `||` より弱く結び付き、左から順にまとまります。`|>` で始まる行は、前の行の続きです。

## エラー時の代替値 `?!`

```kotlin
val values = listOf(4, 0, 2)
println(values.map { 8 / it ?! -1 })
println(values[10] ?! 0)
waitTicks(-1) ?! println("negative wait")
```

```text
[2, -1, 4]
0
negative wait
```

`a ?! b` の値は `a` です。ただし、`a` を評価している間（`a` が呼ぶ関数の中も含む）に実行時エラーが起きたら、`a` の途中までの仕事を捨てて、
結果は `b` になります。両辺は同じ型です。文として使うときは、両辺を `Unit` を返す呼び出しにできます（上の例の `waitTicks(-1) ?! println("negative wait")`）。`?!` はほかのどの演算子よりも弱く結び付き、
右から順にまとまります（`a ?! b ?! c` は `a ?! (b ?! c)`）。エラーより前に起きた副作用（出力した行、代入）は残ります。

`?!` が回復するのは、値から起きるエラーです：整数のあふれ、ゼロでの割り算、負の `waitTicks`、範囲外の添字、そして数学・乱数・ベクトルのエラー
（`CS_RUN_EMPTY_COERCE_RANGE`、`CS_RUN_ROUND_NAN`、`CS_RUN_INVALID_RANDOM_RANGE`、`CS_RUN_INVALID_TOLERANCE`、`CS_RUN_DIMENSION_MISMATCH`、
`CS_RUN_INVALID_DIMENSION`、`CS_RUN_ZERO_VECTOR`、`CS_RUN_RAGGED_MATRIX`）。上限（スタック、呼び出しの深さ、オブジェクト、リストの大きさ）、
正しくないバイトコード、初期化前のプロパティの読み取りは、これまでどおりプログラムを止めます。コンストラクターの中のエラーは、
そのコンストラクターの外では回復しません。`?!` が受け持つコードの範囲は、コンパイルしたプログラムの中に表として入っています。保存して復元した実行も、同じように動きます。

> [!IMPORTANT]
> `?!` は「値の誤り」を受け止めるためのものです。上限を超えたときのような「安全のための停止」は受け止めません。

## 数学・乱数・ベクトル・行列

```kotlin
println(abs(-3))
println(max(2, 5))
println(sqrt(2.0))
println(2.0.pow(10))
println(sin(PI / 2))
println(ln(E))
println(hypot(3.0, 4.0))
println(3.7.roundToInt())
println(15.coerceIn(0, 10))
println(isClose(0.1 + 0.2, 0.3))
randomSeed(7)
println(randomInt(1, 7))
val v = vector(1.0, 2.0, 2.0)
println(length(v))
println(v * 2.0)
println(dot(v, v))
val m = matrix(vector(1.0, 0.0), vector(0.0, 2.0))
println(matmul(m, vector(3.0, 4.0)))
println(m.rowCount)
```

```text
3
5
1.4142135623730951
1024.0
1.0
1.0
5.0
4
10
true
2
3.0
[2.0, 4.0, 4.0]
9.0
[3.0, 8.0]
2
```

- 関数：`abs`、`min`、`max`、`sqrt`、`floor`、`ceil`、`round`、`sin`、`cos`、`tan`、`asin`、`acos`、`atan`、`atan2`、`sinh`、`cosh`、`tanh`、
  `ln`、`log10`、`exp`、`hypot`、`sign`、`isClose`。メソッド：`x.pow(y)`、`x.roundToInt()`、`x.coerceIn(min, max)`、`x.isNaN()`、`x.isInfinite()`。
  定数：`PI`、`E`。結果は Kotlin の `kotlin.math` と同じです。
- 乱数：`random()`、`random(min, max)`、`randomInt(min, max)`（最大値は含まない）、`randomSeed(seed)`。種を決めると、同じ列になります。
- `Vector`（1〜16 個の `Double`）と `Matrix`（8 × 8 まで）。どちらも変更できません：`vector`、`matrix`、`dot`、`length`、`normalize`、`transpose`、
  `matmul`、`m.rowCount`、`m.columnCount`。ベクトルは `+ - * /` と単項の `-` を、行列は `+ - *` を使えます。スカラーは `Double` でなければなりません。

## tick と `waitTicks`

```kotlin
println("tick 0")
waitTicks(20)
println("tick 20")
```

プログラムは、1 tick に決まった数までの命令を実行します（CLI の既定は 128、`--instructions-per-tick` で最大 4096）。この予算は、
プログラムの大きさ（バイトコードの命令の数。下を参照）とは別のものです。ループは、何 tick もかけて、プログラムにある命令よりずっと多くの命令を実行できます。
`waitTicks(n)` は、プログラムを `n` tick のあいだ止めます。CLI は tick を論理的に数え、実際の時間は待ちません。

## 上限と安全性

どのプログラムも決まった上限の中で動くので、スクリプトが際限なくメモリーや時間を使うことはありません。コンパイル時の上限には 2 つの組があり、
ホストが `CompileOptions` で選びます。**埋め込み向け**（`CompileOptions.EMBEDDED`。`CreateScript.compile(source)` の既定で、
Minecraft の MOD の Create: Analytical などのホストが使う）と、**独立版**（`CompileOptions.STANDALONE`。`createscript` コマンドが `run` と
対話モードで使う）です。独立版の上限は 0.2.0-alpha で加えたもので、v0.1.0-alpha は埋め込み向けの上限を使います。

| 上限 | 埋め込み向け | 独立版 |
|---|---|---|
| 1 ファイルのソースの長さ | 4096 文字 | 1,000,000 文字 |
| 1 ファイルのトークン | 2048 | 250,000 |
| 1 ファイルの構文木のノード | 2048 | 250,000 |
| import するモジュール（実行するファイルは数えない） | 16 | 1024 |
| すべてのファイルを合わせたソースの長さ | 69,632 文字（実質的な上限ではない） | 8,000,000 文字 |
| すべてのファイルを合わせたトークン | 34,816（実質的な上限ではない） | 1,000,000 |
| バイトコードの命令（プログラムの大きさ） | 4096 | 固定の上限なし（下を参照） |
| バイトコードの検証の仕事の量 | 追加の上限なし | 100,000,000 ステップ |
| 型の注釈が無く、型が互いに依存するプロパティの連鎖 | 追加の上限なし | 128 |

次の上限は、どちらの設定でも同じです。

| 上限 | 値 |
|---|---|
| ブロックと式の入れ子 | 32 |
| 識別子 / 文字列リテラル / 整数リテラル | 32 文字 / 256 文字 / 10 桁 |
| 定数 | 256 |
| 変数（トップレベル、関数ごとのローカル） | 64 |
| オペランドスタックの深さ | 64 |
| 呼び出しの深さ | 16 |
| 関数ごとの引数 | 16 |
| 同時に生きているオブジェクト | 256 |
| クラスごとのプロパティ | 16 |
| 1 つのリストの要素 / すべてのリストの要素 | 256 / 4096 |
| ベクトルの大きさ / 行列の大きさ | 16 / 8 × 8 |
| `println` の 1 行の長さ | 64 UTF-16 単位（長い行は「…」で切る） |

定数・変数・スタック・呼び出しの深さ・引数・オブジェクト・プロパティ・リストの上限は、仮想機械に属します。バイトコードの検証器と、埋め込み先の
保存状態の形式がこれを前提にしているので、独立版のプログラムでも上げません。そのため、大きな独立版のプログラムでも、異なる定数の値は 256 個まで、
トップレベルの変数は 64 個までです。

### プログラムの大きさ

コンパイルしたプログラムのバイトコードの命令の数は、実行時ではなくコンパイル時の上限です。仮想機械は実行中にこれを確かめません。
tick ごとの予算、`--max-ticks`、スタック、呼び出しの深さ、オブジェクトとリストの上限は、どの設定でも同じように働きます。
一度も呼ばれない関数もコンパイルされ、プログラムの大きさに数えます。

「固定の上限なし」は、コンパイルが際限なく時間やメモリーを使ってよいという意味ではありません。独立版では、プログラムの大きさは上の入力の上限、
主に合計 1,000,000 トークンで抑えられます（見つかった中で最も密なコードは、1 トークンあたり約 2 命令にコンパイルされるので、約 2,000,000 命令）。
コンパイラーは、すべてのファイルの構文木と解析の結果を最後まで持ちます。1 トークンあたり最大で約 330 バイトです。開発用の機械で
`createscript` コマンドを使って測った結果：

| プログラム | 命令 | 時間 | メモリー（常駐） |
|---|---|---|---|
| 1,000,000 文字の 1 ファイル（ほとんどがコメント） | 少し | 0.07 s | 62 MiB |
| 1024 個の小さなモジュール | 数千 | 0.6 s | 290 MiB |
| 1000 行のモジュール 160 個 | 約 640,000 | 0.4 s | 300 MiB |
| トークンの上限に近い 4 ファイル（約 960,000 トークン） | 約 1,890,000 | 0.6 s | 530–590 MiB |

これらは Java のヒープ 512 MiB で実行しました。上限の中でも、もっと大きなプログラムには、より多くのメモリーが要ることがあります。
Java のヒープは `JAVA_OPTS` で設定できます。例：`JAVA_OPTS=-Xmx2g createscript run main.cscr`。

バイトコードの検証は、コンパイルしたすべてのプログラムを確かめます。独立版では、各基本ブロックの始まりの状態だけを持ち、100,000,000 ステップで
`CS_COMP_PROGRAM_TOO_COMPLEX` として止まります。ふつうのプログラムは、1 命令あたり数ステップで済みます。検証ができるだけ遅くなるように作った
合成のプログラムは、約 2,000,000 命令で、約 2.3 s の後に上限に達します。長い呼び出しの連鎖、再帰、import、クラスの構築は、コンパイラーの中で
深い再帰をせずに扱います。

将来、入力の上限を上げる（もっと長いファイル、もっと多くのモジュール、パッケージ）ときは、コンパイルの時間とメモリーの上限も一緒に設計し直さなければなりません。

この言語は、ファイル・ネットワーク・OS・Java/Kotlin のコード・リフレクションに触れられません。`import` では、ホスト（例えば `createscript` コマンド）が
モジュールのファイルを読み、その文字列をコンパイラーに渡します。プログラムが自分でファイルを開くことはできません。
エラーは、`CS_SEM_TYPE_MISMATCH` のような診断コードと、行・列で報告します。
