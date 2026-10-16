#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
cd "$(dirname "$0")/.."
mode="${1:-debug}"
if [[ "$mode" != debug && "$mode" != release ]]; then
  echo 'Usage: scripts/build.sh [debug|release]' >&2; exit 2
fi
export TOOLCHAIN_DIR="${TOOLCHAIN_DIR:-$PWD/.tools}"
# GitHub runners export their own JAVA_HOME/ANDROID_HOME. Always use the
# checksum-pinned toolchain, including when those unrelated tools are installed.
export JAVA_HOME="$TOOLCHAIN_DIR/jdk-21.0.8+9"
export ANDROID_HOME="$TOOLCHAIN_DIR/android-sdk"
export PATH="$JAVA_HOME/bin:$PATH"
tools="$ANDROID_HOME/build-tools/36.1.0"
android_jar="$ANDROID_HOME/platforms/android-34/android.jar"
export SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-$(git log -1 --format=%ct)}"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/gen" "$work/classes" "$work/dex" build
"$tools/aapt2" compile --dir res -o "$work/res.zip"
"$tools/aapt2" link -o "$work/resources.apk" -I "$android_jar" --manifest AndroidManifest.xml -R "$work/res.zip" --java "$work/gen" --auto-add-overlay --min-sdk-version 21 --target-sdk-version 34
find src "$work/gen" -name '*.java' -print | LC_ALL=C sort > "$work/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -classpath "$android_jar" -d "$work/classes" @"$work/sources.txt"
jar cf "$work/classes.jar" -C "$work/classes" .
"$tools/d8" --min-api 21 --lib "$android_jar" --output "$work/dex" "$work/classes.jar"
# Canonical ZIP entries/timestamps: same sources + toolchain + key -> same APK.
python3 - "$work" <<'PY'
import datetime, os, pathlib, sys, zipfile
work=pathlib.Path(sys.argv[1])
stamp=datetime.datetime.fromtimestamp(max(315532800,int(os.environ['SOURCE_DATE_EPOCH'])),datetime.timezone.utc).timetuple()[:6]
with zipfile.ZipFile(work/'resources.apk') as src, zipfile.ZipFile(work/'unsigned.apk','w') as dest:
    entries={name:src.read(name) for name in src.namelist()}
    entries['classes.dex']=(work/'dex/classes.dex').read_bytes()
    for name in sorted(entries):
        info=zipfile.ZipInfo(name, stamp)
        info.compress_type=zipfile.ZIP_DEFLATED
        info.external_attr=0o100644 << 16
        dest.writestr(info, entries[name], compresslevel=9)
PY
"$tools/zipalign" -f -p 4 "$work/unsigned.apk" "$work/aligned.apk"
if [[ "$mode" == release ]]; then
  : "${APK_SIGNING_KEYSTORE:?Set APK_SIGNING_KEYSTORE to the external permanent keystore}"
  : "${APK_SIGNING_STORE_PASSWORD:?Missing signing store password}"
  : "${APK_SIGNING_KEY_PASSWORD:?Missing signing key password}"
  : "${APK_SIGNING_KEY_ALIAS:?Missing signing alias}"
  output=build/homeonfire-plex-test.apk
  "$tools/apksigner" sign --ks "$APK_SIGNING_KEYSTORE" --ks-key-alias "$APK_SIGNING_KEY_ALIAS" --ks-pass env:APK_SIGNING_STORE_PASSWORD --key-pass env:APK_SIGNING_KEY_PASSWORD --v4-signing-enabled false --out "$output" "$work/aligned.apk"
else
  mkdir -p .local-signing
  if ! test -f .local-signing/debug.keystore; then
    keytool -genkeypair -keystore .local-signing/debug.keystore -storepass android -keypass android -alias androiddebugkey -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
    chmod 600 .local-signing/debug.keystore
  fi
  output=build/homeonfire-plex-test-debug.apk
  "$tools/apksigner" sign --ks .local-signing/debug.keystore --ks-pass pass:android --v4-signing-enabled false --out "$output" "$work/aligned.apk"
fi
"$tools/apksigner" verify --verbose --print-certs "$output"
"$tools/zipalign" -c -p 4 "$output"
"$tools/aapt2" dump badging "$output" > "$output.badging.txt"
python3 scripts/verify-apk.py "$output"
sha256sum "$output" > "$output.sha256"
echo "Built and verified: $output"
