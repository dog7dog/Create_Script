# Security

## Reporting

Report a malicious or compromised package, a leaked key or a problem with the registry privately through GitHub's
private vulnerability reporting for this repository (Security → Report a vulnerability), not in a public issue.
Include the package name and version and what you saw. The maintainers answer within 7 days.

For a malicious package the maintainers yank its versions or remove the package, so that `createscript` warns
about it. For a leaked signing key see [SIGNING.md](SIGNING.md).

## What the registry protects against

| Threat | Protection |
|---|---|
| a malicious pull request | the checks run as they are on main, read the pull request only as data, have no secrets, and use actions pinned to commit SHAs; two maintainers other than the author approve |
| a pull request that adds its own workflow to fake the checks | the required checks are accepted only from the registry's GitHub App, which only the trusted `report` job can use; forks cannot change `.github/`; workflows of outside collaborators need approval to run |
| re-running an old check after new commits | every job compares the pull request's current head commit with the commit of the run and stops if they differ |
| a compromised build dependency of the checking tool | the Gradle wrapper is checked against a pinned SHA-256, every dependency against `gradle/verification-metadata.xml` (strict), no build cache is used, and the only job with a write token builds and runs nothing |
| changing a reviewed archive | the SHA-256 of every reviewed version can never change; releases must be immutable; GitHub's digest and the download must both match |
| deleting a version and registering it again with other contents | versions and packages can never be deleted (checked in pull requests and against the published index) |
| a renamed or re-created repository | the repository and owners are recorded with GitHub's numeric IDs |
| names meant to be confused | reserved names, similar names refused, names one letter apart shown to reviewers |
| a maintainer reviewing their own package | owners of a changed package do not count as approvers |
| an old or forged index | the index is signed with Ed25519 outside GitHub; serials only grow; an index expires after 30 days |
| a compromised GitHub account of one maintainer | two approvals by other maintainers; the signing key is not on GitHub, so an index cannot be signed through GitHub alone |

## What it does not protect against

- **Several maintainers acting together**, or the lead together with one other maintainer.
- **A stolen signing key**: whoever has it can sign an index. Clients still refuse changes to versions they have
  already seen. See [SIGNING.md](SIGNING.md).
- **Freezing**: an attacker who blocks updates keeps clients from learning about new removals. Since S8.3 Phase 5
  `createscript` does not fall back to its saved index when the download fails online; it does so only with
  `--offline`, which uses what was saved before (removals published later are not known). An attacker who can
  serve an older, still valid signed index to a client that has no saved state (first use, cleared cache) can
  still hide removals until that index expires.
- **The registry's GitHub App key**: whoever has it can report the required checks. It is kept only in the
  environment `registry-checks`; the App has only "Checks: read and write", and the installation token that the
  workflow uses is revoked at the end of the step.
- **Releases under other names or a changed "latest"**: anyone with write access can create releases or mark
  another one as the latest; settings cannot prevent it (GOVERNANCE.md, "Index tags and releases").
- **Who can create `index-*` tags**: the template allows only the dedicated publishing App (ruleset A, with the
  App as the only bypass actor), and the publishing workflow writes only with that App's token. Whether GitHub
  enforces this has not been tried on GitHub yet (GOVERNANCE.md; docs/registry-github-architecture.md in
  CreateScript). Whoever has the publishing App's key can create `index-*` tags and releases, though not sign an
  index.
- **First use**: a client that has never seen an index (or lost its state) cannot tell an old signed index from the
  newest one before it expires.
- **Bugs in a reviewed package.** A review is not a guarantee.
- GitHub itself (its accounts, releases and API) is trusted for everything except the signature.
