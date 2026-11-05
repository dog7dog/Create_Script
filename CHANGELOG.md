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
- `CompileOptions.STANDALONE` raises the input limits: 1,000,000 characters, 250,000 tokens and 250,000
  syntax tree nodes per file, 1024 modules, and 8,000,000 characters and 1,000,000 tokens in all files.
  Its bytecode verification keeps state only at basic blocks and stops after 100,000,000 steps
  (`CS_COMP_PROGRAM_TOO_COMPLEX`); chains of properties without type annotations are limited to 128
  (`CS_SEM_PROPERTY_TYPE_CHAIN_TOO_DEEP`); too large programs give `CS_MODULE_PROGRAM_TOO_LARGE`.
- The compiler no longer recurses deeply or takes quadratic time on long call chains, recursion, import
  chains, construction cycles and comparisons that look like type arguments.
- `createscript run` and the interactive mode compile with `CompileOptions.STANDALONE`; the command reads
  files up to 3,000,000 bytes.
- `createscript-project`: a new module for the project files `createscript.json` (schema version 1) and
  `createscript.lock` (lock version 1): a strict JSON parser and writer (duplicate keys, size and nesting
  limits), validation of names, SemVer versions, entry paths, sources and SHA-256 values, the canonical lock file
  form and its consistency with the manifest. It does not access files or the network.
- `createscript init`, `createscript manifest check` and `createscript lock check`; exit code 73 when `init`
  cannot create the files. No downloads, installation or dependency resolution yet.
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

