#!/bin/sh
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MAVEN_BIN="$SCRIPT_DIR/../.tools/apache-maven-3.9.9/bin/mvn"
if [ -f "$MAVEN_BIN" ]; then
    exec "$MAVEN_BIN" "$@"
else
    exec mvn "$@"
fi
