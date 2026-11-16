# CreateScript documentation

The documents are grouped by reader. Documents for users and for embedding are in English, with Japanese versions in
[`ja/`](ja/README.md). Design and operations documents are in Japanese and are not translated.

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

## Japanese versions and how they are kept in step

Stage A5b added Japanese versions of the user and API documents under `docs/ja/`, with the same file names
(`docs/ja/language.md`, …, `docs/ja/api/api-surface.md`) and an index, [`docs/ja/README.md`](ja/README.md). The English
document is the reference; each Japanese document starts with the path and the commit of the English document it
follows. A Japanese document may explain more than its source with notes, lists, `<details>` and tables of its own;
it does not leave anything out.

The check is in the repository and needs no new dependency: `test-support/docs-check/` (like
`test-support/api-listing/`) is run by `DocsTranslationTest` of `createscript-cli`, so it runs in `./gradlew build` and
in CI. For each pair of an English document and its Japanese version (and for `README.md` and `README.ja.md`) it checks:

- the same sequence of heading levels;
- the same code blocks, in the same order, character for character, apart from comments, which can be translated
  (`#` comments in `bash` blocks, `//` comments in `kotlin` blocks);
- the same tables with the same number of rows (a table that only the Japanese document has is marked with a line
  `<!-- ja-only -->` before it and is not compared);
- the same set of link targets: a link to a document that has a Japanese version points to that version, and an
  anchor points to the heading at the same position;
- the same `CS_...` ids, and every number of the English text also in the Japanese text;
- the source line at the top of the Japanese document.

It also checks that every relative link and anchor in the repository's Markdown files leads to a file and heading
that exist (records in `docs/audits/` and `CHANGELOG.md` are reported, not failed, because they keep old paths).
Whether the translation says the same thing is reviewed by a person; the check cannot tell.
