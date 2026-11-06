# The `createscript` command

```
createscript                         interactive mode (REPL)
createscript run <file.cscr> [options]
createscript init [directory] [--name <name>]
createscript manifest check [directory]
createscript lock check [directory]
createscript pack [directory] [--output <file>]
createscript --help      (-h)
createscript --version
```

The detailed Japanese manual is [createscript-cli/README.md](../createscript-cli/README.md).

## Running a file

`createscript run` reads one `.cscr` file, compiles it and, if there are no errors, runs it.

- Only file names ending in `.cscr` (lower case) are accepted. The file must be valid UTF-8 and at most
  3,000,000 bytes (the command allows 1,000,000 characters per file; in development, v0.1.0-alpha allows 4096
  characters and 12288 bytes).
- Standard output receives every `println` line in order. Standard error receives the diagnostics, in the
  form `file:line:column: error|warning CODE (arguments)`. Lines and columns count UTF-16 units from 1.
- `waitTicks(n)` counts logical ticks; the CLI does not sleep.
- The command compiles with the standalone limits: 1,000,000 characters, 250,000 tokens and 250,000 syntax
  tree nodes per file, up to 1024 modules, 8,000,000 characters and 1,000,000 tokens in all files, and no
  fixed limit on the number of instructions (in development; v0.1.0-alpha uses 4096 characters and 4096
  instructions). The runtime limits stay; see [Limits and safety](language.md#limits-and-safety).
- Large programs need memory (about 600 MiB for a program near the limits). Set the Java heap with
  `JAVA_OPTS`, for example `JAVA_OPTS=-Xmx2g`.

| Option | Meaning | Default | Allowed values |
|---|---|---|---|
| `--seed <integer>` | Initial state of the random number generator (`randomSeed(...)` in the program replaces it) | `0` | any 64-bit signed integer |
| `--instructions-per-tick <integer>` | Instructions run per logical tick | `128` | 1–4096 |
| `--max-ticks <integer>` | Stop with exit code 3 if the program has not finished after this many ticks | `100000` | 1–1000000 |
| `--project-root <directory>` | Where `import name` looks for `name.cscr` | the directory of the file | an existing directory that contains the file |

| Exit code | Meaning |
|---|---|
| 0 | The program finished |
| 1 | Runtime error (`CS_RUN_*`); the output printed before it is kept |
| 2 | Compile error (nothing is run) |
| 3 | `--max-ticks` reached |
| 64 | Usage error (unknown command or option, bad value, not a `.cscr` file) |
| 65 | The file cannot be used (not UTF-8, too large) |
| 66 | The file does not exist, is not a regular file or cannot be read; the project root does not exist or is not a directory |
| 130 | Interrupted with Ctrl+C while running |

### Running a project

`createscript run [directory]` (in development, S8.2 Phase 4) runs the `entry` of the project's
`createscript.json`; the directory defaults to the current directory. Use it to import installed packages.

```text
$ createscript install github:owner/vector-math@v1.2.0
$ createscript run
25
```

- Before compiling, the project is checked like `createscript manifest check` and `createscript lock check`, and
  every package in `createscript.lock` must be installed exactly: the files in `.createscript/packages/<name>/<version>/`
  must match their marker (no missing, extra or changed files, no symbolic links), the marker must name the
  archive of the lock file (`sha256`), the canonical archive made again from the files must have that SHA-256 (so a
  change of the files together with the marker is found; see
  [Canonical form](packages.md#canonical-form)), and the package's `createscript.json` must match the lock file. Otherwise
  nothing is compiled or run (exit code 65) and `createscript install` is suggested. An interrupted installation
  also stops it.
- It uses no network and no cache, and writes no files. The sources that were checked are the ones compiled.
- Local modules are found in the directory of the entry file. Which modules can be imported is described in
  [Modules from packages](language.md#modules-from-packages).
- `--seed`, `--instructions-per-tick` and `--max-ticks` work as for a file. `--project-root` cannot be used.
- `createscript run <file>.cscr` is unchanged: it does not read `createscript.json` and does not use packages.
- A library without `entry` cannot be run (exit code 65).

### Files with `import`

A file can import other `.cscr` files of the same project (in development, not in v0.1.0-alpha; see
[Modules in the language guide](language.md#modules-local-import)):

```text
$ ls my-project
geometry.cscr  main.cscr
$ createscript run my-project/main.cscr
25
$ createscript run my-project/apps/tool.cscr --project-root my-project
```

- `import name` reads `name.cscr` directly in the project root and nothing else: no subdirectories, no
  search path, no network. The project root is the directory of the file you run, or `--project-root`.
  With `--project-root`, the file you run must be inside that directory (exit code 64 otherwise).
- The file name must match the module name exactly, including upper and lower case. If several files differ
  only in case (`Geometry.cscr` and `geometry.cscr`), the import is ambiguous (`CS_MODULE_AMBIGUOUS`).
- A module file may be a symbolic link only if it leads to a regular file inside the project root.
  A link that leads outside gives `CS_MODULE_OUTSIDE_PROJECT`; a broken link, a directory, a file that is not
  UTF-8 or larger than 3,000,000 bytes gives `CS_MODULE_UNREADABLE`. Module names cannot contain `/`, `..` or an
  absolute path, because a module name is one identifier.
- Diagnostics inside a module name the module's file, for example
  `my-project/geometry.cscr:2:12: error CS_SEM_TYPE_MISMATCH ("Int", "String")`. Runtime errors inside a
  module function also show the module's file, line and column.
- Errors in resolving imports are compile errors (exit code 2), for example
  `my-project/main.cscr:1:8: error CS_MODULE_NOT_FOUND ("geometry")`.
- All files are compiled into one program and run in one virtual machine, so `--instructions-per-tick`,
  `--max-ticks` and the language limits apply to the whole program.
- A file without `import` is compiled exactly as before.

`createscript install` (in development) downloads and installs packages into `.createscript/packages/`;
`createscript run [directory]` uses them (see [Running a project](#running-a-project)).

## Project files

In development (not in v0.1.0-alpha). See [Project files](packages.md) for the formats.

- `createscript init [directory] [--name <name>]` creates `createscript.json` and `src/main.cscr`, and a
  `.gitignore` with the line `/.createscript/` when there is no `.gitignore` (an existing one is never changed). It
  never overwrites a file and, if it fails halfway, removes only what it created (exit code 73 when it cannot
  create the files).
- `createscript manifest check [directory]` checks `createscript.json` and its entry file.
- `createscript lock check [directory]` checks `createscript.lock` against `createscript.json`.
- `createscript pack [directory] [--output <file>]` makes `createscript-package.zip` from a library project
  (schema version 2). The same files always give the same bytes; an existing file is never overwritten.
- `createscript install [github:<owner>/<repo>@v<version>] [directory] [--offline]` installs the dependencies
  and, with a package, adds it to `createscript.json` (see [Installing packages](packages.md#installing-packages-s82-phase-3)).
- The directory defaults to the current directory. The checks only read files. `createscript run [directory]`
  reads them; `createscript run <file>.cscr` does not.

| Exit code | Meaning |
|---|---|
| 0 | No problems |
| 64 | Usage error |
| 65 | A problem in `createscript.json` or `createscript.lock` (shown as `file:line:column: error CS_PKG_…`) |
| 66 | `createscript.json` or the directory does not exist, or a file cannot be read |
| 69 | `install`: the network is unavailable, or a package is not in the cache with `--offline` |
| 73 | `init` cannot create the files (one exists already, or writing failed); `install` finds files in an unexpected state (symbolic links, changed files, an interrupted installation that cannot be finished or undone) |
| 75 | `install`: another installation is running in the project |

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
- The whole session is one program, so it is subject to the language limits (for example 1,000,000 characters
  of source, 64 variables and 256 constants). When a limit is reached, the REPL says so and keeps the earlier
  state.
- `import` is not available in the interactive mode. An input with `import` gives
  `<input>:1:8: error CS_MODULE_IMPORT_UNAVAILABLE ("geometry")` and is not added to the session.

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

The CLI reads the file that it is given and, for `import`, only `name.cscr` files directly in the project
root (after checking that they stay inside it). A CreateScript program cannot access files, the network,
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

- Verified in CI: the Linux package on x86_64 and aarch64, and the macOS package on arm64 (installed, run and
  uninstalled). The macOS x86_64 package has not been verified. See the platform status in the README.
- The macOS package is **not signed or notarized**. It is meant for local tests; Gatekeeper blocks it when it
  is downloaded.
- The runtime is built from the JDK in `JAVA_HOME`. The package records that JDK in `BUILD-INFO.txt`, and
  `legal/JAVA-RUNTIME-SOURCE.txt` says where to get its source code.
- Licenses of the bundled components: `legal/THIRD-PARTY-NOTICES.md` in the package.
