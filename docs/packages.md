# Project files: `createscript.json` and `createscript.lock`

> **Status: in development, not in v0.1.0-alpha.** This is the first step towards packages (S8.1). The
> command can create and check these files. It does **not** download, install or resolve packages yet: there
> is no network access, no registry and no `createscript install`. `createscript run` does not read these
> files yet.

A CreateScript project is a directory with a manifest, `createscript.json`. A project that uses other
packages will also have a lock file, `createscript.lock`, which records exactly which packages were used.

```text
my-app/
  createscript.json
  createscript.lock      (only when there are dependencies)
  src/
    main.cscr
```

## Commands

```text
$ createscript init my-app
Created my-app/createscript.json
Created my-app/src/main.cscr
Run it with: createscript run my-app/src/main.cscr

$ createscript manifest check my-app
my-app/createscript.json: ok (my-app 0.1.0, entry src/main.cscr, 0 dependencies)

$ createscript lock check my-app
my-app/createscript.lock: not needed (no dependencies)
```

- `createscript init [directory] [--name <name>]` creates `createscript.json` and `src/main.cscr` (a "Hello"
  program). The package name defaults to the directory name; use `--name` when the directory name is not a
  valid package name. It never overwrites a file: if either file (or a link with that name) exists, nothing
  is created (exit code 73). Each file is written to a temporary file in the same directory and then given its
  name with a hard link, which fails instead of replacing a file that appeared meanwhile, so a file is never
  left half-written; the files get the normal permissions (as set by the umask). If `src` is a symbolic link or
  not a directory, nothing is created, so `init` never writes through a link. If a later step fails, `init`
  removes only the files and directories it created. It does not create a lock file.
- `createscript manifest check [directory]` checks `createscript.json` and that the entry file exists, is a
  regular file and, after following symbolic links, is inside the project directory.
- `createscript lock check [directory]` checks the manifest, then `createscript.lock` against it. A project
  without dependencies needs no lock file; a project with dependencies and no lock file is an error.

The checks only read files. Problems are reported like compile errors, `file:line:column: error CODE
("arguments")`, with exit code 65 (66 when a file is missing or unreadable, 64 for usage errors). Arguments
come from the files, so characters that could change what a terminal shows (control characters, bidirectional
and other format characters, line separators, unpaired surrogates) are shown as `\uXXXX`.

## `createscript.json` (schema version 1)

```json
{
  "schemaVersion": 1,
  "name": "example-project",
  "version": "0.1.0",
  "entry": "src/main.cscr",
  "license": "MIT",
  "dependencies": {
    "vector-math": { "version": "1.2.0", "source": "github:owner/vector-math" }
  }
}
```

| Field | Required | Rule |
|---|---|---|
| `schemaVersion` | yes | The integer `1`. Another integer gives `CS_PKG_UNSUPPORTED_SCHEMA_VERSION`, and the other fields are not checked (their meaning may differ in another version). |
| `name` | yes | Package name: lower-case ASCII letters and digits, with single `-` between them, starting with a letter, at most 64 characters (`vector-math`, `app2`). |
| `version` | yes | [Semantic Versioning 2.0.0](https://semver.org/), for example `1.2.0`, `0.1.0-alpha.1`, `1.0.0+build.5`. No leading zeros, at most 128 characters. |
| `entry` | yes | The program to run, as a relative path inside the project (see below). |
| `license` | no | An SPDX license expression such as `MIT` or `MIT OR Apache-2.0` (only the characters and the length are checked, not the SPDX list), at most 64 characters. |
| `dependencies` | no | Package name → `{ "version": exact version, "source": "github:owner/repository" }`. Both fields are required. Version ranges are not supported yet. A project cannot depend on itself. At most 1024. |

- **Unknown fields are errors** (`CS_PKG_UNKNOWN_FIELD`), so that a misspelt field is not silently ignored.
  New fields will come with a new `schemaVersion`.
- **Package names are not module names.** `import` uses module names (identifiers such as `vector_math`);
  package names may contain `-`. The two are separate, and nothing converts one into the other. How a package's
  modules are imported will be defined when packages can be installed.
- **Entry paths** use `/` between directories and must end in `.cscr`. These are rejected: an empty path,
  absolute paths (`/x`, `//server/x`), `\`, `:` (drive letters and URLs), empty names (`a//b`, a trailing `/`),
  `.` and `..`, characters other than ASCII letters, digits, `.`, `_` and `-`, names ending in `.`, names that
  are reserved on Windows (`con`, `nul`, `com1`, …), and paths longer than 256 characters. `createscript
  manifest check` also rejects an entry that is missing, is not a regular file, or leads outside the project
  through a symbolic link.

`createscript init` writes the manifest in a canonical form (the field order above, two-space indentation, LF
line ends, one final newline, dependencies sorted by name). Hand-written manifests do not have to be canonical.

## `createscript.lock` (lock version 1)

The lock file is written by the tools (from S8.2 on), not by hand. It records the exact packages that the
project uses, so that the same lock file always gives the same packages.

```json
{
  "lockVersion": 1,
  "root": {
    "name": "example-project",
    "version": "0.1.0"
  },
  "packages": [
    {
      "name": "vector-math",
      "version": "1.2.0",
      "source": "github:owner/vector-math",
      "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
      "dependencies": []
    }
  ]
}
```

| Field | Rule |
|---|---|
| `lockVersion` | The integer `1` (another integer gives `CS_PKG_UNSUPPORTED_LOCK_VERSION`). |
| `root` | `name` and `version` of the project; they must equal those in `createscript.json`. |
| `packages` | Every package used directly or indirectly, sorted by `name`. At most 4096. |
| `packages[].name`, `version`, `source` | Same rules as in the manifest. |
| `packages[].sha256` | SHA-256 of the package archive: exactly 64 lower-case hexadecimal digits (upper case is rejected, so the file has one form). |
| `packages[].dependencies` | Names of the packages that this package uses directly, sorted, without duplicates. Each must be in `packages`. At most 1024. |

### Package identity

- Within one lock file, a package is identified by its **name**. Each name appears once: a project uses one
  version of each package, from one source.
- `version` and `source` say which release of that name is used and where it comes from; `sha256` identifies
  the exact bytes of the package archive.
- Dependencies refer to packages by name. Dependency cycles are errors (`CS_PKG_LOCK_DEPENDENCY_CYCLE`).
- Two dependencies that need the same name with different versions or sources are a conflict. Resolving or
  reporting such conflicts is part of dependency resolution (S8.2 and later).

### Canonical form

The lock file has exactly one valid byte sequence for its contents: UTF-8 without a byte order mark, fields in
the order shown above, packages and their dependencies sorted by name, two-space indentation, `": "` between
keys and values, LF line ends and one final newline. Strings escape only `"`, `\` and control characters.
`createscript lock check` reports any other form as `CS_PKG_LOCK_NOT_CANONICAL` with the first line that differs,
even if the contents are the same. This makes lock files reproducible and their changes easy to review.

### Consistency with the manifest

`createscript lock check` reports:

- `CS_PKG_LOCK_ROOT_MISMATCH`: `root` is not the manifest's `name` or `version`;
- `CS_PKG_LOCK_MISSING_PACKAGE`: a dependency of the manifest is not in the lock file;
- `CS_PKG_LOCK_VERSION_MISMATCH` / `CS_PKG_LOCK_SOURCE_MISMATCH`: a package has another version or source than the
  manifest asks for;
- `CS_PKG_LOCK_UNUSED_PACKAGE`: a package that cannot be reached from the manifest's dependencies;
- `CS_PKG_LOCK_DUPLICATE_PACKAGE`, `CS_PKG_LOCK_DUPLICATE_DEPENDENCY`, `CS_PKG_LOCK_UNKNOWN_DEPENDENCY`,
  `CS_PKG_LOCK_DEPENDENCY_CYCLE`: problems inside the lock file.

In S8.1 the SHA-256 is only checked for its form; nothing is downloaded, so there is no archive to compare it with.

## JSON rules (both files)

- Strict JSON as in RFC 8259: no comments, no trailing commas, no single quotes, no unquoted keys, no `NaN`.
- **Duplicate keys are errors at any depth** (`CS_PKG_JSON_DUPLICATE_KEY`), instead of the last value winning
  silently. `{"a": 1, "a": 2}` is a duplicate too.
- Numbers must be integers that fit in 64 bits (no fraction or exponent): no field needs anything else.
- Strings must not contain raw control characters or unpaired surrogates.
- UTF-8 only; a byte order mark is rejected (`CS_PKG_BYTE_ORDER_MARK`), and so is invalid UTF-8.
- Size limits: 64 KiB for `createscript.json`, 1 MiB for `createscript.lock`; nesting at most 16 levels. The
  command reads at most one byte more than the limit, so a huge file is never read completely.
- After a JSON syntax error nothing else is checked; other problems are all reported (at most 64).

### Why a small parser of our own

The CreateScript build has no JSON library. We considered the usual choices for the JVM: kotlinx.serialization
(needs a Gradle compiler plugin in addition to the library), Jackson (a large library; it can reject duplicate
keys, but only when that feature is turned on), and Gson and org.json (small, but whether a duplicate key is
rejected or the last value wins depends on the API and the version). Any of them adds a dependency to the
packages and to the third-party notices, and we would still have to check the exact behaviour of the chosen
version. The project files need very little of JSON, and we want fixed limits on size and nesting, line and
column positions for every error, and duplicate keys always rejected. So `createscript-project` has its own
strict parser and writer (`Json.kt`, about 400 lines, without recursion, so deeply nested input cannot exhaust
the stack), tested against the cases above. The notes on the libraries come from their documentation as we
know it; we did not run them for S8.1.

## Code layout

- `createscript-project` (new Gradle module): the JSON parser and writer, the manifest and lock file models,
  their rules, the canonical form and the consistency checks. It uses only the Kotlin standard library and
  never touches files or the network; it takes bytes and returns values and diagnostics.
- `createscript-cli`: reads and writes the files, checks the entry file on disk, and implements `init`,
  `manifest check` and `lock check`.
- `createscript-core` (the language, compiler and virtual machine) is unchanged.

## Planned: packages on GitHub Releases (S8.2 and later)

This is not implemented. It is written down so that the lock file format fits it.

- A package version will be published as a **package archive attached to a GitHub Release** (a release asset),
  built by the package's author with a CreateScript tool. `sha256` in the lock file is the SHA-256 of that
  asset's bytes.
- The **"Source code (zip)" and "Source code (tar.gz)" files that GitHub generates automatically are not
  package archives** and will not be used or checked: they are generated on request, are not files that the
  author uploaded, and their bytes are not guaranteed to stay the same, so a lock file could not pin them.
- Downloads will be checked against `sha256` before anything is unpacked or compiled. Package sources will be
  given to the compiler through the existing `ModuleResolver`, so the Core still reads no files and no network.
  A program's identity for saving and restoring runs will include all package sources
  (`ModuleCompileResult.sourceFingerprint`).
- An official registry (reviewed through pull requests) and direct GitHub URLs (not reviewed) are planned; the
  `source` field will grow new forms with a new schema or lock version.
