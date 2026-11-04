# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/). Before 1.0, any release may change the language or the APIs.

## [Unreleased]

### Added

- Local modules: `import name` at the top of a file uses the functions of `name.cscr` as `name.f(...)`,
  with type checks across files, one resolution per module, and diagnostics for missing, ambiguous,
  cyclic and out-of-project modules. Modules contain only `import` lines and functions.
- Core: `CreateScript.compile(source, resolver, entryModule)` with the `ModuleResolver` interface; the host
  gives the module sources, and the Core does not read files. `CreateScript.compile(source)` is unchanged
  and reports `CS_MODULE_IMPORT_UNAVAILABLE` for `import`.
- Core: `ModuleCompileResult.sourceFingerprint` and `ProgramFingerprint.ofModules`, a hash of the entry
  file and all modules for hosts that save and restore runs (the same value as `ofSource` without `import`).
- Core: `CompileOptions` sets the compile-time program size limit. `CompileOptions.DEFAULT` and `EMBEDDED`
  keep 4096 instructions (`CreateScript.compile(source)` is unchanged); `STANDALONE` has no instruction
  limit. The input limits and the runtime limits are unchanged.
- `createscript run` and the interactive mode compile with `CompileOptions.STANDALONE`.
- `createscript run`: `--project-root <directory>` (default: the directory of the file). The interactive
  mode reports `CS_MODULE_IMPORT_UNAVAILABLE` for `import`.

## [0.1.0-alpha] - 2026-11-04 (pre-release)

The first version of CreateScript as a project of its own.

### Added

- `createscript-core`: lexer, parser, semantic analyzer, bytecode compiler, VM with an incremental
  garbage collector, VM snapshots with verification, and the `CreateScript` API for running programs
  tick by tick.
- Language: `Int`, `Float`, `Double`, `Boolean`, `String`; `val` / `var`; `if` / `else`, `while`, `for`
  over integer ranges, `break` / `continue`; functions with parameters, return values and recursion;
  classes with properties and methods; lists and nested lists; math functions, `PI` and `E`;
  deterministic random numbers; `Vector` and `Matrix`; `waitTicks`.
- `createscript-cli`: `createscript run <file.cscr>` with `--seed`, `--instructions-per-tick` and
  `--max-ticks`; the interactive mode (REPL) with JLine line editing and in-memory history.
- Packaging scripts for an unsigned macOS `.pkg` and a Linux `.tar.gz` with `install.sh` /
  `uninstall.sh`, each with a minimal Java 21 runtime made with `jlink`.
- Apache License 2.0, documentation in English and Japanese, and a CI workflow (build and tests on
  Ubuntu and macOS, Linux and macOS packages, gitleaks secret scan).

