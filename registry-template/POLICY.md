# Registry policy

## What a review means

Two maintainers read the package at the registered SHA-256 and agree that its name and description are honest,
that it does what it says, and that it does not try to mislead or harm its users. CreateScript programs cannot
reach files, the network or the operating system, which limits what a package can do, but a reviewed package can
still be wrong. A review is not a guarantee.

Reviewers refuse packages that:

- impersonate another package, project, person or organization, or use a name meant to be confused with one;
- hide what they do, or contain content that is illegal or abusive;
- have no license that allows use (`license` must be an SPDX expression);
- were published without immutable releases, or whose archive differs from the release.

## Names

- The S8.2 package name rules apply (lowercase letters, digits, single hyphens, at most 64 characters, no Windows
  reserved names).
- Names in `reserved-names.json` cannot be registered, nor names that look the same: names are compared after
  removing hyphens and reading `0` as `o` and `1` as `l` (`create-script` is the reserved `createscript`).
- Two packages can never have such similar names. Names one letter apart (`vector-maths`) are shown to the
  reviewers, who decide.
- First come, first served, but names are not for holding: a package must have at least one real version. Names
  that are clearly taken to block or mislead others can be removed.
- Disputes (trademarks, abandoned packages, impersonation) are opened as an issue. The maintainers decide by the
  same rule as pull requests (two maintainers other than those involved). A name is never moved to another
  source: a new owner registers a new name, or the owners agree to add owners.

## Versions

- Versions are exact SemVer without build metadata. A registered version never changes and is never deleted.
- **Yanking** (`yanked` with a reason) means: do not use this version for new installations. Projects that have it
  in their lock file keep working. Owners or maintainers may yank.
- **Removing** a package (`removed` with a reason) is for security problems, abuse or legal reasons. The versions
  stay in the index so that `createscript` can warn about them. Versions that depend on a removed package must be
  yanked in the same pull request.
- Taking back a yank or a removal needs the agreement of the maintainers in the pull request.

## Dependencies

Reviewed packages depend only on reviewed packages, by exact version, from the sources recorded in the registry.
Every reviewed version together with its dependencies must form a valid lock file (no cycles, no two versions of one
name). Version ranges are planned for a later stage (S8.4).
