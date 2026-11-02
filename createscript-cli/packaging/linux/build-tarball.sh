#!/bin/sh
# Builds the Linux archive of CreateScript (Stage 3.12 S5.2). Run it on Linux.
#
#   JAVA_HOME=<JDK 21 for Linux> createscript-cli/packaging/linux/build-tarball.sh
#
# The CPU of the archive is the CPU of the JDK, which must be the CPU of this machine
# (x86_64 or aarch64). The Java runtime is made with that JDK's own jlink.
# Needs GNU tar and GNU coreutils (for reproducible archives).
#
# Output (createscript-cli/build/packages/):
#   createscript-<version>-linux-<arch>.tar.gz          extracts to createscript/
#   createscript-<version>-linux-<arch>.tar.gz.sha256
set -eu

fail() {
    echo "build-tarball: $*" >&2
    exit 1
}

[ "$(uname -s)" = Linux ] || fail "run this on Linux (a Linux runtime is made only with a Linux JDK on Linux)"
[ -n "${JAVA_HOME:-}" ] || fail "set JAVA_HOME to a JDK 21 for Linux"
tar --version 2>/dev/null | grep -q 'GNU tar' || fail "GNU tar is required"

here=$(cd -P -- "$(dirname -- "$0")" && pwd -P)
cli=$(cd -P -- "$here/../.." && pwd -P)
repo=$(cd -P -- "$cli/.." && pwd -P)
version=$(sed -n 's/^createscript_version=//p' "$repo/gradle.properties")
[ -n "$version" ] || fail "createscript_version is not set in gradle.properties"

work=$cli/build/packaging/linux
packages=$cli/build/packages
rm -rf -- "$work"
mkdir -p "$work" "$packages"

result=$("$here/../common/build-image.sh" "$JAVA_HOME" "$work/createscript")
arch=${result##* }
host=$(uname -m)
case $host in
    amd64) host=x86_64 ;;
    arm64) host=aarch64 ;;
esac
[ "$arch" = "$host" ] || fail "the JDK is for $arch, but this machine is $host"
name=createscript-$version-linux-$arch

for file in install.sh uninstall.sh; do
    cp "$here/$file" "$work/createscript/$file"
    chmod 755 "$work/createscript/$file"
done
cp "$here/README.md" "$work/createscript/README.md"
chmod 644 "$work/createscript/README.md"

# The launcher starts the runtime in the archive
"$work/createscript/bin/createscript" --version >&2

epoch=$(sed -n 's/^source date: //p' "$work/createscript/BUILD-INFO.txt")
touch -h -d "@$epoch" "$work/createscript" "$work/createscript/install.sh" "$work/createscript/uninstall.sh" "$work/createscript/README.md"

rm -f -- "$packages/$name.tar.gz" "$packages/$name.tar.gz.sha256"
tar --sort=name --mtime="@$epoch" --owner=0 --group=0 --numeric-owner --format=gnu \
    -C "$work" -cf - createscript | gzip -n -9 > "$packages/$name.tar.gz"
(cd "$packages" && sha256sum "$name.tar.gz" > "$name.tar.gz.sha256")

echo "$packages/$name.tar.gz"
cat "$packages/$name.tar.gz.sha256"
