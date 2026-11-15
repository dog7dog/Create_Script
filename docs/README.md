# CreateScript documentation

The documents are grouped by reader. Documents for users and for embedding are in English; Japanese versions will be
in `docs/ja/` (stage A5b, not written yet). Design and operations documents are in Japanese and are not translated.

## For users

| Document | Contents |
|---|---|
| [language.md](language.md) | The CreateScript language: values, control flow, functions, modules, classes, lists, lambdas, `\|>`, `?!`, math, ticks, limits |
| [cli.md](cli.md) | The `createscript` command: running files and projects, the REPL and line editing, building packages with a bundled Java runtime |
| [samples.md](samples.md) | The sample programs |
| [packages.md](packages.md) | Project files (`createscript.json`, `createscript.lock`), package archives, downloading and installing packages |
| [registry.md](registry.md) | The planned CreateScript-Registry: layout, index, signatures, operation and the client (not published) |
| [../createscript-cli/README.md](../createscript-cli/README.md) | Detailed manual of the command (Japanese) |

## For embedding and tools (API)

| Document | Contents |
|---|---|
| [api/embedding.md](api/embedding.md) | Embedding the Core: compiling, running, limits, modules, saving and resuming, threads |
| [api/project-files.md](api/project-files.md) | Reading and checking project files with `createscript-project` |
| [api/api-surface.md](api/api-surface.md) | Which declarations are Stable, Internal API or module-private, and how the API listing works |
| [api/compatibility.md](api/compatibility.md) | The compatibility policy and how it is checked |

## Design

| Document | Contents |
|---|---|
| [design/registry-github-architecture.md](design/registry-github-architecture.md) | The GitHub setup of CreateScript-Registry (two Apps, tag rulesets), Japanese |

## Operations

| Document | Contents |
|---|---|
| [operations/registry-production-setup.md](operations/registry-production-setup.md) | Setting up the production registry, Japanese |
| [operations/registry-settings-key.md](operations/registry-settings-key.md) | The settings key that signs ruleset snapshots, Japanese |
| [operations/registry-github-test-environment-setup.md](operations/registry-github-test-environment-setup.md) | Setting up the GitHub test environment, Japanese |
| [operations/registry-github-integration-test.md](operations/registry-github-integration-test.md) | The GitHub integration test procedure, Japanese |
| [operations/registry-github-execution-plan.md](operations/registry-github-execution-plan.md) | The execution plan of the GitHub integration operations, Japanese |

## Records

[audits/](audits/) holds the security audits and assessment reports of stage S8.3. They are records: they are not
updated when documents move, so they name the paths that were valid when they were written (see below).
[../CHANGELOG.md](../CHANGELOG.md) is kept the same way.

## Moved documents

Stage A5a (2026-11-15) moved these documents. Older records, commit messages and the audits use the old paths.

| Old path | New path |
|---|---|
| `docs/registry-github-architecture.md` | [`docs/design/registry-github-architecture.md`](design/registry-github-architecture.md) |
| `docs/registry-production-setup.md` | [`docs/operations/registry-production-setup.md`](operations/registry-production-setup.md) |
| `docs/registry-settings-key.md` | [`docs/operations/registry-settings-key.md`](operations/registry-settings-key.md) (the old path keeps a short page that points there, because messages of `createscript-registry-tool` name it) |
| `docs/registry-github-test-environment-setup.md` | [`docs/operations/registry-github-test-environment-setup.md`](operations/registry-github-test-environment-setup.md) |
| `docs/registry-github-integration-test.md` | [`docs/operations/registry-github-integration-test.md`](operations/registry-github-integration-test.md) |
| `docs/registry-github-execution-plan.md` | [`docs/operations/registry-github-execution-plan.md`](operations/registry-github-execution-plan.md) |
| The "Using the Core library" details in `README.md` | [`docs/api/embedding.md`](api/embedding.md) (the README keeps a short example) |
| "Compatibility rules" in `docs/api/api-surface.md` | [`docs/api/compatibility.md`](api/compatibility.md) |

## Japanese versions and how they are kept in step (plan for A5b)

Stage A5b will add Japanese versions of the user and API documents under `docs/ja/`, with the same file names
(`docs/ja/language.md`, …, `docs/ja/api/api-surface.md`) and an index `docs/ja/README.md`. The English document is
the reference; each Japanese document starts with the path and the commit of the English document it follows.

A check without new dependencies will be kept in the repository: shared test code in
`test-support/docs-check/` (like `test-support/api-listing/`), run by a JUnit test of `createscript-cli`, so that it
runs in `./gradlew build` and in CI. For each pair of an English document and its Japanese version it will check:

- the same number and levels of headings;
- the same code blocks, character for character;
- the same number of tables and rows;
- the same number of links, with the same targets (a link to an English document from a Japanese one points to
  its Japanese version when there is one);
- the same `CS_...` ids and the same numbers;
- the source path and commit at the top of the Japanese document exist.

It will also check that every relative link and anchor in the repository's Markdown files leads to a file and
heading that exist (records in `docs/audits/` and `CHANGELOG.md` are reported, not failed, because they keep old
paths). Whether the translation says the same thing is reviewed by a person; the check cannot tell.
