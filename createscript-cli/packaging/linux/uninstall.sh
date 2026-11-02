#!/bin/sh
# Removes CreateScript installed by install.sh (Stage 3.12 S5.2).
#
#   ~/.local/lib/createscript/uninstall.sh       remove the installation that this script belongs to
#   ./uninstall.sh                                (from the extracted archive) remove the one in ~/.local
#   ./uninstall.sh --prefix DIR                   remove the one in DIR/lib/createscript
#
# Removes only what install.sh made: the files and directories listed in
# DIR/lib/createscript/.createscript-install, and DIR/bin/createscript when it is the link that install.sh
# made. A directory that still holds other files (for example files added later) is kept and reported.
# DIR, DIR/bin and DIR/lib are removed only when install.sh made them and they are empty.
#
# install.sh also uses "uninstall.sh --remove-files <directory>" to remove an earlier installation that it
# has moved aside.
set -u

MARKER=.createscript-install
MARKER_HEADER="createscript-install 1"

fail() {
    echo "uninstall: $*" >&2
    exit 1
}

# Removes the files and directories that <dir>/.createscript-install lists, then the list and <dir>.
# Returns 1 when something is kept.
remove_files() {
    dir=$1
    [ -d "$dir" ] && [ ! -h "$dir" ] || fail "$dir is not a directory"
    [ -f "$dir/$MARKER" ] && [ "$(sed -n 1p "$dir/$MARKER")" = "$MARKER_HEADER" ] ||
        fail "$dir was not made by install.sh (no $MARKER); nothing was removed"
    # Every listed directory must still be a real directory, so that no removal follows a symbolic link
    # that was put in its place
    bad=$(sed -n 's/^d //p' "$dir/$MARKER" | while IFS= read -r path; do
        case $path in
            (./*) ;;
            (*) echo "$path"; continue ;;
        esac
        case $path in
            (*/../*|*/..) echo "$path"; continue ;;
        esac
        if [ -h "$dir/$path" ] || { [ -e "$dir/$path" ] && [ ! -d "$dir/$path" ]; }; then echo "$path"; fi
    done)
    [ -z "$bad" ] || fail "$dir has unexpected entries in place of its directories ($bad); nothing was removed"

    sed -n 's/^f //p' "$dir/$MARKER" | while IFS= read -r path; do
        case $path in
            ./*) ;;
            *) continue ;;
        esac
        case $path in
            */../*|*/..) continue ;;
        esac
        if [ -f "$dir/$path" ] || [ -h "$dir/$path" ]; then rm -f -- "$dir/$path"; fi
    done
    sed -n 's/^d //p' "$dir/$MARKER" | LC_ALL=C sort -r | while IFS= read -r path; do
        case $path in
            ./*) ;;
            *) continue ;;
        esac
        case $path in
            */../*|*/..) continue ;;
        esac
        if [ -d "$dir/$path" ] && [ ! -h "$dir/$path" ]; then rmdir -- "$dir/$path" 2>/dev/null; fi
    done
    rm -f -- "$dir/$MARKER"
    if ! rmdir -- "$dir" 2>/dev/null; then
        echo "uninstall: $dir is kept because it contains files that CreateScript did not install:" >&2
        (cd "$dir" && find . ! -name . -print | sed 's/^/  /') >&2
        return 1
    fi
    return 0
}

here=$(cd -P -- "$(dirname -- "$0")" && pwd -P) || fail "cannot find this script's directory"
case $# in
    0)
        if [ -f "$here/$MARKER" ]; then
            libdir=$here
        else
            [ -n "${HOME:-}" ] || fail "HOME is not set; use --prefix DIR"
            libdir=$HOME/.local/lib/createscript
        fi
        ;;
    2)
        case $1 in
            --prefix) libdir=${2%/}/lib/createscript ;;
            --remove-files)
                remove_files "$2"
                exit $?
                ;;
            *) echo "usage: uninstall.sh [--prefix DIR]" >&2; exit 64 ;;
        esac
        ;;
    *) echo "usage: uninstall.sh [--prefix DIR]" >&2; exit 64 ;;
esac

[ -e "$libdir" ] || [ -h "$libdir" ] || fail "CreateScript is not installed in $libdir"
[ -h "$libdir" ] && fail "$libdir is a symbolic link; nothing was removed"
[ -f "$libdir/$MARKER" ] && [ "$(sed -n 1p "$libdir/$MARKER")" = "$MARKER_HEADER" ] ||
    fail "$libdir was not made by install.sh; nothing was removed"

# The link and the path it points to, as install.sh wrote them
link=$(sed -n 's/^link //p' "$libdir/$MARKER" | sed -n 1p)
target=$(sed -n 's/^target //p' "$libdir/$MARKER" | sed -n 1p)
if [ -n "$link" ] && [ -h "$link" ] && [ "$(readlink "$link")" = "$target" ]; then
    rm -f -- "$link"
elif [ -n "$link" ] && { [ -e "$link" ] || [ -h "$link" ]; }; then
    echo "uninstall: $link was not made by CreateScript; it is kept" >&2
fi

made=$(sed -n 's/^made-dir //p' "$libdir/$MARKER")
if remove_files "$libdir"; then
    echo "CreateScript was removed from $libdir"
else
    echo "CreateScript was removed, except for the files listed above"
fi

# The directories that install.sh made (DIR/bin, DIR/lib, DIR), only when they are empty now
printf '%s\n' "$made" | LC_ALL=C sort -r | while IFS= read -r dir; do
    case $dir in
        /*) ;;
        *) continue ;;
    esac
    if [ -d "$dir" ] && [ ! -h "$dir" ]; then rmdir -- "$dir" 2>/dev/null || true; fi
done
