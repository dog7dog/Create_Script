# Security policy

## Supported versions

CreateScript is in alpha. Only the latest commit on the `main` branch receives security fixes.

| Version | Supported |
|---|---|
| 0.1.x (alpha), `main` | Yes |

## Reporting a vulnerability

Please **do not** report vulnerabilities in public issues, pull requests or discussions.

Use GitHub's private vulnerability reporting: open the repository's **Security** tab and choose
**Report a vulnerability**. Please include:

- the CreateScript version or commit, and how you run it (CLI, Core API, package);
- a minimal `.cscr` program or steps that show the problem;
- what you expected and what happened.

The maintainer replies in the private advisory. Please allow time for a fix to be released before you
disclose the problem publicly.

## Scope

CreateScript is designed so that a program cannot access files, the network, the operating system,
Java/Kotlin code or reflection, and so that it always stays within fixed limits of memory and
instructions. Reports of special interest include:

- a `.cscr` program that escapes these restrictions, or that makes the VM, the compiler or the CLI use
  unbounded memory or time;
- a crafted VM snapshot that passes `SnapshotVerifier` but breaks the VM;
- problems in the installers (`install.sh`, `uninstall.sh`, the macOS package scripts), for example
  removing or replacing files that they did not install.

The macOS package built by `build-pkg.sh` is unsigned and not notarized; that is known and documented.
