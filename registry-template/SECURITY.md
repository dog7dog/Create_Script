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
- **Freezing**: an attacker who blocks updates can keep a client on the last index until it expires.
- **First use**: a client that has never seen an index (or lost its state) cannot tell an old signed index from the
  newest one before it expires.
- **Bugs in a reviewed package.** A review is not a guarantee.
- GitHub itself (its accounts, releases and API) is trusted for everything except the signature.
