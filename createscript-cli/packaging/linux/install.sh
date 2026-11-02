#!/bin/sh
# Installs CreateScript from this directory for the current user (Stage 3.12 S5.2).
#
#   ./install.sh                  install to ~/.local (files in ~/.local/lib/createscript,
#                                 command ~/.local/bin/createscript)
#   ./install.sh --prefix DIR     install to DIR/lib/createscript and DIR/bin/createscript
#
# Root is not needed for ~/.local. Running it again replaces an earlier CreateScript installation in the
# same place. It stops without changing anything when
#   - DIR/bin/createscript exists and is not the link that CreateScript made,
#   - DIR/lib/createscript exists and was not made by this installer, or is a symbolic link,
#   - the bundled Java runtime is not for this OS and CPU.
# The new files are copied and checked in a temporary directory next to DIR/lib/createscript first, so a
# failure leaves the earlier installation as it was.
set -u

MARKER=.createscript-install
MARKER_HEADER="createscript-install 1"

fail() {
    echo "install: $*" >&2
    echo "install: nothing was changed" >&2
    exit 1
}

prefix=${HOME:-}/.local
case $# in
    0) [ -n "${HOME:-}" ] || fail "HOME is not set; use --prefix DIR" ;;
    2) [ "$1" = --prefix ] || { echo "usage: install.sh [--prefix DIR]" >&2; exit 64; }; prefix=$2 ;;
    *) echo "usage: install.sh [--prefix DIR]" >&2; exit 64 ;;
esac
case $prefix in
    /*) ;;
    *) fail "the prefix must be an absolute path: $prefix" ;;
esac
case $prefix in
    *:*) fail "the prefix must not contain ':' (it separates Java class path entries): $prefix" ;;
esac
nl='
'
case $prefix in
    *"$nl"*) fail "the prefix must not contain a line break" ;;
esac
prefix=${prefix%/}

src=$(cd -P -- "$(dirname -- "$0")" && pwd -P) || fail "cannot find the package directory"
for item in bin/createscript runtime/bin/java lib BUILD-INFO.txt uninstall.sh; do
    [ -e "$src/$item" ] || fail "the package is incomplete ($item is missing); extract the archive again"
done

# The runtime must be for this OS and CPU (build-image.sh writes them to BUILD-INFO.txt)
package_os=$(sed -n 's/^os: //p' "$src/BUILD-INFO.txt")
package_arch=$(sed -n 's/^arch: //p' "$src/BUILD-INFO.txt")
case $(uname -s) in
    Linux) host_os=linux ;;
    Darwin) host_os=macos ;;
    *) host_os=$(uname -s) ;;
esac
case $(uname -m) in
    x86_64|amd64) host_arch=x86_64 ;;
    aarch64) host_arch=aarch64 ;;
    arm64) if [ "$host_os" = macos ]; then host_arch=arm64; else host_arch=aarch64; fi ;;
    *) host_arch=$(uname -m) ;;
esac
[ "$package_os" = "$host_os" ] || fail "this package is for ${package_os:-an unknown OS}, but this machine runs $host_os"
[ "$package_arch" = "$host_arch" ] ||
    fail "this package is for ${package_arch:-an unknown} CPUs, but this machine is $host_arch; use the $host_arch package"

libparent=$prefix/lib
libdir=$libparent/createscript
bindir=$prefix/bin
link=$bindir/createscript
launcher=$libdir/bin/createscript

# Conflicts
if [ -e "$link" ] || [ -h "$link" ]; then
    if [ ! -h "$link" ] || [ "$(readlink "$link")" != "$launcher" ]; then
        fail "$link already exists and was not made by CreateScript; move it away, or use --prefix"
    fi
fi
if [ -e "$libdir" ] || [ -h "$libdir" ]; then
    [ -h "$libdir" ] && fail "$libdir is a symbolic link; remove it first"
    [ -d "$libdir" ] || fail "$libdir exists and is not a directory; move it away first"
    [ -f "$libdir/$MARKER" ] && [ "$(sed -n 1p "$libdir/$MARKER")" = "$MARKER_HEADER" ] ||
        fail "$libdir exists but was not made by this installer; move it away first"
fi

# The new files are copied to a temporary directory next to the installation and checked there
staging=$libparent/.createscript-new.$$
old=$libparent/.createscript-old.$$
[ ! -e "$staging" ] && [ ! -h "$staging" ] && [ ! -e "$old" ] && [ ! -h "$old" ] ||
    fail "a temporary directory already exists: $staging or $old"
made_now=""
# If the installation fails, the temporary directory (made by this run; its name has this process ID) is
# removed as a whole, and the directories that this run made are removed again when they are still empty
cleanup() {
    if [ -d "$staging" ] && [ ! -h "$staging" ]; then rm -rf -- "$staging"; fi
    printf '%s\n' "$made_now" | while IFS= read -r dir; do
        if [ -n "$dir" ]; then rmdir -- "$dir" 2>/dev/null; fi
    done
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM

# Directories that this installation makes are recorded, so that uninstall.sh can remove them when they are
# empty. Those recorded by an earlier installation in the same place are kept.
parent=$(dirname -- "$prefix")
[ -d "$parent" ] || fail "$parent does not exist"
made=""
if [ -f "$libdir/$MARKER" ]; then made=$(sed -n 's/^made-dir //p' "$libdir/$MARKER"); fi
for dir in "$prefix" "$libparent" "$bindir"; do
    if [ ! -d "$dir" ]; then
        mkdir "$dir" || fail "cannot create $dir"
        made="$made$nl$dir"
        made_now="$dir${made_now:+$nl$made_now}"
    fi
done

mkdir "$staging" || fail "cannot create $staging"
for item in bin lib runtime legal BUILD-INFO.txt README.md uninstall.sh; do
    if [ -e "$src/$item" ]; then
        cp -RP "$src/$item" "$staging/$item" || fail "cannot copy $item"
    fi
done
{
    echo "$MARKER_HEADER"
    echo "link $link"
    echo "target $launcher"
    printf '%s\n' "$made" | sed -n 's/^\(\/.*\)$/made-dir \1/p'
    (cd "$staging" && find . ! -name . -type d -print) | LC_ALL=C sort | sed 's/^/d /'
    (cd "$staging" && find . ! -path "./$MARKER" \( -type f -o -type l \) -print) | LC_ALL=C sort | sed 's/^/f /'
} > "$staging/$MARKER" || fail "cannot write $staging/$MARKER"

"$staging/bin/createscript" --version >/dev/null 2>&1 || fail "the copied CreateScript does not start"

# Replace the earlier installation (if any) with the new one
if [ -d "$libdir" ]; then
    mv "$libdir" "$old" || fail "cannot move the earlier installation aside"
fi
if ! mv "$staging" "$libdir"; then
    [ -d "$old" ] && mv "$old" "$libdir"
    fail "cannot move the new files into $libdir"
fi
if [ ! -h "$link" ]; then
    if ! ln -s "$launcher" "$link"; then
        mv "$libdir" "$staging"
        [ -d "$old" ] && mv "$old" "$libdir"
        fail "cannot create $link"
    fi
fi
trap - EXIT HUP INT TERM
if [ -d "$old" ]; then
    "$libdir/uninstall.sh" --remove-files "$old" ||
        echo "install: the earlier installation was kept in $old because it has files that CreateScript did not install" >&2
fi

version=$("$link" --version 2>/dev/null)
echo "Installed ${version:-CreateScript} to $libdir"
echo "Command: $link"

case ":${PATH:-}:" in
    *":$bindir:"*)
        found=$(command -v createscript 2>/dev/null || true)
        if [ -n "$found" ] && [ "$found" != "$link" ]; then
            echo "Note: another createscript comes first in PATH: $found"
        fi
        ;;
    *)
        echo
        echo "$bindir is not in PATH. To run createscript by name, add this line to your shell startup file"
        echo "(~/.profile, ~/.bashrc or ~/.zshrc), then open a new terminal:"
        echo
        echo "    export PATH=\"$bindir:\$PATH\""
        ;;
esac
