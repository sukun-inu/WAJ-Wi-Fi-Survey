#!/usr/bin/env bash
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export JAVA_HOME="$SCRIPT_DIR/.tools/jdk21"
export M2_HOME="$SCRIPT_DIR/.tools/maven"
export PATH="$JAVA_HOME/bin:$M2_HOME/bin:$PATH"
exec "$M2_HOME/bin/mvn" "$@"
