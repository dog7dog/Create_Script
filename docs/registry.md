# CreateScript-Registry (S8.3)

> **In development (Phases 1 and 2 of S8.3).** This page describes the file formats and the checks in the library
> `createscript-project` (Phase 1) and the operation of the registry with `createscript-registry-tool` and the
> template in `registry-template/` (Phase 2, see [Operating the registry](#operating-the-registry-phase-2)).
> There is no registry repository yet, no signing key, no published index, and `createscript` does not use the
> registry. `createscript install <name>@<version>` comes in a later phase. Nothing here has been tried against
> GitHub.

CreateScript-Registry will be a GitHub repository in which each package name is mapped, through reviewed pull
requests, to a GitHub source and to the SHA-256 of each reviewed version. A signed `index.json` built from it
tells `createscript` which archives were reviewed.

The registry does not change any S8.2 format: `createscript.json`, `createscript.lock` and
`createscript-package.zip` stay as they are, and `source` stays `github:owner/repo`. A registry entry is only a
reviewed record of what an S8.2 lock file already contains (name, version, source, SHA-256, dependencies).

Stages: S8.3 is the registry (names, reviewed hashes, the signed index). S8.4 is dependency management (version
ranges, resolution, `update`).

## Repository layout

```text
registry.json                    settings (index validity, signing key IDs)
reserved-names.json              names nobody can register
packages/<first letter>/<name>.json   one file per package, e.g. packages/v/vector-math.json
```

Every file must be exactly in the canonical form that `RegistryFiles.write…` produces: 2-space indentation, LF
line ends, one final newline, fields in a fixed order, names and versions sorted. A file in any other form is
refused (`CS_REG_NOT_CANONICAL`, with the first line that differs), so a pull request cannot hide content in
unusual formatting. All the JSON rules of `createscript.json` apply as well (strict RFC 8259, duplicate keys
rejected at any depth, UTF-8 without a byte order mark, integers only, at most 16 levels).

### `registry.json`

```json
{
  "registryVersion": 1,
  "indexValidityDays": 30,
  "signingKeyIds": [
    "createscript-registry-2026-1"
  ]
}
```

`indexValidityDays` is 1–90. `signingKeyIds` lists 1–4 key IDs (lowercase letters and digits with single hyphens,
at most 64 characters). The public keys themselves are not taken from the repository: clients have them built in
(Phase 2/3).

### `reserved-names.json`

```json
{
  "registryVersion": 1,
  "names": {
    "core": "the CreateScript core",
    "createscript": "the CreateScript project"
  }
}
```

At most 4096 names, each a valid package name.

### Package files

```json
{
  "registryVersion": 1,
  "name": "vector-math",
  "source": "github:alice/vector-math",
  "repositoryId": 123456789,
  "owners": [
    {
      "login": "alice",
      "id": 1001
    }
  ],
  "description": "2D and 3D vector helpers",
  "license": "MIT",
  "versions": {
    "1.2.0": {
      "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
      "dependencies": {
        "numbers": "3.0.0"
      },
      "review": {
        "pullRequest": 42,
        "reviewers": [
          "dog7dog"
        ],
        "date": "2026-11-20"
      }
    }
  }
}
```

| Field | Rule |
|---|---|
| `name` | the S8.2 package name rules (lowercase, single hyphens, at most 64 characters, no Windows reserved names). The file must be at `packages/<first letter>/<name>.json` (`CS_REG_ENTRY_PATH_MISMATCH`) |
| `source` | `github:owner/repo`, as in S8.2 |
| `repositoryId`, `owners[].id` | GitHub's numeric IDs, 1 to 2^53−1. They do not change when an account or a repository is renamed, so a repository deleted and created again under the same name can be told apart |
| `owners` | 1–16 GitHub logins with their IDs, no duplicates (case-insensitive) |
| `description` | 1–200 characters, see [Text](#text) |
| `license` | an SPDX-style expression, as in S8.2 |
| `removed` | optional `{"reason", "date"}`: the package was removed (for example for security reasons) |
| `versions` | 1–1000 versions. Exact SemVer as in S8.2, **without build metadata** (`1.0.0+a` and `1.0.0+b` have the same precedence and are easy to confuse) |
| `sha256` | the SHA-256 of the reviewed `createscript-package.zip` (lowercase hex) |
| `dependencies` | at most 256, name → exact version. Ranges are S8.4 |
| `review` | the pull request number, 1–16 reviewers (GitHub logins) and the date `YYYY-MM-DD` |
| `yanked` | optional `{"reason", "date"}`: the version was withdrawn |

### Text

Descriptions and reasons may use any Unicode letters, but not characters that can make text look like something
else: control characters, format characters (such as the bidirectional overrides U+202E and U+2066–U+2069 and
U+200B), line and paragraph separators, unpaired surrogates, private use characters and noncharacters. Text must be
in Unicode normalization form NFC (so the same visible text is always the same bytes) and must not start or end
with a space. The character classes come from Java 21 (Unicode 15).

## Rules across packages

Checked when the index is built from the repository and again whenever an index is read:

- **Similar names**: two names whose skeletons are equal cannot both exist (`CS_REG_SIMILAR_NAME`). The skeleton
  removes hyphens and maps `0` to `o` and `1` to `l`, so `vector-math`, `vectormath` and `vect0r-math` are one
  name. A name whose skeleton equals the skeleton of a reserved name is refused (`CS_REG_RESERVED_PACKAGE_NAME`).
  `RegistryRules.similarNames` also finds names one edit away (`vector-maths`, `vetcor-math`); these are not
  errors, but are meant to be shown to reviewers.
- **One source, one repository, one package**: sources are compared without regard to case (GitHub names are not
  case-sensitive), and `repositoryId` must be unique.
- **Reviewed packages depend only on reviewed packages**: every dependency must be a name and version in the
  registry (`CS_REG_UNKNOWN_DEPENDENCY`), and a package cannot depend on itself.
- **Removed packages**: only yanked versions, or versions of removed packages, may depend on a removed package
  (`CS_REG_DEPENDENCY_ON_REMOVED`). Removing a package therefore means yanking what depends on it in the same
  pull request. Yanked versions may still be depended on, so projects that have them in their lock file keep
  working.
- **Installable on its own**: there is no dependency cycle between (name, version) pairs, and the dependencies of
  one version, followed all the way, never contain two versions of one name and at most 4096 packages. So every
  reviewed version and its dependencies form a valid S8.2 lock file (`RegistryIndex.lockedClosure`).

## The index: `index.json`

The index is built from the repository by `RegistryIndexes.build` (Phase 2 will run it in a workflow after each
merge) and published together with its signature as release assets. It holds what clients need:

```json
{"expires":"2026-12-20T10:00:00Z","generated":"2026-11-20T10:00:00Z","indexVersion":1,"packages":{"vector-math":{"description":"2D and 3D vector helpers","license":"MIT","repositoryId":123456789,"source":"github:alice/vector-math","versions":{"1.2.0":{"dependencies":{"numbers":"3.0.0"},"pullRequest":42,"sha256":"0123…"}}}},"serial":57}
```

(`numbers` would of course also be in `packages`; the example is shortened.)

- `serial` (1 to 2^53−1) grows by one with every published index.
- `generated` and `expires` are UTC times to the second, `YYYY-MM-DDTHH:MM:SSZ` (no fractions, offsets or leap
  seconds). `expires` is after `generated`, and at most 90 days after it.
- Owners and reviewers stay in the repository; the index has the pull request number.
- Limits: 16 MiB, 20,000 packages, 1000 versions per package, 200,000 versions in all, 256 dependencies per
  version. The limits are checked before the values are read.

### Canonical form of the index

The bytes of `index.json` are the canonical JSON of RFC 8785 (JSON Canonicalization Scheme), restricted to the
values the index uses:

- UTF-8, no byte order mark, no whitespace outside strings, no final newline.
- Object keys sorted by UTF-16 code units (for the ASCII keys of the index this is plain byte order).
- Strings escape only `"`, `\` and U+0000–U+001F (as `\b \t \n \f \r` or `\u00xx` with lowercase hex); every
  other character, including `/`, DEL and U+2028, is written as UTF-8.
- Integers in decimal, only within ±(2^53−1); there are no other numbers.
- Unpaired surrogates and invalid UTF-8 cannot be written and are refused when read.

A reader refuses an index whose bytes differ from the canonical form of its own content
(`CS_REG_NOT_CANONICAL`), even when the content is valid. Hence the same content always has the same bytes and the
same SHA-256, and two indexes can be compared by their SHA-256.

## Signatures: `index.json.sig`

Every index must be signed with Ed25519 (RFC 8032). Verification uses only Java 21's own implementation (SunEC,
module `jdk.crypto.ec`, already in the runtime that the packages include); no cryptography library is added.

```json
{"signatureVersion":1,"signatures":[{"keyId":"createscript-registry-2026-1","signature":"<88 characters of Base64>"}]}
```

- The file is canonical JSON too (at most 4 KiB). A signature is 64 bytes in standard Base64 with padding; other
  spellings of the same bytes are refused.
- **What is signed**: the ASCII text `CreateScript-Registry index signature v1`, a zero byte, the key ID, a zero
  byte, and then the exact bytes of `index.json`. The prefix separates these signatures from anything else the key
  might sign, and the key ID binds the signature to the key it claims.
- **Trusted keys** are 32-byte Ed25519 public keys with a key ID, built into the client. There are no production
  keys yet: generating, keeping and using the private key is designed in Phase 2. The tests create throwaway keys
  in memory each time they run (key IDs starting with `test-only-`) and never store them.
- **Key rotation**: an index may carry up to 4 signatures. A client accepts it when at least one signature is by a
  key it trusts and every signature by a key it trusts is valid; signatures by keys it does not know are ignored.
  To rotate, the registry signs with the old and the new key, a client release trusts both, and later the old key is
  dropped.

The index is refused when:

| Situation | Code |
|---|---|
| no `index.json.sig`, or it is empty | `CS_REG_SIGNATURE_MISSING` |
| the signature file cannot be read | `CS_REG_SIGNATURE_MALFORMED` (with the reason) |
| no signature is by a trusted key | `CS_REG_SIGNATURE_UNKNOWN_KEY` |
| a signature by a trusted key does not match (a changed index, a changed or truncated signature, a signature made for another key ID, or a signature over the bare index without the prefix) | `CS_REG_SIGNATURE_INVALID` |

## Accepting an index

`IndexVerifier.verify` takes the bytes of `index.json` and `index.json.sig`, the index accepted last time (if any)
and the current time, and checks in this order:

1. The size (at most 16 MiB).
2. The signature, **before** the JSON of the index is read, so unsigned input never reaches the parser.
3. The index itself: canonical form, values, limits and the rules across packages.
4. Time: an index whose `expires` has passed is refused (`CS_REG_INDEX_EXPIRED`); one whose `generated` is more
   than one day in the future is refused (`CS_REG_INDEX_NOT_YET_VALID`). An expired index can be accepted only for
   showing cached information while offline (`allowExpired`).
5. The comparison with the index accepted before (`IndexHistory.compare`):

| Refused | Code |
|---|---|
| a smaller `serial` (an old signed index sent again) | `CS_REG_INDEX_ROLLBACK` |
| the same `serial` with different bytes | `CS_REG_INDEX_SERIAL_REUSED` |
| an earlier `generated` | `CS_REG_INDEX_TIME_ROLLBACK` |
| a package or a version that is no longer there | `CS_REG_PACKAGE_DISAPPEARED`, `CS_REG_VERSION_DISAPPEARED` |
| a reviewed version with another SHA-256 | `CS_REG_SHA256_CHANGED` |
| a reviewed version with other dependencies | `CS_REG_DEPENDENCIES_CHANGED` |
| a package with another `repositoryId` | `CS_REG_REPOSITORY_CHANGED` |

Allowed: new packages and versions, yanking and removing, other descriptions, and another `source` with the same
`repositoryId` (a renamed repository). Because packages and versions can never disappear, a version cannot be
deleted in one index and added again with other contents in the next. The same comparison of the repository files
before and after a pull request is `IndexHistory.compareEntries` (for the Phase 2 workflow).

A single index cannot prove that reviewed SHA-256 values never changed; only the comparison with an earlier
index can. The registry repository's history and its required checks (Phase 2) keep the record.

### Review status

`RegistryIndex.status(name, version, source, sha256)` classifies a package (for example one entry of a lock file):
`REVIEWED`, `YANKED`, `REMOVED`, `VERSION_NOT_REVIEWED`, `NOT_IN_REGISTRY`, `NAME_USED_BY_ANOTHER_SOURCE`,
`SHA256_MISMATCH` and `NAME_MISMATCH`. Phase 3 will refuse `SHA256_MISMATCH` and `NAME_MISMATCH`, warn about
packages that are not reviewed and still allow them when given directly as `github:owner/repo`, and show the
status of every package.

## Security limits

These are known limits of the design, not bugs to be fixed later:

- **First use and lost state.** Rollback protection compares with the index accepted before. On the first fetch,
  or after the local state was deleted (a new machine, a cleared cache, a user or program that can write the cache),
  a client cannot tell an old signed index from the newest one. Such an index is still signed and still checked,
  and an attacker can only send one that has not yet expired, so the window is at most `indexValidityDays` (at
  most 90 days, 30 by default). The local state is protected only by the file permissions of the user.
- **Freezing.** An attacker who can block updates can keep a client on the last index until it expires; after that
  the client refuses it (or shows it as expired when offline).
- **The signing key.** Whoever has the private key can publish any index. Protecting it is Phase 2. A stolen key
  cannot change what a client already accepted (SHA-256 values and dependencies of earlier versions), but it can
  add versions and packages, so the key must be replaced (a client release with a new key) if it leaks.
- **Clocks.** Expiry uses the client's clock. A clock set far back lets an expired index pass; a clock set ahead
  makes current indexes look expired.
- **Review is not proof of safety.** A review says that maintainers read the package at that SHA-256. CreateScript
  programs have no access to files, the network or the operating system, which limits what a package can do, but a
  reviewed package can still be wrong.
- **Similar names** catch hyphens, `0`/`o` and `1`/`l`; other confusable names rely on the review.
- **Not in Phase 1**: downloading the index, storing the accepted index, the registry repository and its
  workflows, the production key, and any command. Linux, Windows and real GitHub downloads have not been tried for
  any of it.

## Operating the registry (Phase 2)

`registry-template/` is the future repository `CreateScript-Registry`: its files (`registry.json`,
`reserved-names.json`, `maintainers.json`, `keys/trusted-keys.json`, `packages/`, `signed/`), its documents
(`README.md`, `POLICY.md`, `CONTRIBUTING.md`, `GOVERNANCE.md`, `SIGNING.md`, `SECURITY.md`), `CODEOWNERS`, two
workflows and the proposed rulesets in `github-settings/`. It stays in the CreateScript repository until the
registry repository is created. GitHub runs workflows only from a repository's root `.github/workflows/`, so the
template's workflows never run here; in addition, both refuse to run while `TOOL_COMMIT` is all zeros. They can be
tried only explicitly, with the tests (`./gradlew :createscript-registry-tool:test`) or by running the tool by hand.

`createscript-registry-tool` (Gradle module of the same name, not part of the `createscript` packages) implements
every check:

| Command | Used by | Does |
|---|---|---|
| `check-repo` | anyone | reads the repository strictly and applies the rules across packages |
| `validate-pr` | `validate.yml` | the checks of a pull request (below) |
| `check-approvals` | `validate.yml` | the approvals that GitHub settings cannot check (`registry-template/GOVERNANCE.md`) |
| `build-index` | a signer | builds the next index from the repository (serial = published + 1) and lists the changes |
| `sign` | a signer | signs locally; in this phase **only keys whose ID starts with `test-only-`** |
| `verify-index` | anyone | verifies a signed index as a client would |
| `publish-check` | a signer, `validate.yml`, `publish.yml` | the checks before publishing (below) |
| `check-release` | `publish.yml` | the published release is immutable and its digests match |

`maintainers.json` (new in Phase 2) holds the lead, `minimumMaintainers` (3), `requiredApprovals` (2) and the
maintainers by login and numeric ID, in canonical form. With fewer than `minimumMaintainers` maintainers the
registry is closed: package pull requests and publishing are refused.
`keys/trusted-keys.json` holds up to 4 public keys (key ID and 32 bytes in Base64), each listed in
`signingKeyIds`; it is used only by `publish-check`, because clients have their own built-in keys.

### Pull request checks (`validate-pr`)

The pull request is read as data from a checked-out tree (symbolic links and non-regular files are refused; nothing
in it is run). A pull request changes one kind of files only: `packages/`, `signed/`, or the other files
(maintenance). For every kind the rules across packages are checked. For package changes:

- the registry is open; reviewed SHA-256 values, dependencies, review records and repository IDs did not change;
  nothing was deleted;
- a new package: the author (by numeric ID) is an owner; for a user's repository that user is an owner; for an
  organization's repository the reviewers are asked to confirm; names one edit away from existing ones are shown;
- an update: only owners add versions or change owners or the source; owners or maintainers may yank, remove or
  change the description;
- each new version: `review.pullRequest` is this pull request, the reviewers are maintainers who are neither the
  author nor owners, at least `requiredApprovals` of them, the date is not in the future, and it is not yanked;
  GitHub reports the repository's numeric ID and current name, the release `v<version>` is immutable, GitHub's digest
  of `createscript-package.zip` is the registered SHA-256, the downloaded file has that SHA-256, and it is a
  canonical package archive (`createscript pack`) with the registered name, version and dependencies, whose sources
  are the registry's sources.

GitHub is read through `GitHubReleases` (fixed hosts, limits, `repositoryInfo` for the numeric IDs) without a
token. The tests use an in-memory GitHub instead.

### Publishing checks (`publish-check`)

The registry is open; there are trusted keys and none of them is a test key (except in the tests); the signature is
valid; compared with the published index there is no rollback, no reused serial, no changed SHA-256 or dependency
and nothing removed; the serial is exactly the next one; and the signed index is byte for byte the index built from
the repository at that commit. The signing and publishing procedure, key rotation, and what to do when a key is
lost or leaks are in `registry-template/SIGNING.md`.

