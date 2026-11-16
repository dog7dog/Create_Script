# サンプル

> **原文（英語）**：[docs/samples.md](../samples.md)（コミット `4a2192b` の時点）。内容が食い違うときは英語版が正です。

サンプルのプログラムは [`createscript-cli/samples/`](../../createscript-cli/samples/) にあります。プログラムの中のコメントは日本語です。
`./gradlew :createscript-cli:installDist` でコマンドを作った後、リポジトリのルートで次のように実行します。

```bash
createscript-cli/build/install/createscript/bin/createscript run createscript-cli/samples/hello.cscr
```

| ファイル | 示すこと | 終了コード |
|---|---|---|
| `hello.cscr` | 1 行を出力する | 0 |
| `features.cscr` | 種を固定した乱数、`Vector`、`Matrix`、`isClose` | 0 |
| `many_lines.cscr` | `for (i in 1..40)` で 40 行を出力する（最後の 32 行だけでなく、すべての行が出力される） | 0 |
| `wait.cscr` | `waitTicks(20000)` がすぐに終わる。tick は論理的に数えるため | 0 |
| `type_error.cscr` | コンパイルエラー | 2 |
| `infinite_loop.cscr` | 終わらないプログラム。`--max-ticks` で止める | 3 |

> [!NOTE]
> 終了コードの意味：**0** は正常終了、**2** はコンパイルエラー、**3** は `--max-ticks` の上限で止めたことを表します。
> そのほかの終了コードは、`createscript` コマンドの説明（`docs/ja/cli.md`）を参照してください。

## 出力

```text
$ createscript run createscript-cli/samples/hello.cscr
Hello, CreateScript!

$ createscript run createscript-cli/samples/features.cscr
0.7415648787718233
1
5.0
[0.6, 0.8]
[[5.0, 11.0], [11.0, 25.0]]
true
false

$ createscript run createscript-cli/samples/wait.cscr
start
after 20000 ticks

$ createscript run createscript-cli/samples/type_error.cscr
createscript-cli/samples/type_error.cscr:1:14: error CS_SEM_TYPE_MISMATCH ("Int", "String")

$ createscript run createscript-cli/samples/infinite_loop.cscr --max-ticks 1000
createscript-cli/samples/infinite_loop.cscr: stopped after 1000 ticks (--max-ticks); the program did not finish
```

> [!TIP]
> `type_error.cscr` の出力の `1:14` は「1 行目の 14 列目」です。`CS_SEM_TYPE_MISMATCH` は型が合わないことを表す診断コードで、
> 括弧の中は「期待した型」と「実際の型」です。

それぞれの出力つきの例は、ほかにも [language.md](language.md) にあります。
