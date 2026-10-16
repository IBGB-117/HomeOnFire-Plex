#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}"
output=$(mktemp -d)
trap 'rm -rf "$output"' EXIT
package=io/github/ibgb117/homeonfireplex/test
"${java_bin}javac" --release 8 -Xlint:-options -d "$output" "src/$package/AutostartSession.java" "src/$package/ForegroundPolicy.java" "tests/$package/AutostartSessionTest.java"
"${java_bin}java" -ea -cp "$output" io.github.ibgb117.homeonfireplex.test.AutostartSessionTest
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests -p 'test_apk_packaging.py' -v
