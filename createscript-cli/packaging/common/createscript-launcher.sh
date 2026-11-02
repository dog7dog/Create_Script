#!/bin/sh
# CreateScript launcher (Stage 3.12 S5.2).
# Starts the CLI with the Java runtime bundled next to this script (../runtime).
# JAVA_HOME and the java on PATH are not used. Symbolic links to this script
# (for example /usr/local/bin/createscript or ~/.local/bin/createscript) are followed.
# build-image.sh replaces the class path placeholder below when it assembles the package.

self=$0
hops=0
while [ -h "$self" ]; do
    hops=$((hops + 1))
    if [ "$hops" -gt 40 ]; then
        echo "createscript: too many levels of symbolic links: $0" >&2
        exit 1
    fi
    target=$(readlink -- "$self") || exit 1
    case $target in
        /*) self=$target ;;
        *) self=$(dirname -- "$self")/$target ;;
    esac
done

app=$(cd -P -- "$(dirname -- "$self")/.." && pwd -P) || exit 1
case $app in
    *:*)
        # ':' separates class path entries, so the installation directory must not contain it
        echo "createscript: the installation directory must not contain ':': $app" >&2
        exit 1
        ;;
esac

java=$app/runtime/bin/java
if [ ! -x "$java" ]; then
    echo "createscript: the bundled Java runtime is missing: $java" >&2
    echo "createscript: reinstall CreateScript" >&2
    exit 1
fi

exec "$java" -classpath "@CLASSPATH@" com.creatrbypc.createanalytical.cli.MainKt "$@"
