# Governance: co-maintainers

## Roles

- **Lead** (`lead` in `maintainers.json`, currently dog7dog): responsible for the registry. Approves maintenance
  changes and changes of package owners, keeps the repository settings, coordinates signing.
- **Maintainers** (`maintainers` in `maintainers.json`, by GitHub login **and numeric ID**): review pull requests.
  Only maintainers have write access to the repository.
- **Signers**: maintainers who hold a signing key (see [SIGNING.md](SIGNING.md)).

## Rules

1. Every pull request needs **two approvals by maintainers other than its author**, on its latest commit.
2. Every pull request must pass the checks `registry/validate` and `registry/approvals`, which the registry's own
   GitHub App reports on the pull request's latest commit (see "Checks" below).
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
| approvers are maintainers | only if exactly the maintainers have write access; code owner review (CODEOWNERS lists every maintainer) | ✓ (numeric IDs in `maintainers.json`) | |
| approval of the latest commit | ✓ (dismiss stale approvals, require approval of the last push) | ✓ (and every job refuses a run for an older commit) | |
| owners of the changed package do not count | ✗ | ✓ | |
| other conflicts of interest | ✗ | ✗ | ✓ |
| lead for maintenance and owner changes | ✗ (CODEOWNERS lists every maintainer, so that the lead's own pull requests can be approved) | ✓ | |
| a maintainer requested changes | ✓ (with required reviews, a change request by someone with write access blocks merging) | ✓ (any maintainer's latest review) | |
| registry closed below three maintainers | ✗ | ✓ (and `validate`, `publish`) | |
| required checks come from the registry's checks, not from a pull request's own workflow | ✓ (required status checks with the `integration_id` of the registry's GitHub App) | | |
| a fork does not change workflows or CODEOWNERS | ✗ | ✓ (`validate` refuses `.github/` changes from forks) | |
| the approvals still hold when an index is published | | ✓ (`publish` checks them again on the merged pull request) | |
| no direct push, no force push, no deletion of `main` | ✓ (rulesets: pull request, non-fast-forward, deletion) | | |
| no bypass by administrators | ✓ (rulesets with no bypass actors) — but an administrator can still change or delete the ruleset itself | | ✓ (changes to settings are announced in an issue first) |
| releases are immutable | ✓ (immutable releases, a repository setting) | `publish` checks each release | |
| a second approval before publishing | ✓ (environment `publish` with required reviewers) | | |
| recorded reviewers are the approvers | ✗ | ✓ | |

**GitHub settings cannot enforce**: who counts as a maintainer apart from write access, conflicts of interest,
the minimum number of maintainers, that the approvals check runs again after a review (a maintainer adds the label
`recheck`), and that the repository owner does not change the settings. Owners of a personal repository always have full
control; moving the registry to an organization with several owners reduces this, but does not remove it.

## Checks

A check is only worth something if a pull request cannot make it itself. Every job of a GitHub Actions workflow
creates a check named after the job, reported by the GitHub Actions App, and a pull request (also from a fork) can
add a workflow with a job of any name. A required check given only by its name (`validate`) can therefore be
satisfied by such a job (finding P4-H1 of the S8.3 Phase 4 audit). So:

- The workflow `validate` runs three jobs. `validate` and `approvals` read the pull request as data, with no
  secrets and a read-only token. `report` never sees the pull request: it creates the check runs
  `registry/validate` and `registry/approvals` with their results, **as the registry's own GitHub App**, on the
  pull request's head commit.
- The ruleset requires these two checks **from that App** (`integration_id`). A check with the same name from any
  other source, including every GitHub Actions job, does not count. `createscript-registry-tool check-settings`
  refuses a ruleset whose required checks have no `integration_id` or the GitHub Actions App's.
- Every job first reads the pull request's current head commit from the API and stops if the run is for an older
  commit, so re-running an old run never checks or reports an old commit.
- Reviews do not start `pull_request_target`; after approving, a maintainer adds the label `recheck` (or removes
  and adds it again), which starts a run for the current head commit.

**The GitHub App "CreateScript Registry Checks"** (created by the lead in step 3 of the bootstrap): permissions
"Checks: read and write" and "Pull requests: read" only, installed only on the registry repository, no webhook.
Its ID is the repository variable `REGISTRY_CHECKS_APP_ID` and the `integration_id` of both required checks; its
private key is the secret `REGISTRY_CHECKS_APP_KEY` of the environment `registry-checks`, which only the `report`
job uses (deployment branches: `main` only, no reviewers). The key is rotated like any GitHub App key; whoever has
it can report checks, so it is kept only in that environment.

## GitHub settings (proposal, to be applied when the repository is created)

Before applying, run `createscript-registry-tool check-settings --ruleset github-settings/main-ruleset.json`
after filling in the App's ID; it must print `OK`.

- **Rulesets**: `github-settings/main-ruleset.json` for `main` (pull requests with 2 approvals, code owner review,
  stale approvals dismissed, approval of the last push, resolved threads, required checks `registry/validate` and
  `registry/approvals` **from the registry's GitHub App** on the latest `main`, linear history, no force push, no
  deletion, **no bypass actors**) and `github-settings/index-tags-ruleset.json` (tags `index-*` cannot be moved or
  deleted). The value `0` of `integration_id` in the template is a placeholder that GitHub and `check-settings`
  refuse.
- **CODEOWNERS**: one rule, `*` followed by every maintainer (`createscript-registry-tool` refuses anything else).
  GitHub never counts an author's own approval, so with the lead as the only code owner the lead's pull requests
  could never be merged (finding P4-M1). With every maintainer as a code owner, any pull request can get the code
  owner approval from another maintainer; that the lead takes part in maintenance is checked by `approvals`.
- **Collaborators**: only maintainers, with the `write` role; the lead as admin. No deploy keys.
- **Actions**: allow only actions pinned to a full commit SHA; workflow permissions read-only by default;
  **require approval to run workflows for all outside collaborators** (not only first-time contributors).
- **Environment `registry-checks`**: the App's private key; deployment branches `main` only.
- **Environment `publish`**: required reviewers = the maintainers, "prevent self-review" on, only `main` can deploy.
- **Releases**: immutable releases on.
- **Private vulnerability reporting** on.

## Adding and removing maintainers

Adding: a maintenance pull request (from a branch of the registry repository) adds the person to `maintainers.json`
(login and numeric ID) and to the `*` line of `.github/CODEOWNERS`. The new maintainer comments that they accept. After the merge the lead
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
2. The lead adds themselves and the first two maintainers to `maintainers.json` with their numeric IDs, and all three
   to `.github/CODEOWNERS`, each of them confirming in an issue. These are the only commits made without two
   approvals; they are listed in that issue. Until the lead is in `maintainers.json`, the `approvals` check refuses
   every pull request.
3. The lead creates the GitHub App and the environments, fills in the App's ID in the ruleset, checks it with
   `check-settings`, applies the rulesets, and sets `TOOL_COMMIT` and `WRAPPER_JAR_SHA256` in both workflows to a
   reviewed CreateScript commit and the SHA-256 of its `gradle/wrapper/gradle-wrapper.jar` (compared with the
   checksum that Gradle publishes for that wrapper version), through a pull request that now needs the approvals.
4. The signing key is created (SIGNING.md) and its public key added to `keys/trusted-keys.json` and to
   `createscript` itself.
5. With three maintainers the registry is open.
