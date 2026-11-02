# Samples

The sample programs are in [`createscript-cli/samples/`](../createscript-cli/samples/). Their comments are in
Japanese. Run them from the repository root after `./gradlew :createscript-cli:installDist`:

```bash
createscript-cli/build/install/createscript/bin/createscript run createscript-cli/samples/hello.cscr
```

| File | What it shows | Exit code |
|---|---|---|
| `hello.cscr` | Prints one line | 0 |
| `features.cscr` | Random numbers with a fixed seed, `Vector`, `Matrix`, `isClose` | 0 |
| `many_lines.cscr` | Prints 40 lines with `for (i in 1..40)` (all lines are written, not only the last 32) | 0 |
| `wait.cscr` | `waitTicks(20000)` finishes at once, because ticks are counted logically | 0 |
| `type_error.cscr` | A compile error | 2 |
| `infinite_loop.cscr` | A program that never ends; stop it with `--max-ticks` | 3 |

## Output

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

More examples, each with its output, are in [language.md](language.md).
