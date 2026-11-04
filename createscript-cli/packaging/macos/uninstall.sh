#!/bin/sh
# Removes CreateScript installed by the macOS package (Stage 3.12 S5.2).
#
#   sudo /usr/local/lib/createscript/uninstall.sh            remove
#   /usr/local/lib/createscript/uninstall.sh --dry-run       only show what would be removed
#
# Removes only what the package installed: the files that the installer receipt lists, the directories
# that become empty, and /usr/local/bin/createscript when it is the link that the package made.
# Then forgets the receipt. /usr/local, /usr/local/bin and /usr/local/lib are never removed.
set -u

ID=com.creatrbypc.createscript
LOCATION=usr/local/lib/createscript
LINK_TARGET=../lib/createscript/bin/createscript
lib=/$LOCATION
link=/usr/local/bin/createscript

dry=false
case ${1:-} in
    "") ;;
    --dry-run) dry=true ;;
    *)
        echo "usage: uninstall.sh [--dry-run]" >&2
        exit 64
        ;;
esac

fail() {
    echo "uninstall: $*" >&2
    exit 1
}

location=$(pkgutil --pkg-info "$ID" 2>/dev/null | sed -n 's/^location: //p')
[ "$location" = "$LOCATION" ] || fail "CreateScript is not installed by the package (no receipt $ID for /$LOCATION)"
[ -h "$lib" ] && fail "$lib is a symbolic link; nothing was removed"
if [ "$dry" = false ] && [ "$(id -u)" -ne 0 ]; then
    fail "run with sudo (or use --dry-run to see what would be removed)"
fi

remove() {
    if [ "$dry" = true ]; then echo "would remove: $1"; else rm -f "$1"; fi
}

if [ -h "$link" ] && [ "$(readlink "$link")" = "$LINK_TARGET" ]; then
    remove "$link"
elif [ -e "$link" ] || [ -h "$link" ]; then
    echo "uninstall: $link was not made by CreateScript; it is kept" >&2
fi

pkgutil --files "$ID" | while IFS= read -r path; do
    path=${path#./}
    case $path in
        ""|/*|..|../*|*/..|*/../*) continue ;;
    esac
    if [ -f "$lib/$path" ] || [ -h "$lib/$path" ]; then remove "$lib/$path"; fi
done

if [ "$dry" = true ]; then
    echo "would remove the empty directories under $lib, $lib itself, and the receipt $ID"
    exit 0
fi

pkgutil --only-dirs --files "$ID" | sort -r | while IFS= read -r path; do
    path=${path#./}
    case $path in
        ""|.|/*|..|../*|*/..|*/../*) continue ;;
    esac
    if [ -d "$lib/$path" ] && [ ! -h "$lib/$path" ]; then rmdir "$lib/$path" 2>/dev/null || true; fi
done
if [ -d "$lib" ] && ! rmdir "$lib" 2>/dev/null; then
    echo "uninstall: $lib is kept because it contains files that CreateScript did not install" >&2
fi
pkgutil --forget "$ID" >/dev/null
echo "CreateScript was removed."
