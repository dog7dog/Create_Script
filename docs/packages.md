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
  program), and a `.gitignore` with the line `/.createscript/` when the directory has no `.gitignore` (an existing
  `.gitignore`, also a link or a directory with that name, is never changed). The package name defaults to the directory name; use `--name` when the directory name is not a
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
| `name` | yes | Package name: lower-case ASCII letters and digits, with single `-` between them, starting with a letter, at most 64 characters (`vector-math`, `app2`). Not a [name reserved on Windows](#names-reserved-on-windows). |
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
  are [reserved on Windows](#names-reserved-on-windows) (`con.cscr`, `src/NUL.backup.cscr`, `com1/main.cscr`),
  and paths longer than 256 characters. `createscript manifest check` also rejects an entry that is missing, is
  not a regular file, or leads outside the project through a symbolic link.

### Names reserved on Windows

Windows treats `CON`, `PRN`, `AUX`, `NUL`, `COM0` to `COM9` and `LPT0` to `LPT9` as devices, in any case and with
any extension (`con.cscr`, `Nul.txt` and `com1.tar.gz` are all devices), and it ignores dots and spaces at the
end of a name (`con.` and `con ` too). Such files cannot be created there, or open a device instead of a file.
So that a project and its packages work on every system, these names are rejected everywhere, also on macOS and
Linux (S8.2):

- package names in `createscript.json` (`name`, `dependencies`) and `createscript.lock`, and `createscript init`
  (also a name taken from the directory): `CS_PKG_RESERVED_NAME` with the name;
- module names in a library's `modules` (`CS_PKG_RESERVED_NAME`) and module files in a package archive
  (`modules/con.cscr`, also private modules: `CS_PKG_ARCHIVE_INVALID_PATH` with the reason), so `pack` and
  `install` refuse them;
- entry paths (`CS_PKG_INVALID_PATH` with the reason);
- `import con` of a local module, in project mode and when running a file (`CS_MODULE_UNREADABLE`, "'con' is a
  reserved file name on Windows").

Names that only begin with such a name (`console`, `com10`, `aux-tools`) are allowed. Running a single file
whose own name is reserved (`createscript run con.cscr`, as in v0.1.0-alpha) is unchanged. Archive paths and
module names allow only ASCII letters, digits and `_` (and `.` before `cscr`), so the other Windows rules
(`< > : " / \ | ? *`, control characters, trailing dots and spaces) cannot occur there, and paths that differ
only in case are already refused. This has not been tried on Windows itself.

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
`mutableListOf`), and not a [name reserved on Windows](#names-reserved-on-windows). **Package names and module names are separate:** the package `vector-math` may export the module
`vector_math`, and nothing converts one into the other. A library's module `name` lives in the file
`modules/name.cscr`.

### Public and private modules

- **Public modules** are the ones listed in `modules`. Projects that depend on the package can `import` them.
- **Private modules** are the other `.cscr` files in `modules/`. Only the package's own modules are meant to
  import them; a project that depends on the package cannot. This rule is checked by `createscript run [directory]`
  (S8.2 Phase 4; see [Modules from packages](language.md#modules-from-packages)).
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

### Canonical form

The bytes that `pack` writes are the **canonical form** of the archive: they are fully determined by the paths and
contents of its files, with nothing taken from the machine, the file system or the time. For each file, in order of
path (the paths are ASCII, so byte order and character order are the same):

- a local header: signature `PK\3\4`, version needed 10, flags 0, method 0 (STORED), time 0 and date
  1980-01-01 (`0x0021`), the CRC-32, the size twice, the length of the name, no extra field; then the name and the
  contents;

then a central directory entry for each file in the same order (version made by `0x0314`: Unix, ZIP 2.0; version needed 10,
flags 0, method 0, the same time, date, CRC-32 and sizes, no extra field, comment, disk number or internal
attributes, external attributes `0100644 << 16` (a regular file, mode 0644), the offset of the local header, the
name), and an end of central directory record without a comment. All numbers are little-endian. There is no
directory entry, data descriptor, ZIP64 record or anything else.

So the files of a package and its canonical archive determine each other exactly. `install` accepts only canonical
archives (an archive that is a valid ZIP file with the right contents but was made by another tool, for example
with compression, a `modules/` directory entry, other times or attributes, or a comment, is refused with exit code
65; make the archive with `createscript pack`). Because of this, the archive can be made again from the installed
files, and its SHA-256 compared with `createscript.lock` (see
[Running installed packages](#running-installed-packages-s82-phase-4)).

`PackageArchives.read` (used by `pack` to check its result and by `install`) accepts an archive only in this strict
form; it also accepts the `DEFLATED` entries and the `modules/` directory entry that other tools write, but `install`
then also requires the canonical form above:

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

> **In development.** The download, the checks and the cache are in the library `createscript-install` and are
> used by `createscript install` (see [Installing packages](#installing-packages-s82-phase-3)).

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
- System proxy settings are not used (proxies are planned for a later stage).
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

Reviewed hashes in an official registry with a signed index are being built in S8.3 (see [registry.md](registry.md)).
The client is in place (Phase 3: `createscript install <name>@<version>`, and a review status for direct installs
and restores), but **the official registry is not published and has no production key yet, and it is looking for
maintainers**; until then `createscript install <name>@<version>` refuses (exit code 69) without connecting, and
direct installs report that their review status could not be checked. Signatures by the authors of packages
themselves are not planned yet.

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
  (`PackageArchives`), and the registry formats, the canonical index and its Ed25519 signature check
  (`project.registry`, S8.3 Phase 1, see [registry.md](registry.md)). It uses only the Kotlin standard library and
  the JDK, and never touches files or the network; it takes bytes and returns values and diagnostics.
- `createscript-install` (S8.2 Phases 2 and 3): downloads package archives from GitHub Releases with the JDK HTTP
  client, checks their SHA-256 and GitHub's digest, keeps the cache, resolves dependencies, and installs them
  with the journal. It never runs what it downloads. S8.3 Phase 3 adds the registry client (`RegistryTrust`,
  `RegistryClient`, `RegistryCache`, `RegistryReview`): it downloads and verifies the signed index, keeps the
  accepted index and its history, and checks the review status of what is installed ([registry.md](registry.md#the-client-createscript-install-nameversion-phase-3)).
- `createscript-registry-tool` (S8.3 Phase 2): checks registry pull requests and approvals, and builds, signs
  (test keys only for now), checks and verifies the index for the planned registry ([registry.md](registry.md)).
  It is not part of the `createscript` packages.
- `createscript-cli`: reads and writes the files, checks the entry file on disk, and implements `init`,
  `manifest check`, `lock check`, `pack` and `install` (including `install <name>@<version>`).
- `createscript-core` (the language, compiler and virtual machine) is unchanged.

## Installing packages (S8.2 Phase 3)

> **In development.** Installed packages are used by `createscript run [directory]` (S8.2 Phase 4; see
> [Running a project](cli.md#running-a-project) and [Modules from packages](language.md#modules-from-packages)).
> `createscript install <name>@<version>` (installing by name from the signed registry index, S8.3 Phase 3) uses
> the same installer; it is described in [registry.md](registry.md#the-client-createscript-install-nameversion-phase-3)
> and does not work until the official registry is published.

```text
$ createscript install github:owner/vector-math@v1.2.0
Installed numbers 3.0.0 from github:owner/numbers -> .createscript/packages/numbers/3.0.0
  sha256 9c1e…
  downloaded; recorded on first use (trust on first use); GitHub digest matched; the release is immutable
Installed vector-math 1.2.0 from github:owner/vector-math -> .createscript/packages/vector-math/1.2.0
  sha256 3f1c…
  downloaded; recorded on first use (trust on first use); GitHub digest matched; the release is immutable
Updated createscript.json (dependency vector-math 1.2.0 from github:owner/vector-math)
Wrote createscript.lock (2 packages)

$ createscript install              # install what createscript.json and createscript.lock say
$ createscript install --offline    # the same without the network (from the cache only)
```

- `createscript install github:<owner>/<repo>@v<version> [directory]` downloads the package, reads its name from
  its `createscript.json`, adds or changes that one entry in `dependencies` and installs everything. Only exact
  versions are accepted (`@v1.2.0` or `@1.2.0`). A dependency of the same name from another source is not
  replaced, and one source cannot be two packages.
- `createscript install [directory]` installs the dependencies in `createscript.json`. Packages that
  `createscript.lock` has with the same name, version and source use its SHA-256, which is **always** enforced
  (a different archive is refused, from the cache or from GitHub). Other packages are recorded on first use.
  Lock entries that are no longer needed are removed from the lock file.
- `--offline` uses no network: every package must be in the cache with the SHA-256 from the lock file.
- Running the same command again changes nothing (no file is rewritten).
- Exit codes: 69 network unavailable (or not in the cache with `--offline`), 65 a problem in a package, a conflict
  or an invalid project file, 66 no `createscript.json`, 73 files are not in the expected state (symbolic links,
  changed files, an interrupted installation that cannot be finished or undone), 75 another install is running.

### Dependencies

- Dependencies are followed from `createscript.json`, depth first in name order, so the order of downloads and
  the result depend only on the files.
- Each name is one package. Two requirements for the same name must have the same version and source, otherwise
  the installation stops with both requirements and who made them (versions are not chosen or merged; ranges
  and resolution are planned for S8.4). One source cannot be two packages, and no package can depend on the
  project itself.
- Cycles are refused (`dependency cycle: alpha -> beta -> alpha`).
- Limits for one installation: 256 packages, a depth of 32, and 128 MiB of package files in all (each archive
  also has the [archive limits](#package-archives-createscript-packagezip)).
- Every archive is checked like `createscript pack` checks it: a library, the expected name and version, only
  allowed paths, valid sources. If `createscript.lock` lists other dependencies for a package than its archive
  does, the lock file was changed by hand and the installation stops.

### Files

```text
.createscript/
  install.lock                       held while an installation runs (an empty file)
  packages/<name>/<version>/         an installed package: createscript.json, modules/*.cscr, documents
    .createscript-package.json       the marker: which installation put the files there and their SHA-256
  transaction.json                   only while an installation is running or after it was interrupted
  txn-<id>/                          the prepared files of that installation
```

- `.createscript/` should not be committed. `createscript init` creates a `.gitignore` with `/.createscript/` when the
  directory has none; an existing `.gitignore` (or a link or directory with that name) is never changed. `install`
  suggests adding the line when it is missing and does not change `.gitignore` itself.
- An existing `packages/<name>/<version>/` is used only if its marker and files match the package exactly (no
  missing, extra or changed files, no symbolic links) and the canonical archive made again from its files has the
  SHA-256 of the package. Otherwise the installation stops before changing anything and asks you to remove the
  directory.
- Directories of versions that are no longer used are not removed (removing packages is planned for a later stage).
- `createscript.json`, `createscript.lock`, `.createscript` and everything in it must not be symbolic links:
  `install` does not write through links.
- Nothing that is downloaded is run: archives are only checked and unpacked as files.

### Changing several files safely

1. Hold `.createscript/install.lock` (`FileChannel.tryLock`), so that only one installation runs in a project.
   The file has two locked ranges: a gate (byte 0) and the data (the rest). `install` takes the gate
   exclusively and then the data exclusively; `run` takes the gate shared, then the data shared, and lets the gate
   go at once. While an installation waits with the gate, new runs cannot start their check, so a stream of runs
   cannot keep it out; both take the gate first, so they never wait for each other in a circle. `install` waits up
   to 30 seconds and `run` up to 10 (then exit code 75); the lock is tried again every 20 milliseconds, without
   an order among several waiting programs.
2. Read the project files and download and check every package. Up to here nothing in the project changes, so a
   network or check failure leaves everything as it was.
3. Create a new `.createscript/txn-<id>/` and write in it, first, `journal.tmp`: the complete journal (state
   `prepared`) with the SHA-256 of the old and new manifest and lock file and of each package marker (the markers
   are made in memory first). Then write the new manifest and lock file, copies of the current ones, and the
   unpacked packages (each package's marker first, then its files), and flush them to disk.
4. Publish the journal: give `journal.tmp` the name `.createscript/transaction.json` with a hard link, which never
   replaces a file. Where the file system has no hard links (FAT, for example), check that the name is free and
   rename the file with an atomic move (`ATOMIC_MOVE`); `install.lock` keeps other installations away in the
   meantime. Where neither works, the installation stops and changes nothing. The journal is never copied, so a
   half-written `transaction.json` cannot appear (and one that is not complete JSON with every field is refused).
5. Move the packages into `packages/` (never replacing a directory), then replace `createscript.json` and
   `createscript.lock` (a rename). Before each replacement the current file must be the one read in step 2, with
   the same SHA-256 and, where the file system tells, the same file (`fileKey`): a file that another program
   changed or replaced is never overwritten. Before each package is moved, every directory from the project to
   `packages/<name>` must be a real directory (not a symbolic link) whose real path is where it should be. After the
   move, the package must be there: `packages/<name>` is still that directory (the same `fileKey`), and the
   package directory is the one that was moved (the same `fileKey`) with this installation's marker and files.
   All packages are checked again just before `createscript.json` is replaced. If any check fails, the
   installation is not completed and is undone; nothing behind a symbolic link is read, changed or removed.
6. Mark the journal `committed`, then remove the prepared files and the journal.

When a step fails, the installation is undone at once. When the process stops (a crash or `kill`), the next
`createscript install` finds the journal and finishes or undoes the installation before doing anything else:

- **The journal is not trusted.** It must be a regular file of this user with exactly the expected fields and
  valid values; package names, versions and the id are checked, so it cannot name paths outside `.createscript`.
- **It must have been written here.** The journal records where it was written: the real path of the project and
  the file identity (`fileKey`) of `.createscript` and of `txn-<id>`. It is used automatically only when all three
  are the same now. A project that was copied, unpacked, downloaded, moved or restored from a backup has new
  directories (or another path), so a journal that came with it is never used automatically; the command stops as
  described below. Where the file system has no `fileKey` (Java reports none on Windows NTFS, for example), an
  interrupted installation is never finished or undone automatically; installations that are not interrupted, and
  failures that are undone at once by the same process, work as usual. Journals of the earlier version (without
  this record) are not used automatically either.
- **What is written is checked, every time.** The recorded place is not a proof (see below), so before finishing,
  the new manifest must be the old one or the old one with one dependency added or changed (the only change
  `install` makes), the new lock file must pass `lock check` against it, a package that the old lock file has
  with the same name, version and source must keep its SHA-256 and dependencies, and every package to be put in
  place must make again the canonical archive with the SHA-256 of the new lock file. Before undoing, the saved
  old manifest and lock file must be valid and fit the new ones in the same way. When a check fails, nothing is
  changed and the command stops as described below (it does not switch to the other direction).
- **Finish** when the new manifest and lock file are in place or their copies are intact, and every package is
  in place with this installation's marker or its prepared copy is intact.
- **Undo** otherwise: a manifest or lock file whose SHA-256 is the new one is put back from its copy (or removed if
  there was none), and package directories with this installation's marker and unchanged files are removed.
- **Files changed by someone else are never overwritten or removed.** A manifest or lock file that is neither the
  old nor the new one, and package directories that do not match, are left as they are; the installation is
  undone otherwise and stops with a list of what was left.
- **When neither is safe** (a damaged journal, or missing or changed copies), nothing is changed. The command gives
  the reason, lists the files that the installation left (`transaction.json` and `txn-<id>/` with its contents),
  says whether `createscript.json` and `createscript.lock` are the version before or after the installation or were
  changed by someone else, and lists the steps to check them by hand: compare them with the saved copies (`*.old`
  before, `*.new` after), check the package directories of that transaction, and only then move the journal and
  `txn-<id>/` out of the project (keeping them until you are sure) and run `createscript install` again. It never
  asks to delete the journal before the files are checked.
- `manifest check`, `lock check` and `pack` stop with exit code 65 while a journal exists.
- An installation that stopped before step 4 has changed nothing. Its `txn-<id>` directory is removed by the next
  `createscript install` (which reports it) only when no journal exists and everything in it matches the record
  it began with: the directory, its files and subdirectories are not symbolic links and belong to this user, its
  name is `txn-` and a UUID, `journal.tmp` is a valid journal with that id, and every other file is one of the
  copies or prepared packages that the record names, with the recorded SHA-256 (files that were not written yet may
  be missing). Everything is checked before anything is removed. Anything else stays where it is and is reported
  with the reason, for example a file that was changed, a file that `install` does not make, a symbolic link, or a
  directory without `journal.tmp` (what a finished or undone installation leaves when it could not confirm and
  remove some files, or what an earlier version made).
- **Files that were being written.** Every file in `txn-<id>/` (`journal.tmp` first) is written under a temporary
  name and gets its own name only after it was written completely and flushed to disk, so a file with its own name
  is never a part of one. The temporary name is first `.part-<UUID>` (still empty), then
  `.part-<inode>-<size>-<name>`, which records the file itself (its inode) and how large it will be. The file is
  created read-only for its owner (`r--------`) and written only through the descriptor that created it, so other
  writes to it (appending, overwriting, an editor saving in place) are refused; the final file gets `rw-r--r--`
  after it has its name. A stopped installation can leave one such file; the next install removes it with the
  rest only when it belongs to this user, is a regular file (not a link), is still read-only, is still the file
  that `install` created (the same inode; an editor that saves into a new file changes it), is not larger than
  recorded, and is for a name that the record expects and that does not exist yet (an empty `.part-<UUID>` holds
  nothing and may go). Just before each file is removed, its SHA-256 and file identity (`fileKey`) must still be
  those that were checked. Any other `.part-` file stays and is reported. Its contents cannot be checked, because
  the file was never finished, so the read-only permission is what keeps edits out. It cannot stop a change made
  by someone who makes the file writable and then read-only again (`chmod`) within the recorded size, a program
  that had already opened it for writing, or `root`; removing the file removes only that name, so the contents
  stay if the file has another name (a hard link).
- Every `createscript install` also looks at `.createscript/packages/<name>/<version>/` and reports each directory
  that cannot be used: files that do not match the marker (a source or the marker was changed, a file is missing
  or extra, the marker is damaged or missing), a marker that names another package or version, files that no
  longer make the archive named in the marker, symbolic links (reported, never followed), and names that `install`
  does not make. This finds, for example, a package directory that was edited after an installation was
  interrupted and undone. The warning names the directory and the reason and asks you to keep a copy of your
  changes and remove the directory. Nothing is removed, repaired or followed, and the check never makes the
  installation fail (a directory that cannot be read is reported too). Usable directories of versions that
  `createscript.lock` no longer lists are not reported (removing them is planned), and names starting with `.`
  (such as `.DS_Store`) are skipped. A directory that this installation needs still stops it, as before.
- **The temporary file of the journal.** The journal is rewritten (to `committed`) through
  `txn-<id>/journal.tmp`. If the process stopped while writing it, the next install removes that file before
  anything else, but only when it is a regular file of this user (not a link) and is either a complete journal of
  this transaction or the beginning of one (the bytes of a journal follow from its contents, so a file written
  only in part is always the beginning of it); the journal that counts is `transaction.json`. Any other
  `journal.tmp` stops the command with nothing changed, and the first step is to move that file out of the
  project.
- **A missing transaction directory.** If `transaction.json` (state `prepared`) refers to a `txn-<id>` directory
  that does not exist, the command says so and stops; put the directory back if you moved it, or check the two
  files yourself.
- When the installation that runs after a recovery fails (for example, `--offline` and a package not in the cache),
  its message still says that an interrupted installation was finished or undone first.

#### Checking an interrupted installation by hand

When `createscript install` stops with "cannot be finished or undone safely", it has changed nothing. Its message
lists the reason, the files that were left and whether `createscript.json` and `createscript.lock` are the version
before or after the installation. Then:

1. Do what the first step of the message says if it is specific (for example, move a `journal.tmp` out of the
   project, or put back a `txn-<id>` directory that was moved).
2. Compare `createscript.json` and `createscript.lock` with the saved copies in `.createscript/txn-<id>/`
   (`*.old` before the installation, `*.new` after it) and keep the version you want; `createscript manifest
   check` and `createscript lock check` tell whether they are valid.
3. Move `.createscript/transaction.json` and `.createscript/txn-<id>/` out of the project (keep them until you are
   sure) and run `createscript install` again. It checks every package against `createscript.lock` again and
   reports package directories that cannot be used.

Never delete the journal before the two files are checked: while it exists, `run`, `manifest check`,
`lock check` and `pack` refuse to work, so a half-finished update is not used by mistake.

What this protects against: a project that someone else made, with a `.createscript` directory that contains a
journal and saved copies chosen by them, so that the next `createscript install` would replace the
`createscript.json` and `createscript.lock` that you reviewed. Such a journal is not used, because its directories
were not created on your computer at that path. What it does not protect against: a program that runs as your user
and can change the files in the project. It can read the file identities and write a matching journal, together
with saved copies that look like adding or changing a dependency; it could also change `createscript.lock`
directly. The file identities are not a secret or a signature; they only tell a journal written here from one that
came with the files.

### Running installed packages (S8.2 Phase 4)

`createscript run [directory]` checks the installed packages against `createscript.lock` before compiling (see
[Running a project](cli.md#running-a-project)) and gives their sources to the compiler through the existing
`ScopedModuleResolver` (`CreateScript.compileScoped`), so the Core still reads no files and no network. Each
`import` is looked up in the scope of the file that contains it, and package modules get the id
`package/module`, so the same module name in different packages does not collide (see
[Modules from packages](language.md#modules-from-packages)). A program's identity for saving and restoring runs
includes all package sources (`ModuleCompileResult.sourceFingerprint`).

Before compiling, `run` reads each package directory once and checks, in this order: the directory and its files
match the marker exactly (no missing, extra or changed files, no symbolic links or special files); the marker names
the package, version, source and SHA-256 of `createscript.lock`; and the canonical archive made again in memory from
the files that were read (all files except the marker) has the SHA-256 of `createscript.lock`. The sources that are
compiled are the bytes that were checked. Any difference stops the run with exit code 65 before anything is
compiled. The marker alone is therefore not trusted: a change of both the files and the marker in the same way is
found, because the files no longer make the archive in the lock file. `run` does not use the network or the cache
and writes no files.

The lock file is what is trusted: a change of the files, the marker and `createscript.lock` together cannot be told
apart from installing another archive (review changes of `createscript.lock` like changes of code). Running
`createscript install` again after removing `.createscript/packages` checks the archives against the lock file
again.

File systems that add a `._` file next to every new file (macOS does this on FAT and exFAT drives and on some
network drives, to store extended attributes) are not supported: the package directories would no longer hold
exactly the installed files. `createscript install` finds this with a test file before it changes anything and
stops with exit code 73; `run` refuses such files in package directories and names the reason.

Known limits: checks and renames are separate steps, so a program that changes a file in the moment between them
is not always noticed (Java names files by path); a file whose contents and size stay the same after a change
cannot be told apart where the file system has no `fileKey`. Windows has not been tried.

#### Directories that other users can change

Before it changes anything, `createscript install` checks the owner and the permissions of the project root, of
every directory above it up to `/`, and of `.createscript` and every directory below it; `createscript run` does
the same before it uses installed packages. Links are not followed: the directories above the root are those of
its real path, and links inside `.createscript` are left to the other checks. A directory is accepted when it
belongs to you or to `root` and neither its group nor other users can write to it. A directory that others can
write to is accepted only above the project, when it has the sticky bit (like `/tmp` and `/Users/Shared`) and the
next directory on the way belongs to you or to `root`, because then nobody else can rename or remove it. A
group-writable directory counts as writable by others, because the members of the group cannot be checked.
Directories that no one else can enter (no `x` for the group and others) are not looked into further.

When a directory fails the check, the command stops with exit code 73 (`install`) or 65 (`run`), changes
nothing and names the directory and its permissions. It never changes permissions itself: remove the write
permission of others if that is what you want (`chmod go-w <directory>`), or move the project. Directories and
files that `install` creates get `rwxr-xr-x` and `rw-r--r--` (your umask can only remove more), so they pass.

Limits: access control lists (macOS `chmod +a`) cannot be read with the standard Java 21 API, so a directory that
an ACL makes writable for others is not found (macOS home directories carry a `deny delete` ACL by default, which
is harmless). Where the owner and permissions cannot be read, as on file systems without POSIX permissions
(Windows, for example), `install` and `run` with packages stop instead of guessing; installing packages on Windows
is therefore not possible yet. Linux has the same rules but has not been tried. The check makes it impossible for
other users to replace the directories, since only the owner and `root` can change their permissions; it does
not stop programs that run as you, and it does not remove the race described below.

Directories replaced by symbolic links during an installation: Java on macOS (JDK 21) has no way to move a file
relative to a directory that is already open (`SecureDirectoryStream`, `openat` and `renameat` are not available),
so every move names its target by path again. A program running as your user that replaces
`.createscript/packages/<name>` (or another directory on the way) with a symbolic link in the moment between the
check and the move makes the move put the package directory behind the link, outside the project. This cannot be
prevented. What `createscript install` does: it finds this after the move, does not report success, does not
write `createscript.json` or `createscript.lock`, undoes the installation, and does not read, change or remove
anything behind the link (including the package that was moved there; the message says where it may be). In a
test that replaced the directory again and again during 1,200 installations, packages were moved outside the
project 92 times, no installation was reported as successful, and no other file outside the project was changed.
A replacement after the last check (just before the lock file is written) is not noticed by `install`; `run`
refuses symbolic links in `.createscript/packages`. Only programs that can already change the project can do
this; keep projects in directories that only you can write to.

### Still planned

- The official registry reviewed through pull requests (S8.3, [registry.md](registry.md)): the formats, the
  operating tools and the client exist, but the registry repository, its maintainers (at least three are needed)
  and the production key do not yet. Packages keep `source: "github:owner/repo"`, so the manifest, lock file and
  archive formats do not change; packages that are not in the registry can still be installed directly.
- Version ranges and dependency resolution (S8.4).
