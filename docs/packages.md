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
  removes only the files and directories it created (see [Rollback](#rollback-of-init)). It does not create a
  lock file.
- `createscript manifest check [directory]` checks `createscript.json` and that the entry file exists, is a
  regular file and, after following symbolic links, is inside the project directory. `createscript.json` itself
  is read under the same rule (see [Reading the project files](#reading-the-project-files)).
- `createscript lock check [directory]` checks the manifest, then `createscript.lock` against it. A project
  without dependencies needs no lock file; a project with dependencies and no lock file is an error.

The checks only read files. Problems are reported like compile errors, `file:line:column: error CODE
("arguments")`, with exit code 65 (66 when a file is missing or unreadable, 64 for usage errors). Arguments
come from the files, so characters that could change what a terminal shows (control characters, bidirectional
and other format characters, line separators, unpaired surrogates) are shown as `\uXXXX`.

### Rollback of `init`

When `init` creates a file, it records the file's identifier (`fileKey`: device and inode numbers on macOS and
Linux), its size and the SHA-256 of what it wrote; for a directory it records the identifier. If a later step
fails, it goes through what it created in reverse order and removes an item only if it is still the same:

- a file is removed only if it is a regular file (not a link), has the same size and SHA-256, and the same
  identifier when both identifiers are known;
- a directory is removed only if it is a directory with the same identifier (when known) and is empty.

Anything else is kept and listed in the error message, for example
`kept: my-app/createscript.json (it was replaced by another file)`. Where the file system gives no identifier
(`fileKey()` returns `null`, as on some file systems and platforms), only the size and the SHA-256 are compared;
a file replaced by another one with exactly the same contents then cannot be told apart and is removed.
Checking and removing are two separate steps with a file name, so a replacement in between (a time-of-check to
time-of-use race) cannot be ruled out completely with the Java file API; the checks make the window very small.

### Reading the project files

`manifest check` and `lock check` read `createscript.json` and `createscript.lock` like this:

- A symbolic link is followed, and the file it leads to must be inside the project directory
  (`CS_PKG_FILE_OUTSIDE_PROJECT` otherwise, also for links to `/dev/null` and other devices). A broken link is
  reported as a missing file.
- What the path leads to must be a regular file; directories, FIFOs (named pipes), devices and sockets give
  `CS_PKG_NOT_A_REGULAR_FILE`. This is checked before the file is opened, because opening a FIFO waits for a
  writer.
- The file is opened at its real path without following links (`NOFOLLOW_LINKS`), at most the size limit plus
  one byte is read, and its identifier, size and modification time are compared before and after reading
  (`CS_PKG_FILE_CHANGED_WHILE_READING` if they differ).

These steps reduce, but do not remove, the races between checking and reading: a directory on the path could be
swapped after the check, and a file swapped for a FIFO just before it is opened would still make the command wait.
`NOFOLLOW_LINKS` only applies to the last part of the path. The commands only read these files, and show at most
short parts of them in diagnostics.

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

## `createscript.json` schema version 2: applications and libraries

> **S8.2 Phase 1** (in development). Schema version 1 stays valid and unchanged; `createscript init` still
> writes version 1.

```json
{
  "schemaVersion": 2,
  "kind": "library",
  "name": "vector-math",
  "version": "1.2.0",
  "license": "MIT",
  "modules": ["vector_math"],
  "dependencies": {}
}
```

| Field | Rule |
|---|---|
| `kind` | Required in version 2: `"application"` (a program with an `entry`) or `"library"` (a package that other projects import). |
| `entry` | Required for an application, optional for a library. |
| `modules` | Only for a library, and required there: the module names that other projects can `import`, at least one, no duplicates, at most 1024. |

All other fields keep the version 1 rules, and unknown fields are still errors. A module name is a CreateScript
identifier: an ASCII letter or `_` first, then letters, digits and `_`, not only `_`, at most 32 characters, not
a Kotlin hard keyword and not a built-in function that cannot be redefined (`println`, `waitTicks`, `listOf`,
`mutableListOf`). **Package names and module names are separate:** the package `vector-math` may export the module
`vector_math`, and nothing converts one into the other. A library's module `name` lives in the file
`modules/name.cscr`.

### Public and private modules

- **Public modules** are the ones listed in `modules`. Projects that depend on the package can `import` them.
- **Private modules** are the other `.cscr` files in `modules/`. Only the package's own modules are meant to
  import them; a project that depends on the package cannot. This visibility rule is enforced from S8.2 Phase 4
  (until then nothing installs packages, so nothing imports them).
- **Private does not mean secret.** Private modules are in the package archive like the public ones, so anyone
  who downloads the package can read their source. "Private" only limits what other packages and projects can
  `import`; it is not a way to hide code, keys or data.

## Package archives: `createscript-package.zip`

> **S8.2 Phase 1** (in development). The archive format and `createscript pack` exist; downloading and installing
> come in the next phases.

A library is distributed as one ZIP file named `createscript-package.zip`, attached to a GitHub Release whose tag
is `v<version>`. GitHub's automatically generated "Source code" archives are not package archives.

```text
createscript-package.zip
  createscript.json          schema version 2, kind "library"
  modules/vector_math.cscr   modules listed in "modules"
  modules/helpers.cscr       other modules of the package
  LICENSE                    optional: LICENSE, LICENCE, NOTICE, README, CHANGELOG or COPYING, also with .md or .txt
```

`createscript pack [directory] [--output <file>]` makes the archive from a library project. It takes exactly
`createscript.json`, the `.cscr` files directly in `modules/` and the documents listed above, and refuses anything
else in `modules/`, symbolic links and files that are not regular files. It writes entries sorted by path, without
compression, with the time 1980-01-01 00:00 and mode 0644, and without directory entries, comments or extra
fields, so the same files always give the same bytes. It checks the result with the same rules as an
installation, prints its SHA-256 and never overwrites a file (exit code 73). It does not run or compile any code.

An archive is accepted only in this strict form (other tools may write `DEFLATED` entries and a `modules/` directory
entry; both are accepted):

- **Layout:** the end of central directory record at the end (a comment is allowed), the central directory right
  before it, and the local headers and data from the first byte on, one after another without gaps or overlaps.
  The local headers must match the central directory (name, method, sizes, CRC). Prepended data, data between
  entries and data after the end record are rejected, and so are overlapping entries (a known way to build zip bombs).
- **Not supported:** ZIP64, multiple disks, encryption, data descriptors, and compression methods other than STORED
  and DEFLATED.
- **Entries:** names must be printable ASCII and follow the paths above (no `..`, absolute paths, `\`, `:` or other
  directories). Only regular files and the `modules/` directory are allowed: entries whose Unix file type is a
  symbolic link, a device, a FIFO or a socket are rejected. Duplicate paths, also those that differ only in case,
  are rejected.
- **Limits:** the archive at most 32 MiB; at most 1024 entries; a `.cscr` file at most 3,000,000 bytes (the
  standalone source limit of 1,000,000 UTF-16 units times 3 bytes, the same value as the `createscript` command's
  file limit), other files at most 1 MiB; at most 16 MiB in all after expansion; at most 100 times compression.
  The declared sizes are checked before expanding, and expansion stops at the declared size, so an entry that
  expands to more than it declares is rejected.
- **Contents:** `createscript.json` must be a schema version 2 library (with the expected name and version when
  installing), every module in `modules` must have its file, and every `.cscr` file must be valid UTF-8 without a
  byte order mark and at most 1,000,000 characters. Every entry's CRC-32 is checked.

The files of a package are only source code and documents. Neither the archive nor its manifest can give a
program more access to the operating system, the network or the JVM than any other CreateScript program has.

## Downloading package archives (S8.2 Phase 2)

> **In development.** The download, the checks and the cache exist as a library (`createscript-install`); no
> command uses them yet. Installing (unpacking, the lock file and dependencies) comes in Phase 3.

For a dependency `{ "version": "1.2.0", "source": "github:owner/repo" }` the archive is

```text
https://github.com/owner/repo/releases/download/v1.2.0/createscript-package.zip
```

The URL is built from the name, so downloading needs no GitHub API request and is not limited by the API rate
limit.

### Connections

- Only HTTPS to `github.com`, `api.github.com` and the hosts that GitHub redirects release downloads to
  (`release-assets.githubusercontent.com`; `objects.githubusercontent.com` is also allowed), on port 443. The list is
  fixed in the code: no option, environment variable or file can change where packages are downloaded from.
- Redirects are followed by CreateScript itself (at most 5), and every location is checked against the list. URLs
  with user information (`user@host`), other ports, IP addresses (also decimal and hexadecimal forms) and
  fragments are refused.
- System proxy settings are not used (proxies are planned for S8.3).
- A GitHub token is optional and read only from the environment variable `CREATESCRIPT_GITHUB_TOKEN`. It is sent
  only to `api.github.com` (never with downloads or to redirect locations) and never printed; messages also leave
  out the query part of URLs, which for release downloads contains a signature.
- Limits: 10 seconds to connect, 60 seconds for each attempt including the whole body, 32 MiB for an archive and
  4 MiB for an API response, counted in bytes actually received (a larger `Content-Length` is refused before
  reading). A stalled connection cannot hold an attempt longer than 60 seconds.
- Retries: up to 3 more attempts after network errors, timeouts, HTTP 5xx and 429, waiting 1, 2 and 4 seconds, or as
  long as `Retry-After` or the rate limit reset says (when that is more than 60 seconds, CreateScript gives up and
  suggests a token). HTTP 404, 401 and other 403 responses are not retried.

### Trust

| What | Trusted party | Shows | Does not show |
|---|---|---|---|
| The SHA-256 in `createscript.lock` | your project | the archive is exactly the one recorded before; **any other archive is always refused** | whether the recorded one was good |
| First download without a lock entry (TOFU) | HTTPS and whoever controls the repository at that time | the archive was not changed on the way | who wrote it, or that the account is not compromised |
| GitHub's `digest` for the asset (from the API) | GitHub, the same party as the download | the file was not damaged or swapped between GitHub and you; **a different value is refused** | anything about the author. If the API cannot be used (rate limit, network, no digest), the download is reported as **unverified** and the reason is given |
| `immutable` of the release (from the API) | GitHub | the release assets cannot be replaced after publishing | whether they were good when published |
| The cache | nobody | — | it is read only when the lock file gives a SHA-256, and that SHA-256 is checked every time |

Proving who published a package (signatures, attestations) is planned for S8.3, and reviewed hashes in an official
registry for S8.4.

### Cache

Archives are stored by their SHA-256 in `archives/sha256/<sha256>.zip` under `CREATESCRIPT_CACHE_DIR`, or by
default `~/Library/Caches/CreateScript` on macOS and `$XDG_CACHE_HOME/createscript` or `~/.cache/createscript`
elsewhere. Directories that CreateScript creates there are private to the user (mode 0700). The cache location
itself may be a symbolic link (to another disk, for example), but `archives`, `sha256` and the entries must not be:
links and entries whose contents do not match their name are not used, deleted or replaced, and the download goes
on without the cache with a warning. Nothing in the cache is ever run.

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
  their rules, the canonical form and the consistency checks, and the package archive reader and writer
  (`PackageArchives`). It uses only the Kotlin standard library and never touches files or the network; it takes
  bytes and returns values and diagnostics.
- `createscript-install` (S8.2 Phase 2): downloads package archives from GitHub Releases with the JDK HTTP client,
  checks their SHA-256 and GitHub's digest, and keeps the cache. It never runs what it downloads.
- `createscript-cli`: reads and writes the files, checks the entry file on disk, and implements `init`,
  `manifest check`, `lock check` and `pack`.
- `createscript-core` (the language, compiler and virtual machine) is unchanged.

## Planned: installing packages (S8.2 Phase 3 and later)

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
