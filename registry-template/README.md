# CreateScript-Registry

> **Template, not in service.** This directory is the starting point for the future repository
> `CreateScript-Registry`. It lives in the CreateScript repository until that repository is created, and
> GitHub does not run its workflows here (they run only from a repository's root `.github/workflows/`).
> No index has been published, no signing key exists, and the registry is **closed** until it has at least
> three maintainers (see [GOVERNANCE.md](GOVERNANCE.md)). Nothing here has been tried on GitHub.

CreateScript-Registry maps package names to GitHub sources and to the SHA-256 of each reviewed version of
`createscript-package.zip`. Every change is a pull request that is checked automatically and approved by two
maintainers other than its author. A signed `index.json` built from this repository tells `createscript` which
archives were reviewed.

The registry does not change how packages are made or installed: projects keep
`"source": "github:owner/repo"` in `createscript.json`, and `createscript.lock` keeps the SHA-256. Packages that
are not in the registry can still be installed directly; `createscript` will say that they are not reviewed.

| File | Purpose |
|---|---|
| `registry.json` | index validity (days) and the IDs of the signing keys |
| `reserved-names.json` | names that nobody can register |
| `maintainers.json` | the lead, the maintainers (GitHub login and numeric ID), the number of approvals |
| `keys/trusted-keys.json` | the public keys that the publishing check accepts (none yet) |
| `packages/<first letter>/<name>.json` | one file per package |
| `signed/index.json`, `signed/index.json.sig` | the index to publish next, signed by a signer |
| `.github/workflows/validate.yml` | the checks of every pull request |
| `.github/workflows/publish.yml` | publishes a signed index as an immutable release |
| `github-settings/` | the proposed repository rulesets |

- How to register or update a package: [CONTRIBUTING.md](CONTRIBUTING.md)
- What is accepted: [POLICY.md](POLICY.md)
- Maintainers, approvals and GitHub settings: [GOVERNANCE.md](GOVERNANCE.md)
- Signing and publishing the index, and keys: [SIGNING.md](SIGNING.md)
- Reporting a problem: [SECURITY.md](SECURITY.md)

The file formats are described in the CreateScript repository in `docs/registry.md`. The checks are done by
`createscript-registry-tool` from the CreateScript repository.

## Trying it locally

The checks can be run without GitHub, with a fake GitHub in the tests:

```sh
./gradlew :createscript-registry-tool:test      # in the CreateScript repository
./gradlew :createscript-registry-tool:installDist
createscript-registry-tool/build/install/createscript-registry-tool/bin/createscript-registry-tool check-repo --repo registry-template
```
