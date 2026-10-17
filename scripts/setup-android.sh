#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
cd "$(dirname "$0")/.."
export TOOLCHAIN_DIR="${TOOLCHAIN_DIR:-$PWD/.tools}"
mkdir -p "$TOOLCHAIN_DIR/downloads" "$TOOLCHAIN_DIR/android-sdk"
cd "$TOOLCHAIN_DIR/downloads"
fetch() {
  local url="$1" file="$2" checksum="$3"
  if ! test -f "$file" || ! echo "$checksum  $file" | sha256sum -c --status; then
    curl --fail --location --retry 3 "$url" -o "$file"
  fi
  echo "$checksum  $file" | sha256sum -c
}
fetch 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.8%2B9/OpenJDK21U-jdk_x64_linux_hotspot_21.0.8_9.tar.gz' jdk.tar.gz f2dc5418092c43003db8f9005c4a286e1c0104fea96ccdd49e8ebd037cac9219
if ! test -x ../jdk-21.0.8+9/bin/javac; then tar -xzf jdk.tar.gz -C ..; fi
fetch https://dl.google.com/android/repository/build-tools_r36.1_linux.zip build-tools.zip a7b5889e4a79fcf3b0976bef40d401f4240fb1eed891d9d91169da1111e11d78
fetch https://dl.google.com/android/repository/platform-34-ext12_r01.zip platform.zip b8349ff89d0bc40d26c712dcc5eb36ee60f40e203d7589bd4599b9237e98329a
python3 - <<'PY'
import os, pathlib, zipfile
root=pathlib.Path(os.environ['TOOLCHAIN_DIR'])/'android-sdk'
for archive, target in [('build-tools.zip','build-tools/36.1.0'),('platform.zip','platforms/android-34')]:
    dest=root/target
    if dest.exists():
        continue
    staging=dest.with_name(dest.name+'.extracting')
    staging.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as z:
        for info in z.infolist():
            parts=pathlib.PurePosixPath(info.filename).parts[1:]
            if not parts:
                continue
            if '..' in parts:
                raise ValueError('Unsafe archive path')
            out=staging.joinpath(*parts)
            if info.is_dir():
                out.mkdir(parents=True, exist_ok=True)
            else:
                out.parent.mkdir(parents=True, exist_ok=True)
                out.write_bytes(z.read(info))
                mode=info.external_attr >> 16
                if mode:
                    out.chmod(mode & 0o777)
    staging.rename(dest)
PY
"$TOOLCHAIN_DIR/jdk-21.0.8+9/bin/javac" -version
"$TOOLCHAIN_DIR/android-sdk/build-tools/36.1.0/aapt2" version
