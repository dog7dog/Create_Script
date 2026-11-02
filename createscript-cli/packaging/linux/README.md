# CreateScript for Linux

This archive contains the `createscript` command and a Java 21 runtime for one CPU type (see
`BUILD-INFO.txt`). Java, Gradle and Kotlin do not need to be installed.

## Check the download

```bash
sha256sum -c createscript-<version>-linux-<arch>.tar.gz.sha256
```

## Install (for the current user, no root)

```bash
tar -xzf createscript-<version>-linux-<arch>.tar.gz
cd createscript
./install.sh
```

- The files go to `~/.local/lib/createscript`, and the command to `~/.local/bin/createscript`.
- To install somewhere else: `./install.sh --prefix DIR` (files in `DIR/lib/createscript`, command in `DIR/bin/createscript`).
- If `~/.local/bin` is not in `PATH`, `install.sh` prints the line to add to your shell startup file.
- Running `install.sh` again (for example with a newer archive) replaces the earlier installation in the same place.
- `install.sh` stops without changing anything if `~/.local/bin/createscript` or `~/.local/lib/createscript` already exists and was not made by CreateScript.

You can also run `bin/createscript` in the extracted directory without installing.

## Use

```bash
createscript                   # interactive mode (REPL)
createscript run hello.cscr    # run a .cscr file
createscript --help
createscript --version
```

## Uninstall

```bash
~/.local/lib/createscript/uninstall.sh
```

(With `--prefix DIR` at install time: `DIR/lib/createscript/uninstall.sh`.)
It removes only the files that `install.sh` installed and the `createscript` link that it made.
Files that you added to `~/.local/lib/createscript` are kept and listed.

## Licenses

See `legal/THIRD-PARTY-NOTICES.md` and `runtime/legal/`.
