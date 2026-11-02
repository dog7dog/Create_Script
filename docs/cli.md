# The `createscript` command

```
createscript                         interactive mode (REPL)
createscript run <file.cscr> [options]
createscript --help      (-h)
createscript --version
```

The detailed Japanese manual is [createscript-cli/README.md](../createscript-cli/README.md).

## Running a file

`createscript run` reads one `.cscr` file, compiles it and, if there are no errors, runs it.

- Only file names ending in `.cscr` (lower case) are accepted. The file must be valid UTF-8 and at most
  12288 bytes (the language allows 4096 characters).
- Standard output receives every `println` line in order. Standard error receives the diagnostics, in the
  form `file:line:column: error|warning CODE (arguments)`. Lines and columns count UTF-16 units from 1.
- `waitTicks(n)` counts logical ticks; the CLI does not sleep.

| Option | Meaning | Default | Allowed values |
|---|---|---|---|
| `--seed <integer>` | Initial state of the random number generator (`randomSeed(...)` in the program replaces it) | `0` | any 64-bit signed integer |
| `--instructions-per-tick <integer>` | Instructions run per logical tick | `128` | 1–4096 |
| `--max-ticks <integer>` | Stop with exit code 3 if the program has not finished after this many ticks | `100000` | 1–1000000 |

| Exit code | Meaning |
|---|---|
| 0 | The program finished |
| 1 | Runtime error (`CS_RUN_*`); the output printed before it is kept |
| 2 | Compile error (nothing is run) |
| 3 | `--max-ticks` reached |
| 64 | Usage error (unknown command or option, bad value, not a `.cscr` file) |
| 65 | The file cannot be used (not UTF-8, too large) |
| 66 | The file does not exist, is not a regular file or cannot be read |
| 130 | Interrupted with Ctrl+C while running |

## Interactive mode (REPL)

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

- Enter one statement, declaration or expression at a time. The value of an expression is printed.
- Variables, functions, classes, objects and the random state are kept for later inputs. Earlier inputs are
  not run again: the REPL resumes from a verified snapshot of the virtual machine.
- `{`, `(` and `[` continue the input on `...` lines until they are closed. Write `} else {` on one line.
- `help` shows help. `exit`, `quit` and end of input (Ctrl+D) leave with exit code 0.
- An input with a compile error, a runtime error, or more than 100,000 ticks is not added to the session.
- The whole session is one program, so it is subject to the language limits (for example 4096 characters of
  source). When a limit is reached, the REPL says so and keeps the earlier state.

### Line editing

When both standard input and standard output are a terminal, the REPL reads lines with JLine 3
(3.30.17). Otherwise (pipes, files, `TERM=dumb`) it reads plain lines.

| Key | Action |
|---|---|
| ↑ / ↓ | Previous / next input in the history |
| ← / → | Move the cursor |
| Home / End, Ctrl+A / Ctrl+E | Start / end of the line |
| Backspace / Delete | Delete before / at the cursor |
| Ctrl+C | Discard the current input (including unfinished `...` lines); the REPL continues |
| Ctrl+D | On an empty line, leave the REPL; otherwise delete the character at the cursor |
| Tab | Inserts a tab (there is no completion) |

- The history is kept in memory only; it is never written to a file.
- History expansion (`!!`), bracketed paste and completion are turned off, so input means the same as
  without line editing.
- Ctrl+C while a program is running still ends `createscript` with exit code 130.
- JLine runs the system's `stty`, `tty`, `test` and, when needed, `infocmp` with fixed arguments to set up the
  terminal. CreateScript programs cannot run commands. The JLine native library is not used.

## Safety

The CLI reads only the one file that it is given. A CreateScript program cannot access files, the network,
the operating system or Java/Kotlin code. `--max-ticks` and `--instructions-per-tick` bound the work of every
run, and the language limits bound its memory.

## Packages with a bundled Java runtime

`createscript-cli/packaging/` builds packages that contain a minimal Java 21 runtime made with `jlink`,
so that users do not need Java:

| OS | Package | Installs to |
|---|---|---|
| macOS (arm64 or x86_64) | `createscript-<version>-macos-<arch>-unsigned.pkg` | `/usr/local/lib/createscript`, command `/usr/local/bin/createscript` |
| Linux (x86_64 or aarch64) | `createscript-<version>-linux-<arch>.tar.gz` with `install.sh` | `~/.local/lib/createscript`, command `~/.local/bin/createscript` |

```bash
# macOS (the CPU of the JDK decides the CPU of the package)
JAVA_HOME=/path/to/jdk-21 createscript-cli/packaging/macos/build-pkg.sh

# Linux (run on Linux with a JDK 21 for that CPU; needs GNU tar)
JAVA_HOME=/path/to/jdk-21 createscript-cli/packaging/linux/build-tarball.sh
```

- The macOS package is **not signed or notarized**. It is meant for local tests; Gatekeeper blocks it when it
  is downloaded.
- The runtime is built from the JDK in `JAVA_HOME`. The package records that JDK in `BUILD-INFO.txt`, and
  `legal/JAVA-RUNTIME-SOURCE.txt` says where to get its source code.
- Licenses of the bundled components: `legal/THIRD-PARTY-NOTICES.md` in the package.
