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

**The GitHub App "CreateScript Registry Checks"** (created by the lead in step 3 of the bootstrap):

- **Repository permissions: "Checks: read and write" only** (GitHub adds "Metadata: read" to every App). It has no
  other repository, organization or account permission, no webhook and no user authorization. It reads nothing:
  the pull request's head commit is read with the job's own `GITHUB_TOKEN`, not with the App.
- **Installed only on the registry repository** ("Only select repositories"); it can be installed only on this
  account. The workflow additionally asks GitHub for an installation token limited to this repository and to
  `checks: write`, masks it at once, uses it only to create the two check runs, and revokes it
  (`DELETE /installation/token`) when the step ends, also after an error. If the revocation fails, a warning
  without any secret is printed; the token then expires by itself within about an hour.
- Its ID is the repository variable `REGISTRY_CHECKS_APP_ID` and the `integration_id` of both required checks; its
  private key is the secret `REGISTRY_CHECKS_APP_KEY` of the environment `registry-checks`, which only the `report`
  job uses (deployment branches: `main` only, no reviewers). The key is rotated like any GitHub App key; whoever has
  it can report checks, so it is kept only in that environment.
- **Changing the App's permissions or installation** is a maintenance change: it is proposed in an issue, approved
  like a maintenance pull request (two maintainers and the lead), and the installation owner then accepts the new
  permissions on GitHub. Nobody adds a permission "for later".

The App is separate from the workflows' own `GITHUB_TOKEN`, whose permissions are set per job:

| Workflow / job | `GITHUB_TOKEN` permissions | Secrets |
|---|---|---|
| `validate` / `validate`, `approvals` | `contents: read`, `pull-requests: read` | none |
| `validate` / `report` | `pull-requests: read` (to read the current head commit) | the App key (environment `registry-checks`) |
| `publish` / `check`, `verify` | `contents: read` (`check` also `pull-requests: read`) | none |
| `publish` / `release` | `contents: write` (environment `publish`) | none |

## GitHub settings (proposal, to be applied when the repository is created)

Before applying, fill in the two Apps' IDs and run
`createscript-registry-tool check-settings --ruleset github-settings/main-ruleset.json --tag-creation-ruleset
github-settings/index-tags-creation-ruleset.json --tag-protection-ruleset github-settings/index-tags-protection-ruleset.json
--checks-app-id <checks App ID> --publisher-app-id <publisher App ID>`; it must print `OK`.

- **Rulesets**: `github-settings/main-ruleset.json` for `main` (pull requests with 2 approvals, code owner review,
  stale approvals dismissed, approval of the last push, resolved threads, required checks `registry/validate` and
  `registry/approvals` **from the registry's GitHub App** on the latest `main`, linear history, no force push, no
  deletion, **no bypass actors**), `github-settings/index-tags-creation-ruleset.json` (ruleset A: only the
  publishing App may create `index-*` tags) and `github-settings/index-tags-protection-ruleset.json` (ruleset B:
  `index-*` tags cannot be moved or deleted by anybody). The value `0` of `integration_id` and of A's `actor_id`
  in the template is a placeholder that `check-settings` refuses.
- **CODEOWNERS**: one rule, `*` followed by every maintainer (`createscript-registry-tool` refuses anything else).
  GitHub never counts an author's own approval, so with the lead as the only code owner the lead's pull requests
  could never be merged (finding P4-M1). With every maintainer as a code owner, any pull request can get the code
  owner approval from another maintainer; that the lead takes part in maintenance is checked by `approvals`.
- **Collaborators**: only maintainers, with the `write` role; the lead as admin. No deploy keys.
- **Actions**: allow only actions pinned to a full commit SHA; workflow permissions read-only by default;
  **require approval to run workflows for all outside collaborators** (not only first-time contributors).
- **Environment `registry-checks`**: the checks App's private key; deployment branches `main` only.
- **Environment `publish`**: required reviewers = the maintainers, "prevent self-review" on, only `main` can deploy;
  the publishing App's private key `REGISTRY_PUBLISHER_APP_KEY`.
- **Repository variables**: `REGISTRY_CHECKS_APP_ID` and `REGISTRY_PUBLISHER_APP_ID` (the two Apps' IDs).

## Index tags and releases

What protects the index releases, and what GitHub settings cannot do:

- Ruleset B (`github-settings/index-tags-protection-ruleset.json`) forbids **updating and deleting** `index-*`
  tags, for everybody (no bypass actors). A published index can therefore not be moved to another commit.
- Ruleset A (`github-settings/index-tags-creation-ruleset.json`) forbids **creating** `index-*` tags, with the
  publishing GitHub App as the only bypass actor (see below). Whether GitHub applies the bypass to the tag that is
  created when a draft release is published is **not verified** (test IT-21 and IT-09).
- Before publishing, the `check` job compares the repository's live tag rulesets with these two files and the App
  IDs (`check-live-rulesets`), and stops on any difference. According to GitHub's REST documentation, a ruleset's
  `bypass_actors` are returned only to users who can write the ruleset; the job's read-only token therefore may not
  see them, and then the check **fails** (it never assumes them). How the bypass actors are verified before each
  publication is decided after the tests on GitHub (Phase 10B); until then, publication stops at this check.
- Even with tag creation restricted, anyone with write access can create a release under another tag name, or
  mark another release as the latest. Clients fetch `releases/latest`, and an old signed index there is still
  valid until it expires: clients with saved state refuse it as a rollback (and keep their newer index), and a
  client without saved state accepts it (docs/registry.md, "Security limits"). This cannot be prevented by the
  repository settings; it is limited by: write access only for maintainers, the short validity of an index, and
  watching the releases (the lead checks after every publication, and anyone may, that the latest release is the
  highest `index-<serial>`).

### The publishing GitHub App (adopted in S8.3 Phase 9, in the template since Phase 10A, **not tried on GitHub**)

The design (`docs/registry-github-architecture.md`) is in the template since S8.3 Phase 10A: the `release` job
publishes with the publishing App's token and its own `GITHUB_TOKEN` is read-only, and the tag rulesets are A and
B. None of it has been tried on GitHub (tests IT-09 and IT-21 to IT-27 of `docs/registry-github-integration-test.md`);
in particular, how a bypass actor of a ruleset acts on the creation of tags is not verified.

- **A second App, "CreateScript Registry Publisher"**: repository permission "Contents: read and write" only,
  installed only on the registry repository, no webhook. It is separate from the checks App, so that neither key
  can do the other's job: the checks App cannot create releases or tags, and the publishing App cannot create the
  required checks or approve pull requests. Neither ever sees the index signing key, which stays offline.
- **Its private key** is a secret of the environment `publish` (required reviewers = the maintainers, "prevent
  self-review", deployment branches `main` only), so a workflow can use it only after the second approval. It is
  never stored anywhere else; it is rotated like the checks App's key.
- **The `release` job** keeps `GITHUB_TOKEN` read-only (`contents: read`) and never falls back to it. Just before
  its first use, after the files were checked, it asks for an installation token of the publishing App limited to
  this repository and `contents: write`, masks it, stops unless GitHub reports exactly `contents: write` (and
  `metadata: read`) on exactly this repository, uses it only for the draft, the draft check and the publication,
  and revokes it (`DELETE /installation/token`) when the step ends, however it ends. A failed authentication or a
  missing permission stops it before anything is created.
- **Two tag rulesets** for `refs/tags/index-*`:
  1. B: update, deletion and non-fast-forward forbidden, **no bypass actors**;
  2. A: creation forbidden, with the publishing App as the **only** bypass actor (`actor_type: Integration`). A
     bypass applies to every rule of its ruleset, which is why creation is in a ruleset of its own.
  The files are in `github-settings/`; A's `actor_id` is filled in when the App exists.
- **What it does not change**: maintainers with write access can still create releases under other tag names and
  change the latest release; an administrator can still change the rulesets; whoever has the publishing App's
  key can create `index-*` tags and releases, though not sign an index (an old signed index can be published
  again under a new tag, which clients with saved state refuse as a rollback). A client-side check that the tag
  of `releases/latest` is `index-<serial>` of the index it received would close the "other tag name" case; it is
  a separate code change.
- **Emergency stop** (with or without the App): disable the `publish` workflow in the Actions settings, remove the
  reviewers' ability to approve the `publish` environment by restricting it to a branch that does not exist, and,
  with the App, suspend its installation and delete its key. Nothing already published is deleted in a hurry
  (see "Failed publication").

## Failed publication

The `release` job creates `index-<serial>` as a **draft** first, checks the draft's two assets and GitHub's
digests against the SHA-256 recorded by the `check` job, and only then publishes it and makes it the latest.
Nothing is ever deleted or replaced automatically. Depending on where it stopped:

| Where it stopped | State | What the maintainers do |
|---|---|---|
| before the draft (checks, an existing release) | nothing published | read the error; an existing release for the serial means a previous attempt exists: inspect it before anything else |
| the draft's assets or digests | a **draft** (not public, not the latest) | inspect the draft, delete it by hand after recording what was wrong, fix the cause, run the workflow again |
| published, but not reported immutable, or `verify` failed | a **published** release (possibly the latest), recorded as failed in the job summary | do not delete it in a hurry: its assets are signed, so clients verify them. Check immutable releases and the digests; if it must be withdrawn, publish the next serial and record the decision in an issue |

A re-run never publishes the same serial again: the `check` job refuses a serial that is already published, and
the `release` job stops if any release (draft or published) exists for that serial.
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
