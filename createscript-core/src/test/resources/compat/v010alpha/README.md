# Binary compatibility fixture: v0.1.0-alpha

`OldCaller.class` is `OldCaller.kt.txt` compiled with Kotlin 2.0.0 (JVM 21) against the `createscript-core.jar`
of CreateScript v0.1.0-alpha (commit `5c7d68c`, built from `git archive 5c7d68c`). `BinaryCompatibilityTest`
runs it with the current Core, so the test fails if a member of the v0.1.0-alpha API that it calls is
removed or changed (for example `ast.Program(List, List, List)` and `copy$default`).

SHA-256 of the files used:

- `createscript-core.jar` of v0.1.0-alpha: `d74f0167fa61b05eb1aedb26ecd58017e2faeaf1d5288a443c96c2235f59dad9`
- `OldCaller.class`: `f00f54f334b03e1dfdd05b0629a4d1498d728c451db3e65056ac76c6d522acfd`

To make it again: build the v0.1.0-alpha Core jar from `git archive 5c7d68c`, compile `OldCaller.kt.txt`
(renamed to `.kt`) with the Kotlin 2.0.0 Gradle plugin and `compileOnly files('<that jar>')`, and copy
`compat/v010alpha/OldCaller.class` here. The class file contains no paths of the machine that built it.
