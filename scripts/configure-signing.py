#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Create an external permanent TEST key; optionally upload without printing secrets.
Never use a production/upstream key. Existing GitHub secret bindings are not replaced.
"""
import argparse
import base64
import os
import pathlib
import secrets
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--directory', required=True, type=pathlib.Path)
parser.add_argument('--repository', default='IBGB-117/HomeOnFire-Plex')
parser.add_argument('--upload', action='store_true')
args = parser.parse_args()
os.umask(0o077)
directory = args.directory.resolve()
repository_root = pathlib.Path(__file__).resolve().parents[1]
if directory == repository_root or repository_root in directory.parents:
    parser.error('Permanent signing material must be stored outside the repository')
names = ['APK_SIGNING_KEYSTORE_BASE64', 'APK_SIGNING_STORE_PASSWORD',
         'APK_SIGNING_KEY_PASSWORD', 'APK_SIGNING_KEY_ALIAS']
if args.upload:
    # Check access AND preserve existing keys before creating or replacing anything.
    result = subprocess.run(['gh', 'api', '--paginate',
        f'repos/{args.repository}/actions/secrets', '--jq', '.secrets[].name'],
        check=True, capture_output=True, text=True)
    if set(result.stdout.splitlines()).intersection(names):
        raise SystemExit('Existing signing secret bindings found. Preserve them; do not rotate the update key.')
    subprocess.run(['gh', 'api', f'repos/{args.repository}/actions/secrets/public-key',
                    '--silent'], check=True)
directory.mkdir(mode=0o700, parents=True, exist_ok=True)
key = directory / 'homeonfire-plex-test.p12'
password_file = directory / 'store-password'
expected = [key, password_file]
if any(p.exists() for p in expected) and not all(p.exists() for p in expected):
    raise SystemExit('Incomplete existing signing directory; refusing to overwrite it.')
if not key.exists():
    password_file.write_text(secrets.token_urlsafe(48))
    password_file.chmod(0o600)
    environment = dict(os.environ, APK_KEY_PASSWORD=password_file.read_text())
    java_bin = pathlib.Path(os.environ['JAVA_HOME']) / 'bin' if 'JAVA_HOME' in os.environ else None
    keytool = str(java_bin / 'keytool') if java_bin else 'keytool'
    subprocess.run([keytool, '-genkeypair', '-storetype', 'PKCS12', '-keystore', str(key),
        '-storepass:env', 'APK_KEY_PASSWORD', '-keypass:env', 'APK_KEY_PASSWORD',
        '-alias', 'homeonfireplex-test', '-dname', 'CN=HomeOnFire Plex Test,O=IBGB-117',
        '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000'], check=True, env=environment)
    key.chmod(0o600)
if args.upload:
    values = [base64.b64encode(key.read_bytes()), password_file.read_bytes(),
              password_file.read_bytes(), b'homeonfireplex-test']
    for name, value in zip(names, values):
        subprocess.run(['gh', 'secret', 'set', name, '--repo', args.repository],
                       input=value, check=True)
    print('Four permanent signing secret bindings saved to GitHub; values were not printed.')
else:
    print('Permanent test key prepared outside the repository. Back up the key and password securely.')
    print('GitHub upload was not performed. Use --upload with GitHub Actions Secrets write access.')
