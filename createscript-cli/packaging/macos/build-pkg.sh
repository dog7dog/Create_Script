#!/bin/sh
# Builds the macOS installer package of CreateScript (Stage 3.12 S5.2).
#
#   JAVA_HOME=<JDK 21 for macOS> createscript-cli/packaging/macos/build-pkg.sh
#
# The CPU of the package is the CPU of the JDK: an arm64 (Apple Silicon) JDK makes the arm64 package and
# an x86_64 (Intel) JDK makes the x86_64 package. Each package holds only one runtime.
#
# Output (createscript-cli/build/packages/):
#   createscript-<version>-macos-<arch>-unsigned.pkg          installs /usr/local/lib/createscript and
#                                                            the link /usr/local/bin/createscript
#   createscript-<version>-macos-<arch>-unsigned.pkg.sha256
#
# The package is not signed or notarized. It is for local tests; it is not ready for public distribution
# (see "Signing and notarization" in createscript-cli/README.md).
set -eu

ID=com.creatrbypc.createscript

fail() {
    echo "build-pkg: $*" >&2
    exit 1
}

[ "$(uname -s)" = Darwin ] || fail "run this on macOS"
[ -n "${JAVA_HOME:-}" ] || fail "set JAVA_HOME to a JDK 21 for macOS"
command -v pkgbuild >/dev/null && command -v productbuild >/dev/null || fail "pkgbuild and productbuild are required"

here=$(cd -P -- "$(dirname -- "$0")" && pwd -P)
cli=$(cd -P -- "$here/../.." && pwd -P)
repo=$(cd -P -- "$cli/.." && pwd -P)
version=$(sed -n 's/^createscript_version=//p' "$repo/gradle.properties")
[ -n "$version" ] || fail "createscript_version is not set in gradle.properties"

work=$cli/build/packaging/macos
packages=$cli/build/packages
rm -rf -- "$work"
mkdir -p "$work" "$packages"

result=$("$here/../common/build-image.sh" "$JAVA_HOME" "$work/root")
arch=${result##* }
name=createscript-$version-macos-$arch-unsigned

cp "$here/uninstall.sh" "$work/root/uninstall.sh"
chmod 755 "$work/root/uninstall.sh"
touch -h -r "$work/root/BUILD-INFO.txt" "$work/root/uninstall.sh"

# The lowest macOS that the runtime supports (LC_BUILD_VERSION minos of runtime/bin/java)
minos=$(otool -l "$work/root/runtime/bin/java" | awk '$1 == "minos" { print $2; exit }')
[ -n "$minos" ] || fail "could not read the minimum macOS version of the runtime"

mkdir -p "$work/scripts"
for script in preinstall postinstall; do
    sed "s|@ARCH@|$arch|" "$here/scripts/$script" > "$work/scripts/$script"
    chmod 755 "$work/scripts/$script"
done

touch -h -r "$work/root/BUILD-INFO.txt" "$work/root"
pkgbuild \
    --root "$work/root" \
    --identifier "$ID" \
    --version "$version" \
    --install-location /usr/local/lib/createscript \
    --scripts "$work/scripts" \
    --ownership recommended \
    "$work/createscript-component.pkg" >&2

# pkgbuild stores the extended attributes of the files as AppleDouble (._*) files in the payload. macOS adds
# com.apple.provenance to files that apps make, and it cannot be removed. So the payload and its Bom are made
# again from the same files with cpio (owner root, group wheel) and mkbom, and so is the scripts archive.
# pkgbuild's PackageInfo is kept, and the component package (a xar archive) is made again with xar.
expanded=$work/component
pkgutil --expand "$work/createscript-component.pkg" "$expanded"
(cd "$work/root" && find . | LC_ALL=C sort | cpio -o --format odc -R 0:0 2>/dev/null) | gzip -n -9 > "$expanded/Payload"
rm -r -- "$expanded/Scripts"
(cd "$work/scripts" && find . | LC_ALL=C sort | cpio -o --format odc -R 0:0 2>/dev/null) | gzip -n -9 > "$expanded/Scripts"
mkbom "$work/root" "$work/files.bom"
lsbom "$work/files.bom" | awk -F'\t' 'BEGIN { OFS = "\t" } { $3 = "0/0"; print }' > "$work/files.txt"
rm -f -- "$expanded/Bom"
mkbom -i "$work/files.txt" "$expanded/Bom"
count=$(cd "$work/root" && find . | wc -l | tr -d ' ')
kbytes=$(du -sk "$work/root" | awk '{ print $1 }')
sed -i '' -e "s/numberOfFiles=\"[0-9]*\"/numberOfFiles=\"$count\"/" -e "s/installKBytes=\"[0-9]*\"/installKBytes=\"$kbytes\"/" "$expanded/PackageInfo"
rm -f -- "$work/createscript-component.pkg"
(cd "$expanded" && xar --compression none -cf "$work/createscript-component.pkg" PackageInfo Bom Payload Scripts)

# Check the payload: the same files, no AppleDouble files, owner root
listed=$(gunzip -dc < "$expanded/Payload" | cpio -it 2>/dev/null | wc -l | tr -d ' ')
[ "$listed" = "$count" ] || fail "the payload has $listed entries, expected $count"
if lsbom -s "$expanded/Bom" | grep -q '/\._'; then fail "the Bom lists AppleDouble files"; fi
scripts=$(gunzip -dc < "$expanded/Scripts" | cpio -it 2>/dev/null | LC_ALL=C sort | tr '\n' ' ')
[ "$scripts" = ". ./postinstall ./preinstall " ] || fail "unexpected scripts archive: $scripts"
if lsbom -p U "$expanded/Bom" | grep -v -q '^root$'; then fail "the Bom lists files not owned by root"; fi

cat > "$work/distribution.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<installer-gui-script minSpecVersion="2">
    <title>CreateScript $version ($arch)</title>
    <options hostArchitectures="$arch" customize="never" require-scripts="false"/>
    <domains enable_localSystem="true" enable_currentUserHome="false" enable_anywhere="false"/>
    <volume-check>
        <allowed-os-versions>
            <os-version min="$minos"/>
        </allowed-os-versions>
    </volume-check>
    <choices-outline>
        <line choice="default">
            <line choice="$ID"/>
        </line>
    </choices-outline>
    <choice id="default"/>
    <choice id="$ID" visible="false">
        <pkg-ref id="$ID"/>
    </choice>
    <pkg-ref id="$ID" version="$version" onConclusion="none">createscript-component.pkg</pkg-ref>
</installer-gui-script>
EOF

rm -f -- "$packages/$name.pkg" "$packages/$name.pkg.sha256"
productbuild --distribution "$work/distribution.xml" --package-path "$work" "$packages/$name.pkg" >&2
(cd "$packages" && shasum -a 256 "$name.pkg" > "$name.pkg.sha256")

echo "$packages/$name.pkg"
cat "$packages/$name.pkg.sha256"
