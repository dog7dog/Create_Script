#!/bin/sh
# Assembles the CreateScript application directory used by the macOS and Linux packages (Stage 3.12 S5.2).
#
#   build-image.sh <jdk-home> <output-dir>
#
# <jdk-home>    A JDK 21 for the operating system this script runs on. Its CPU architecture decides the
#               architecture of the package. The runtime is made with that JDK's own jlink and jmods,
#               so a runtime for another operating system is never made here.
# <output-dir>  Must not exist yet. It receives:
#                 bin/createscript   launcher (POSIX sh)
#                 lib/               createscript-cli, createscript-core, kotlin-stdlib, annotations, jline-reader,
#                                    jline-terminal
#                 runtime/           minimal Java runtime made by jlink (runtime/legal holds its licenses)
#                 legal/             CreateScript's NOTICE, THIRD-PARTY-NOTICES.md, the Apache License 2.0,
#                                    the JLine license and JAVA-RUNTIME-SOURCE.txt (where to get the source
#                                    code of the Java runtime)
#                 BUILD-INFO.txt     version, OS, CPU, JDK vendor and version, modules, commit
#
# Prints "<os> <arch>" (for example "macos arm64") on the last line of standard output.
set -eu

if [ "$#" -ne 2 ]; then
    echo "usage: build-image.sh <jdk-home> <output-dir>" >&2
    exit 64
fi
jdk=$1
out=$2

here=$(cd -P -- "$(dirname -- "$0")" && pwd -P)
repo=$(cd -P -- "$here/../../.." && pwd -P)
cli=$repo/createscript-cli

fail() {
    echo "build-image: $*" >&2
    exit 1
}

# ---- the JDK ----
release=$jdk/release
[ -f "$release" ] || fail "not a JDK (no release file): $jdk"
field() {
    sed -n "s/^$1=\"\(.*\)\"\$/\1/p" "$release"
}
java_version=$(field JAVA_VERSION)
os_name=$(field OS_NAME)
os_arch=$(field OS_ARCH)
implementor=$(field IMPLEMENTOR)
implementor_version=$(field IMPLEMENTOR_VERSION)
runtime_version=$(field JAVA_RUNTIME_VERSION)
semantic_version=$(field SEMANTIC_VERSION)
source_repo=$(field SOURCE_REPO)
source_commit=$(field SOURCE)
case $java_version in
    21|21.*) ;;
    *) fail "JDK 21 is required (JAVA_VERSION=$java_version): $jdk" ;;
esac
[ -x "$jdk/bin/jlink" ] && [ -x "$jdk/bin/jdeps" ] || fail "jlink and jdeps are required: $jdk"
[ -f "$jdk/jmods/java.base.jmod" ] || fail "the JDK has no jmods directory: $jdk"

host_os=$(uname -s)
[ "$os_name" = "$host_os" ] || fail "the JDK is for $os_name but this machine runs $host_os (a runtime for another OS is not made here)"
case $os_name in
    Darwin) os=macos ;;
    Linux) os=linux ;;
    *) fail "unsupported OS: $os_name" ;;
esac
case $os_arch in
    aarch64|arm64) arch=arm64 ;;
    x86_64|amd64) arch=x86_64 ;;
    *) fail "unsupported CPU architecture: $os_arch" ;;
esac
if [ "$os" = linux ] && [ "$arch" = arm64 ]; then arch=aarch64; fi

[ ! -e "$out" ] && [ ! -h "$out" ] || fail "the output directory already exists: $out"

version=$(sed -n 's/^createscript_version=//p' "$repo/gradle.properties")
[ -n "$version" ] || fail "createscript_version is not set in gradle.properties"
commit=$(git -C "$repo" rev-parse --verify -q HEAD 2>/dev/null || echo unknown)
if [ -n "$(git -C "$repo" status --porcelain 2>/dev/null)" ]; then commit="$commit (working tree has changes)"; fi
epoch=${SOURCE_DATE_EPOCH:-$(git -C "$repo" log -1 --format=%ct 2>/dev/null || echo 0)}

# ---- the CLI (the same files as ./gradlew :createscript-cli:installDist) ----
JAVA_HOME=$jdk "$repo/gradlew" -p "$repo" --quiet :createscript-cli:installDist >&2
dist=$cli/build/install/createscript
script=$dist/bin/createscript
classpath=$(sed -n 's/^CLASSPATH=//p' "$script")
[ -n "$classpath" ] || fail "no CLASSPATH line in $script"

mkdir -p "$out/bin" "$out/lib" "$out/legal"
jars=""
old_ifs=$IFS
IFS=:
for entry in $classpath; do
    name=${entry#\$APP_HOME/lib/}
    case $name in
        */*|"") fail "unexpected class path entry: $entry" ;;
    esac
    cp "$dist/lib/$name" "$out/lib/$name"
    jars="$jars $name"
done
IFS=$old_ifs

# Only the CLI, Core and their Kotlin libraries: nothing from Minecraft, NeoForge, Create or Kotlin for Forge
for name in $jars; do
    if unzip -Z1 "$out/lib/$name" | grep -E -i -q '^(net/minecraft|net/neoforged|com/mojang|com/simibubi|thedarkcolour)/|neoforge\.mods\.toml|mods\.toml'; then
        fail "$name contains Minecraft, NeoForge, Create or Kotlin for Forge files"
    fi
done
for name in createscript-cli.jar createscript-core.jar; do
    other=$(unzip -Z1 "$out/lib/$name" | grep -v -E '^(META-INF/|com/$|com/creatrbypc/$|com/creatrbypc/createanalytical/$|com/creatrbypc/createanalytical/(script|cli)/)' || true)
    [ -z "$other" ] || fail "$name contains files outside the CreateScript packages: $other"
done

launcher_classpath=$(printf '%s' "$classpath" | sed 's|\$APP_HOME|$app|g')
sed "s|@CLASSPATH@|$launcher_classpath|" "$here/createscript-launcher.sh" > "$out/bin/createscript"
chmod 755 "$out/bin/createscript"

# ---- the Java runtime ----
modules=$("$jdk/bin/jdeps" --multi-release 21 --ignore-missing-deps --print-module-deps "$out/lib/"*.jar)
[ -n "$modules" ] || fail "jdeps found no modules"
"$jdk/bin/jlink" \
    --module-path "$jdk/jmods" \
    --add-modules "$modules" \
    --strip-debug --no-header-files --no-man-pages --compress=zip-6 \
    --output "$out/runtime" >&2
[ -f "$out/runtime/legal/java.base/LICENSE" ] || fail "the runtime has no license files"

# ---- licenses and build information ----
[ -f "$repo/NOTICE" ] || fail "no NOTICE file at the repository root"
cp "$repo/NOTICE" "$out/legal/NOTICE"
cp "$here/THIRD-PARTY-NOTICES.md" "$out/legal/THIRD-PARTY-NOTICES.md"
cp "$here/licenses/APACHE-2.0.txt" "$out/legal/APACHE-2.0.txt"
cp "$here/licenses/BSD-3-CLAUSE-JLINE.txt" "$out/legal/BSD-3-CLAUSE-JLINE.txt"
# The source code of the Java runtime, as recorded in the release file of the JDK that made it.
# SOURCE is ".:git:<commit>" (the commit of the OpenJDK sources in SOURCE_REPO)
{
    echo "The Java runtime in runtime/ was made with jlink from this JDK, without changes to its code:"
    echo
    echo "  $implementor $implementor_version ($runtime_version, $os_name $os_arch)"
    echo
    echo "It is licensed under the GNU General Public License, version 2, with the Classpath Exception"
    echo "(runtime/legal/java.base/LICENSE and ASSEMBLY_EXCEPTION), and the third-party licenses in runtime/legal/."
    echo
    echo "Source code of this exact build:"
    if [ -n "$source_repo" ]; then echo "  repository: $source_repo"; fi
    case $source_commit in
        *git:*) echo "  commit:     ${source_commit##*git:}" ;;
    esac
    if [ "$implementor" = "Eclipse Adoptium" ] && [ -n "$semantic_version" ]; then
        tag=jdk-$semantic_version
        echo "  release:    https://github.com/adoptium/temurin${java_version%%.*}-binaries/releases/tag/$(printf '%s' "$tag" | sed 's/+/%2B/g')"
        echo "              (the OpenJDK21U-jdk-sources_*.tar.gz file of that release holds the complete source code)"
    fi
    if [ -z "$source_repo" ] && [ "$implementor" != "Eclipse Adoptium" ]; then
        echo "  the JDK does not record its source repository; ask its vendor ($implementor) for the source code"
    fi
} > "$out/legal/JAVA-RUNTIME-SOURCE.txt"
{
    echo "CreateScript $version"
    echo "os: $os"
    echo "arch: $arch"
    echo "java runtime: $implementor $implementor_version ($runtime_version, $os_name $os_arch)"
    echo "java modules: $modules"
    echo "jlink options: --strip-debug --no-header-files --no-man-pages --compress=zip-6"
    echo "libraries:$jars"
    echo "commit: $commit"
    echo "source date: $epoch"
} > "$out/BUILD-INFO.txt"

# The package must not contain sources, build scripts, secrets or Gradle files
# (runtime/ is the jlink output; its conf/*.properties files belong to the Java runtime)
unexpected=$(cd "$out" && find . -path ./runtime/conf -prune -o \( -name '*.kt' -o -name '*.gradle' -o -name '*.properties' -o -name '.env*' -o -name '*.pem' -o -name '*.key' -o -name '*.p12' -o -name '.git*' -o -name '.DS_Store' \) -print)
[ -z "$unexpected" ] || fail "unexpected files in the package: $unexpected"
absolute_links=$(cd "$out" && find . -type l -exec sh -c 'case $(readlink "$1") in /*) echo "$1";; esac' sh {} \;)
[ -z "$absolute_links" ] || fail "absolute symbolic links in the package: $absolute_links"

# Permissions: directories 755, files 644, executables 755. Timestamps: the source date
chmod -R u+rwX,go+rX,go-w "$out"
if [ "$os" = macos ]; then stamp=$(date -u -r "$epoch" +%Y%m%d%H%M.%S); else stamp=$(date -u -d "@$epoch" +%Y%m%d%H%M.%S); fi
TZ=UTC0 find "$out" -exec touch -h -t "$stamp" {} +

echo "$os $arch"
