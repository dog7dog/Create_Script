# Compatibility policy

This policy covers the Stable API of `createscript-core` and `createscript-project`, as classified in
[api-surface.md](api-surface.md). It was decided in stage A1 and applied in A2–A4.

## Rules

- Before 1.0, a release can break the Stable API, but only when the break is listed under a `### Changed` or
  `### Removed` heading of `CHANGELOG.md` with what to use instead.
- Stable declarations that were in `v0.1.0-alpha` keep their JVM signatures (binary compatibility), as the
  existing `Program` and `CompiledProgram` compatibility constructors do (`@Deprecated(level = HIDDEN)`).
- New properties of Stable data classes get default values and go after the existing ones, so constructors
  and `copy` keep working; a hidden overload keeps the old JVM signature.
- Saved states: `VmSnapshot` made by an earlier release must still be accepted by `CreateScript.resume`
  (the language changes are made so that saved states keep working, for example stage 9.0 and 9.2; see
  [../language.md](../language.md)).
- Diagnostic code ids (`CS_…`, `CS_PKG_…`) are Stable: an id is never reused for a different meaning. New codes
  can be added in any release, so a `when` over `DiagnosticCode` or `ProjectDiagnosticCode` needs an `else`.
  The same holds for `DiagnosticPhase` (this was already announced for `DiagnosticPhase.MODULE`).
- Internal API can change in any release without a CHANGELOG entry.

## Internal API and opt-in

Declarations that are public only because other modules of this repository use them, or because they were public
in `v0.1.0-alpha`, are marked `@InternalCreateScriptApi` (core) or `@InternalCreateScriptProjectApi` (project).
Using them gives a compiler warning, not an error, until the caller opts in with `@OptIn(...)` or
`-opt-in=...`. Code written against `v0.1.0-alpha` that uses them keeps compiling.

## How the rules are checked

- `api/createscript-core.api` and `api/createscript-project.api` list the public API on the JVM, with opt-in
  markers, nullability and constant values. `ApiListingTest` fails when the compiled API differs. A change is made
  on purpose by running the tests with `CREATESCRIPT_API_UPDATE=1` and reviewing the diff of the listing (see
  "API listing" in [api-surface.md](api-surface.md)).
- `BinaryCompatibilityTest` runs code compiled against `v0.1.0-alpha` and against the module API of S8.2 Phase 3
  with the current Core, including a host that saves, rebuilds and resumes runs field by field.
- `CreateScriptTest` checks the documented behaviour of saving and resuming.
