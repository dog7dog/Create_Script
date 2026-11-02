# Licenses in this CreateScript package

CreateScript itself (`lib/createscript-cli.jar`, `lib/createscript-core.jar`, `bin/createscript`, and
`install.sh` / `uninstall.sh` where present) is licensed under the Apache License, Version 2.0
(`legal/APACHE-2.0.txt`). Its notice file is `legal/NOTICE`.

This package also contains the following components that are not part of CreateScript.
They are distributed under their own licenses.

| Component | Files | License | License text |
|---|---|---|---|
| Java runtime (made with `jlink` from the JDK named in `BUILD-INFO.txt`; the release packages use Eclipse Temurin 21) | `runtime/` | GNU General Public License, version 2, with the Classpath Exception, and the third-party licenses listed by OpenJDK | `runtime/legal/` (`java.base/LICENSE`, `ASSEMBLY_EXCEPTION`, `ADDITIONAL_LICENSE_INFO` and the `*.md` files) |
| Kotlin Standard Library 2.0.0 (`org.jetbrains.kotlin:kotlin-stdlib`) | `lib/kotlin-stdlib-2.0.0.jar` | Apache License, Version 2.0 | `legal/APACHE-2.0.txt` |
| JetBrains Java Annotations 13.0 (`org.jetbrains:annotations`) | `lib/annotations-13.0.jar` | Apache License, Version 2.0 | `legal/APACHE-2.0.txt` |
| JLine 3.30.17 (`org.jline:jline-reader`, `org.jline:jline-terminal`; line editing in the interactive mode) | `lib/jline-reader-3.30.17.jar`, `lib/jline-terminal-3.30.17.jar` | BSD 3-Clause License | `legal/BSD-3-CLAUSE-JLINE.txt` |

## Java runtime

The Java runtime is not modified. It contains only the Java modules listed in `BUILD-INFO.txt`,
without debug information, header files or manual pages.

`legal/JAVA-RUNTIME-SOURCE.txt` names the exact JDK build that the runtime was made from and where its
complete source code can be obtained: the OpenJDK source repository and commit recorded by that JDK, and,
for Eclipse Temurin, the release page that holds the source archive of that build.

## JLine

JLine is used without its native library (`org.jline:jline-native` is not included). It sets up the terminal
with the `stty` command of the operating system.
