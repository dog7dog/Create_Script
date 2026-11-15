# Signing and publishing the index

> **Not in service.** No production key exists yet. In this phase `createscript-registry-tool sign` refuses every
> key whose ID does not start with `test-only-`, and `publish-check` refuses trusted keys with such IDs, so a test
> key can never sign a public index. Creating the production key needs its own approval (see "Key ceremony").

## Principles

- The private key never goes to GitHub: not as a secret, not in a workflow, not in a file in any repository.
- Signing happens on a signer's own machine. The publishing workflow only checks a signed index and publishes it;
  it cannot sign.
- What is signed is exactly the canonical `index.json` (see `docs/registry.md`) built from a reviewed commit of
  `main`, prefixed with the domain `CreateScript-Registry index signature v1` and the key ID.
- An index is published only after **two separate approvals**: the publication pull request (two maintainers)
  and the `publish` environment. Before publishing, the workflow checks the approvals of the merged pull request
  again, and that the merged tree is exactly the tree that was approved.
- The release is created with the dedicated publishing App's short-lived token (the job's own `GITHUB_TOKEN` is
  read-only; GOVERNANCE.md, "Index tags and releases"); only the `release` job, after the `publish` environment
  approval, can obtain it. Neither the workflow nor either GitHub App ever has the index signing key.
- The job that creates the release (the only one with a write token) builds and runs nothing: it compares the two
  files with the SHA-256 values from the checking job, stops if a release for the serial already exists, creates
  the release as a draft, checks the draft's assets and GitHub's digests, and only then publishes it as the
  latest. Nothing is deleted or replaced automatically (GOVERNANCE.md, "Failed publication").

## Publishing a new index

On the signer's machine, with the CreateScript tool built (`./gradlew :createscript-registry-tool:installDist`):

```sh
git switch main && git pull --ff-only          # the reviewed state
curl -fsSLO https://github.com/OWNER/CreateScript-Registry/releases/latest/download/index.json
curl -fsSLO https://github.com/OWNER/CreateScript-Registry/releases/latest/download/index.json.sig

tool build-index --repo . --previous-index index.json --previous-sig index.json.sig --out signed/index.json
#   prints the serial and every change since the published index: read it before signing
tool sign --repo . --index signed/index.json --key /path/to/offline/key.pk8 --key-id createscript-registry-key-1 --out signed/index.json.sig
tool publish-check --repo . --previous-index index.json --previous-sig index.json.sig
```

(`tool` is `createscript-registry-tool/build/install/createscript-registry-tool/bin/createscript-registry-tool`.)

Then open a pull request that changes only `signed/index.json` and `signed/index.json.sig`. `validate` runs the
same `publish-check`. After two approvals and the merge, `publish.yml` checks again (job `check`: the index, the
approvals of the merged pull request on its head commit, and that the merged tree is that head commit's tree),
waits for the `publish` environment approval, creates the release `index-<serial>` as a draft, checks it and then
publishes it (job `release`, which builds nothing), and checks that GitHub reports it immutable with the right
digests (job `verify`, which records a failure in the job summary).

Any maintainer may sign, the lead included: with every maintainer as a code owner, the lead's publication pull
request is approved by two other maintainers like any other.

The checks refuse:

- an index without a signature, with a signature by an unknown key or a test key, or with a wrong signature;
- a serial that is not exactly the one after the published index (no rollback, no reuse);
- an index whose reviewed SHA-256 values or dependencies differ from the published index, or that lost a version;
- an index that is not byte for byte the one built from the commit being published;
- publishing while the registry is closed;
- publishing a commit that does not come from exactly one merged pull request with valid approvals on its head
  commit.

If the `release` or `verify` job fails, follow GOVERNANCE.md, "Failed publication": a draft that failed its checks
is inspected and deleted by hand; a published release that is not reported immutable is not deleted in a hurry
(its assets are signed). A published immutable release cannot be changed; a mistake is fixed by the next index.

The index expires after `indexValidityDays` (30). Publish a new index at least every three weeks, even without
changes.

## Key ceremony (to be approved before it happens)

- Ed25519, generated on an offline machine by the lead with a second maintainer watching, for example
  `openssl genpkey -algorithm ed25519 -out key.pk8` (PKCS#8). The tool reads PKCS#8 in DER or PEM.
- The private key is kept encrypted on two offline media in two places, held by two different maintainers.
- The public key (32 bytes, Base64) goes into `keys/trusted-keys.json` through a maintenance pull request, and into
  `createscript` (the clients' built-in keys, Phase 3) through a CreateScript release.
- The key ID names the key and its year, for example `createscript-registry-2027-1`, and is listed in
  `registry.json` (`signingKeyIds`).

## Rotating a key (planned)

1. Make the new key (ceremony) and add its public key and ID to `keys/trusted-keys.json`, `registry.json` and a
   CreateScript release.
2. Sign every index with both keys (`sign --existing-sig` adds a signature to the file) until most users have that
   release.
3. Remove the old key from `createscript`, then from the files, then destroy it.

## A lost key

Nothing is published until a new key is made. Make it (ceremony), publish a CreateScript release with the new key,
and publish new indexes signed with it. Indexes signed with the lost key stay valid until they expire.

## A leaked key

1. Announce it (security advisory) and stop publishing with that key.
2. Make a new key, remove the leaked key from `keys/trusted-keys.json` and `registry.json`, and publish a
   CreateScript release that trusts only the new key.
3. Publish a new index with a higher serial signed with the new key as soon as possible.

Until users update `createscript`, whoever holds the leaked key can sign indexes for them. Clients still refuse a
lower serial and any change to versions they have already seen, so the damage is limited to new packages and new
versions, and ends when those indexes expire.
