# Governance: co-maintainers

## Roles

- **Lead** (`lead` in `maintainers.json`, currently dog7dog): responsible for the registry. Approves maintenance
  changes and changes of package owners, keeps the repository settings, coordinates signing.
- **Maintainers** (`maintainers` in `maintainers.json`, by GitHub login **and numeric ID**): review pull requests.
  Only maintainers have write access to the repository.
- **Signers**: maintainers who hold a signing key (see [SIGNING.md](SIGNING.md)).

## Rules

1. Every pull request needs **two approvals by maintainers other than its author**, on its latest commit.
2. Every pull request must pass the checks `validate` and `approvals`.
3. Owners of a package that a pull request changes do not count as approvers for it (**conflict of interest**). A
   maintainer who has another conflict (works for the owner, has a dispute with them) says so in the pull request and
   does not approve.
4. Maintenance changes (anything outside `packages/` and `signed/`) and changes of package owners also need the
   lead, as author or approver.
5. A pull request changes one kind of files only: packages, the signed index, or maintenance files.
6. The registry needs **at least three maintainers** (`minimumMaintainers`). Below that it is **closed**: pull
   requests that change packages or publish an index fail, and no index is published. Maintenance pull requests
   (such as adding maintainers) need every other maintainer and the lead.
7. Nobody pushes to `main` directly, force-pushes or deletes it, and nobody, administrators included, bypasses
   these rules.

## Which rule is enforced where

| Rule | GitHub settings | `approvals` check | Only by people |
|---|---|---|---|
| two approvals, not by the author | ✓ (required approvals: 2; authors cannot approve) | ✓ | |
| approvers are maintainers | only if exactly the maintainers have write access | ✓ (numeric IDs in `maintainers.json`) | |
| approval of the latest commit | ✓ (dismiss stale approvals, require approval of the last push) | ✓ | |
| owners of the changed package do not count | ✗ | ✓ | |
| other conflicts of interest | ✗ | ✗ | ✓ |
| lead for maintenance and owner changes | partly (CODEOWNERS: `*` → lead) | ✓ | |
| a maintainer requested changes | ✓ (with required reviews, a change request by someone with write access blocks merging) | ✓ (any maintainer's latest review) | |
| registry closed below three maintainers | ✗ | ✓ (and `validate`, `publish`) | |
| required checks | ✓ (rulesets: required status checks) | | |
| no direct push, no force push, no deletion of `main` | ✓ (rulesets: pull request, non-fast-forward, deletion) | | |
| no bypass by administrators | ✓ (rulesets with no bypass actors) — but an administrator can still change or delete the ruleset itself | | ✓ (changes to settings are announced in an issue first) |
| releases are immutable | ✓ (immutable releases, a repository setting) | `publish` checks each release | |
| a second approval before publishing | ✓ (environment `publish` with required reviewers) | | |
| recorded reviewers are the approvers | ✗ | ✓ | |

**GitHub settings cannot enforce**: who counts as a maintainer apart from write access, conflicts of interest,
the minimum number of maintainers, that the approvals check runs again after a review (a maintainer must re-run it),
and that the repository owner does not change the settings. Owners of a personal repository always have full
control; moving the registry to an organization with several owners reduces this, but does not remove it.

## GitHub settings (proposal, to be applied when the repository is created)

- **Rulesets**: `github-settings/main-ruleset.json` for `main` (pull requests with 2 approvals, code owner review,
  stale approvals dismissed, approval of the last push, resolved threads, required checks `validate` and
  `approvals` on the latest `main`, linear history, no force push, no deletion, **no bypass actors**) and
  `github-settings/index-tags-ruleset.json` (tags `index-*` cannot be moved or deleted).
- **Collaborators**: only maintainers, with the `write` role; the lead as admin. No deploy keys.
- **Actions**: allow only actions pinned to a full commit SHA; workflow permissions read-only by default;
  require approval to run workflows for first-time contributors.
- **Environment `publish`**: required reviewers = the maintainers, "prevent self-review" on, only `main` can deploy.
- **Releases**: immutable releases on.
- **Private vulnerability reporting** on.

## Adding and removing maintainers

Adding: a maintenance pull request adds the person to `maintainers.json` (login and numeric ID) and to the
`packages/` line of `.github/CODEOWNERS`. The new maintainer comments that they accept. After the merge the lead
gives them write access and adds them to the reviewers of the `publish` environment.

Removing (stepping down, inactivity for 6 months, or a decision of the other maintainers): the same pull request
in reverse; write access and environment access are removed right after the merge. A maintainer is never removed
by their own vote, and the lead cannot be removed without the agreement of all other maintainers. If this would
leave fewer than three maintainers, the registry is closed until new maintainers join.

If a maintainer's account may be compromised, the lead removes their write access first and opens the pull request
afterwards.

## Bootstrap

GitHub cannot require two approvals before there are two other maintainers. The registry is therefore set up in
this order, and stays closed until step 5:

1. The lead creates the repository from this template (without the settings yet) and enables immutable releases
   and private vulnerability reporting.
2. The lead adds themselves and the first two maintainers to `maintainers.json` with their numeric IDs, each of them
   confirming in an issue. These are the only commits made without two approvals; they are listed in that issue.
3. The lead applies the rulesets and the environment, and sets `TOOL_COMMIT` in both workflows to a reviewed
   CreateScript commit (through a pull request that now needs the approvals).
4. The signing key is created (SIGNING.md) and its public key added to `keys/trusted-keys.json` and to
   `createscript` itself.
5. With three maintainers the registry is open.
