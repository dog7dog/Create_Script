# Binary compatibility fixture: the module API before S8.2 Phase 4

`OldModuleUser.class` and `OldResolver.class` are `OldModuleUser.kt.txt` compiled with Kotlin 2.0.0 (JVM 21) against
the `createscript-core.jar` of commit `9f938b4` (S8.2 Phase 3, built from `git archive 9f938b4`; the Core there is
the same as in S8.1). `BinaryCompatibilityTest` runs them with the current Core, so the test fails if S8.2 Phase 4
(`ModuleAccess` and the new `CreateScript.compile` overload) changes the existing module API: a class that
implements `ModuleResolver`, a lambda used as one, `CreateScript.compile(source, resolver)` (`compile$default`),
`compile(source, resolver, entryModule, options)`, the `ModuleLookup` results, `sourceFingerprint`, `locate`, and
running and resuming the program.

SHA-256 of the files used:

- `createscript-core.jar` of `9f938b4`: `4be75db5ec27095d84fa1b28bac314a7eb6aad5d96230f6971ccdbdb3b55c5ef`
- `OldModuleUser.class`: `5a46edc1ac91fad3d7529fae04204c982bfd18190d93a890276408883ea4b4f8`
- `OldResolver.class`: `6acaf2eb6074d23d99d013d5243946fb40854e2e913bee5800d099c39e650858`

To make them again: build the Core jar from `git archive 9f938b4`, compile `OldModuleUser.kt.txt` (renamed to
`.kt`) with the Kotlin 2.0.0 Gradle plugin and `compileOnly files('<that jar>')`, and copy the two classes from
`compat/s82phase3/` here. The class files contain no paths of the machine that built them.
