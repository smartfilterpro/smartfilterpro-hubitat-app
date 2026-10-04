#!/usr/bin/env bash
# Run the app's tests off the hub: tests/runtime_checkpoints_test.groovy
# loads SmartFilterProHubitatApp.groovy through tests/HubitatHarness.groovy.
#
# Needs Java (11+). Downloads groovy-all 2.4.21 (Hubitat runs Groovy 2.4)
# from Maven Central into tests/.cache on first use, or uses GROOVY_JAR.
#
#   tests/run_tests.sh [path/to/SmartFilterProHubitatApp.groovy]
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$(dirname "$here")"
jar="${GROOVY_JAR:-$here/.cache/groovy-all-2.4.21.jar}"
if [ ! -f "$jar" ]; then
  mkdir -p "$(dirname "$jar")"
  curl -sSfL -o "$jar.tmp" https://repo1.maven.org/maven2/org/codehaus/groovy/groovy-all/2.4.21/groovy-all-2.4.21.jar
  mv "$jar.tmp" "$jar"
fi

app="${1:-$root/SmartFilterProHubitatApp.groovy}"
cd "$root"
exec java -cp "$jar:$here" groovy.ui.GroovyMain "$here/runtime_checkpoints_test.groovy" "$app"
