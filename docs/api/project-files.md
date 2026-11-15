# Reading project files with `createscript-project`

`createscript-project` reads, checks and writes the project files `createscript.json` and `createscript.lock`. It
works on bytes and strings only: it reads no files and uses no network, so a tool reads the files itself and passes
the bytes. The file formats and their rules are described in [../packages.md](../packages.md); this guide shows the
Stable API (listed in [api-surface.md](api-surface.md)).

Build it with `./gradlew :createscript-project:jar` (`createscript-project/build/libs/createscript-project.jar`); it
needs only the Kotlin standard library 2.0.0 (with the JetBrains annotations that it uses). Package: `com.creatrbypc.createanalytical.project`.

## The manifest

```kotlin
import com.creatrbypc.createanalytical.project.Manifests

fun checkManifest(bytes: ByteArray) {
    val result = Manifests.parse(bytes)
    val manifest = result.manifest
    if (manifest == null) {
        result.diagnostics.forEach { println(it.format(Manifests.FILE_NAME)) }
        return
    }
    println("${manifest.name} ${manifest.version} (${manifest.kind.id})")
    manifest.entry?.let { println("entry $it at line ${result.entryPosition.line}") }
}
```

- `parse` is strict: unknown or duplicate keys, wrong types and values that break the rules are diagnostics (with
  stable ids such as `CS_PKG_INVALID_NAME`), not exceptions. Files over `Manifests.MAX_BYTES` are refused.
- `ProjectDiagnostic.format(file)` gives `file:line:column: error CODE ("argument", ...)`, with arguments escaped so
  that the text is safe to print on a terminal.
- `ManifestResult` gives positions (`FilePosition`) of the entry, of each dependency and of each module, to report
  later problems (such as a missing file) at the right place.
- `Manifests.write(manifest)` gives the canonical text; the same manifest always gives the same text.

## The lock file

```kotlin
import com.creatrbypc.createanalytical.project.Lockfiles
import com.creatrbypc.createanalytical.project.Manifest

fun checkLock(bytes: ByteArray, manifest: Manifest): Boolean {
    val result = Lockfiles.parse(bytes)
    val problems = Lockfiles.check(bytes, result, manifest)
    problems.forEach { println(it.format(Lockfiles.FILE_NAME)) }
    return problems.isEmpty()
}
```

- `parse` checks what the lock file alone decides (values, duplicates, references, cycles).
- `check` also requires the exact canonical bytes (`Lockfiles.write`) and agreement with the manifest; it returns
  the diagnostics of `parse` when that failed.
- `Lockfile.of(root, packages)` makes a lock file in canonical order and throws `IllegalArgumentException` for
  invalid values.

## The rules

`ProjectRules` has the rules on their own: `isValidName` (package names), `isValidModuleName`, `isValidVersion`
(SemVer), `isValidLicense`, `isValidSource` (`github:owner/repository`), `isValidSha256`, `pathProblem` (entry and
module paths) and `isWindowsReserved`, with their limits. All rules accept ASCII only.

## Not in the Stable API

The JSON reader and writer, package archives (`PackageArchives`) and the registry formats are Internal API
(`@InternalCreateScriptProjectApi`): the CLI and the installer use them, and they can change in any release.
`PackageArchives` may become Stable when its rules are settled.

All objects in this module keep no mutable state and can be used from several threads.
