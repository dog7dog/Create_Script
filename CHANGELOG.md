# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/). Before 1.0, any release may change the language or the APIs.

## [Unreleased]

## [0.1.0-alpha] - not released yet

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

