# Registering and updating packages

> The registry is closed until it has three maintainers. Until then, pull requests that change `packages/`
> fail the checks and wait.

## Before the pull request

1. Make the archive with `createscript pack` (the canonical `createscript-package.zip`).
2. Turn on **immutable releases** for your repository (Settings → General → Releases). Releases made before that
   are not immutable and are refused.
3. Publish a release with the tag `v<version>` and the asset `createscript-package.zip`. Do not change it
   afterwards: the registry records its SHA-256, and any other file is refused forever.
4. Note the SHA-256 that GitHub shows for the asset (`sha256:…`), and your repository's numeric ID and your own
   numeric ID (`gh api repos/OWNER/REPO --jq .id`, `gh api users/LOGIN --jq .id`).

## Registering a new package

Add `packages/<first letter>/<name>.json` (for example `packages/v/vector-math.json`) in the canonical form shown in
`docs/registry.md`: two-space indentation, the fields in the fixed order, versions and dependency names sorted,
one final newline.

- You must be one of the `owners`. If the repository belongs to a user, that user must be one of the owners. If it
  belongs to an organization, say in the pull request why you may publish for it.
- `review.pullRequest` is the number of your pull request. `review.reviewers` lists the two maintainers who will
  review it (ask in the pull request; they must not be you or an owner). `review.date` is the date of the review.
- Dependencies must be packages and versions that are already in the registry, from the same sources as in your
  archive's `createscript.json`.

A pull request changes only files in `packages/`. Changes to anything else are separate maintenance pull
requests.

## Updating a package

Only an owner can add a version or change the owners or the source. Add the version to `versions` with the same
release steps as above. A reviewed version can never change: not its SHA-256, its dependencies or its review
record, and it cannot be deleted. To withdraw a version, add `yanked` with a reason (owners or maintainers). To
remove a whole package, add `removed` (see [POLICY.md](POLICY.md)).

## What the checks do

`validate` (on every push to the pull request) reads the pull request as data and never runs anything from it:

- strict JSON, canonical form, file names, unknown fields, limits;
- names, reserved and similar names, versions, dependencies (only reviewed packages, no cycles, no two versions of
  one name), yanked and removed rules;
- reviewed SHA-256 values and dependencies have not changed, and nothing was deleted;
- the pull request author is an owner (or a maintainer, for yanking and removal);
- for each new version: the GitHub repository's numeric ID and current name, the release is immutable, GitHub's
  digest of `createscript-package.zip` is the registered SHA-256, the downloaded file has that SHA-256, and it is a
  canonical package archive with the registered name, version and dependencies.

`approvals` checks the approvals (see [GOVERNANCE.md](GOVERNANCE.md)). Reviews do not start it again; after the
approvals a maintainer adds the label `recheck`, which starts a run for the current head commit (re-running an old
run fails on purpose when the pull request has newer commits).

The results appear as the checks `registry/validate` and `registry/approvals`, reported by the registry's GitHub
App. Checks with these names from anywhere else do not count. A pull request from a fork cannot change `.github/`
(workflows and CODEOWNERS).
