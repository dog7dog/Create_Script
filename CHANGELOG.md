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
- Core: `DiagnosticPhase.MODULE` for diagnostics about modules and imports (`CS_MODULE_…`). Code with a `when`
  over `DiagnosticPhase` that lists every value and has no `else` needs a branch for it.
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
  cannot create the files. No downloads, installation or dependency resolution yet. `init` rolls back only
  files and directories that are still the ones it created (identifier, size, SHA-256). The checks read only
  regular files inside the project (`CS_PKG_FILE_OUTSIDE_PROJECT`, `CS_PKG_NOT_A_REGULAR_FILE`,
  `CS_PKG_FILE_CHANGED_WHILE_READING`).
- Project files schema version 2 (`kind` `application` or `library`, `modules`) and package archives
  (`createscript-package.zip`): a strict ZIP reader (layout, no ZIP64, encryption, data descriptors, links or
  special files, size, count and ratio limits, CRC checks) and a deterministic writer. `createscript pack` makes a
  package archive from a library project. Schema version 1 is unchanged.
- `createscript run [directory]`: runs the `entry` of `createscript.json` with the installed packages, without the
  network. Before compiling, the lock file is checked as by `lock check` and every installed package must match its
  marker and the lock file (SHA-256 of the archive, its manifest and dependencies), and the canonical archive made
  again from the installed files must have the SHA-256 of the lock file, so files changed together with their
  marker are also refused; otherwise nothing is run (exit code 65). `install` accepts only archives in the
  canonical form that `createscript pack` writes (other valid ZIP files are refused with exit code 65). The project and each package can import the public modules of their direct dependencies and their own
  modules; private modules of other packages and transitive dependencies give `CS_MODULE_NOT_VISIBLE`. Each import
  is looked up in the scope of its file, so modules with the same name in different packages (private modules
  included) or a local module and a private package module can be used together; only a name that exists twice in
  one scope gives `CS_MODULE_AMBIGUOUS`. A package module named like the entry file is no longer reported as an
  import cycle. `createscript run <file>.cscr` is unchanged.
- Core: `ScopedModuleResolver`, `ScopedModuleLookup` and `CreateScript.compileScoped(source, resolver,
  entryModule, options)`: modules are named by an id from the resolver, which is asked for each importing file;
  functions are named `id.function`. The existing `compile` functions, `ModuleResolver` and `ModuleAccess` are
  unchanged and give the same instructions, diagnostics and fingerprints as before.
- Core: `ModuleAccess` and `CreateScript.compile(source, resolver, entryModule, options, access)`, checked for every
  `import` before the resolver; the diagnostic `CS_MODULE_NOT_VISIBLE`. The existing `compile` functions and
  `ModuleResolver` are unchanged (code compiled against the earlier Core runs unchanged; a regression test runs
  such code).
- `createscript init` creates a `.gitignore` with `/.createscript/` when the directory has none; an existing one is
  never changed.
- `createscript install [github:owner/repo@v1.2.0] [directory] [--offline]`: installs a package and its
  dependencies (exact versions, followed depth first in name order; conflicts, cycles and more than 256 packages,
  a depth of 32 or 128 MiB are errors) into `.createscript/packages/<name>/<version>/`, adds the package to
  `createscript.json` (only that entry changes) and writes `createscript.lock`. SHA-256 values in the lock file are
  always enforced; `--offline` uses only the cache. A journal (`.createscript/transaction.json`) and
  `.createscript/install.lock` make the update of several files safe: a failed or interrupted installation is
  finished or undone, files changed by another program are never overwritten or removed, and a damaged journal
  stops with instructions. New exit codes 69 and 75. `manifest check`, `lock check` and `pack` stop while an
  installation is interrupted. When an interrupted installation cannot be finished or undone safely, the message
  lists the files that are left, the state of the manifest and the lock file, and the steps to check them; it does
  not ask to delete the journal first. The journal records the real path of the project and the file identity of
  `.createscript` and of the transaction directory, and is used automatically only where it was written (a journal
  in a copied, downloaded, moved or restored project, or on a file system without file identities, stops with
  these instructions). Before a recovery writes the manifest or the lock file, it checks that the manifest is the
  old one with at most one dependency added or changed, that the lock file passes `lock check` and keeps the
  SHA-256 of packages that did not change, and that every package makes again the canonical archive of the lock
  file; saved copies used to undo are checked too. A failure after a recovery still reports the recovery.
  The journal is written first as `journal.tmp` in the transaction directory and published with a hard link or,
  where there are none, an atomic move; it is never copied, and the installation stops unchanged where neither
  works. A `txn-*` directory that no journal refers to is removed by the next install only when everything in it
  matches that record (no links, this user's files, no other files); otherwise it is kept and reported with the
  reason. Drives that add `._` files (FAT and exFAT on macOS) are refused before anything changes.
  An installation that stopped while the journal was being rewritten (a half-written `journal.tmp`) is finished
  on the next install: the temporary file is removed only when it belongs to this user, is not a link and is a
  complete journal of this transaction or the beginning of one; otherwise the command stops with steps to check
  by hand. A journal whose transaction directory is missing is reported as such.
  Before each package is moved, the directories on the way must be real directories inside the project; after the
  move and again before the manifest is written, each package must be where it was moved (same `fileKey`), or the
  installation is not completed and is undone without touching anything behind a symbolic link. A move redirected
  by a symbolic link that replaces a directory at that moment cannot be prevented and is documented.
- `createscript install` warns about package directories in `.createscript/packages` that cannot be used (files
  that do not match the marker or no longer make its archive, symbolic links, unknown names), for example one
  edited after an interrupted installation was undone; it does not remove, repair or follow them. Usable old
  versions are not reported.
- Names reserved on Windows (`CON`, `PRN`, `AUX`, `NUL`, `COM0`–`COM9`, `LPT0`–`LPT9`, in any case and with any
  extension) are rejected as package names, module names, entry paths and module files in package archives
  (`CS_PKG_RESERVED_NAME`, or the reason in `CS_PKG_INVALID_PATH` and `CS_PKG_ARCHIVE_INVALID_PATH`), by
  `init`, `pack` and `install`, and as local modules of `import` (`CS_MODULE_UNREADABLE`). Running a single file
  is unchanged.
- Files of an installation are written under a temporary name (`.part-<inode>-<size>-<name>`) and renamed when
  complete, so an installation that stopped while it was writing them leaves no half-written file under the real
  name, and the next install removes the temporary file when it is still the file it created (same inode, not
  larger than recorded, expected by the record); otherwise it is kept and reported.
  The temporary file is read-only (`r--------`) while it is written through the descriptor that created it, so
  other writes are refused, and only read-only temporary files are removed; each removal checks the SHA-256 and
  the file identity again.
- `createscript install` and `createscript run` with packages refuse projects where another user can change the
  project root, a directory above it (unless it is sticky and the next directory is yours) or a directory in
  `.createscript`; the message names the directory and its permissions, and permissions are never changed.
  Directories and files that `install` creates are `rwxr-xr-x` and `rw-r--r--`. ACLs are not checked, and file
  systems without POSIX permissions are not supported for packages yet.
- `createscript run [directory]` holds a shared lock on `.createscript/install.lock` while it checks the project
  (released before compiling; the file is only opened for reading and never created): while an installation is
  running it stops with exit code 75 instead of reporting an interrupted installation, which it reports (65) only
  when no installation is running. Where the lock cannot be tried, a journal stops it with a message that says so.
- `createscript-install`: downloading package archives from GitHub Releases with the JDK HTTP client (HTTPS to fixed
  GitHub hosts only, checked redirects, the token only for `api.github.com`, size and time limits, retries and rate
  limits), SHA-256 checks against the lock file and GitHub's asset digest, and a cache keyed by SHA-256 that is
  checked on every use. The bundled Java runtime gains `java.net.http` and
  `jdk.crypto.ec` (for TLS).
- Core: `ast.Program` keeps the JVM signatures of v0.1.0-alpha (the constructor and `copy` with three lists, as
  hidden members), so code compiled against v0.1.0-alpha still runs; a regression test runs such code.
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

