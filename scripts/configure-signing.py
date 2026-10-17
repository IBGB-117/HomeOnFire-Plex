#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Prepare a permanent TEST key on a persistent PC, verify its backup, upload securely.
Never use a production/upstream key. No key or password is printed.
"""
import argparse
import base64
import hashlib
import os
import pathlib
import secrets
import subprocess

ALIAS = 'homeonfireplex-test'
KEY = 'homeonfire-plex-test.p12'
PASSWORD = 'store-password'
PIN = 'APK_SIGNING_CERT_SHA256'
NAMES = ['APK_SIGNING_KEYSTORE_BASE64', 'APK_SIGNING_STORE_PASSWORD',
         'APK_SIGNING_KEY_PASSWORD', 'APK_SIGNING_KEY_ALIAS']


def external_directory(path):
    path = path.expanduser().resolve()
    # Cloud sandboxes may have empty protected .git marker directories outside
    # checkouts. A real checkout has a .git file (worktree) or a .git/HEAD file.
    if any((parent / '.git').is_file() or (parent / '.git/HEAD').is_file()
           for parent in (path, *path.parents)):
        raise ValueError('Signing material and backups must be outside every Git checkout.')
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    path.chmod(0o700)
    return path


def keytool(arguments, password):
    executable = str(pathlib.Path(os.environ['JAVA_HOME']) / 'bin/keytool') if os.environ.get('JAVA_HOME') else 'keytool'
    return subprocess.run([executable, *arguments], check=True, capture_output=True,
                          env=dict(os.environ, APK_KEY_PASSWORD=password))


def certificate(directory):
    result = keytool(['-exportcert', '-keystore', str(directory / KEY),
                      '-storepass:env', 'APK_KEY_PASSWORD', '-alias', ALIAS],
                     (directory / PASSWORD).read_text())
    return hashlib.sha256(result.stdout).hexdigest()


def prepare_key(directory):
    files = [directory / KEY, directory / PASSWORD]
    if any(p.exists() for p in files) and not all(p.is_file() for p in files):
        raise ValueError('Incomplete signing directory; refusing to overwrite it.')
    if not files[0].exists():
        password = secrets.token_urlsafe(48)
        # Exclusive writes preserve an existing identity, including after failed setup.
        with files[1].open('x') as stream:
            stream.write(password)
        keytool(['-genkeypair', '-storetype', 'PKCS12', '-keystore', str(files[0]),
                 '-storepass:env', 'APK_KEY_PASSWORD', '-keypass:env', 'APK_KEY_PASSWORD',
                 '-alias', ALIAS, '-dname', 'CN=HomeOnFire Plex Test,O=IBGB-117',
                 '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000'], password)
    for path in files:
        path.chmod(0o600)
    return certificate(directory)


def backup_key(directory, backup, fingerprint):
    if directory == backup or directory in backup.parents or backup in directory.parents:
        raise ValueError('Use two separate, non-nested signing and backup directories.')
    for name in (KEY, PASSWORD):
        source, destination = directory / name, backup / name
        if destination.exists():
            if destination.read_bytes() != source.read_bytes():
                raise ValueError('Backup differs from the active key; refusing to overwrite it.')
        else:
            with destination.open('xb') as stream:
                stream.write(source.read_bytes())
        destination.chmod(0o600)
    if certificate(backup) != fingerprint:
        raise ValueError('Backup certificate verification failed.')
    # This fingerprint is public; it does not contain key or password material.
    for target in (directory, backup):
        (target / 'certificate-sha256.txt').write_text(fingerprint + '\n')
    print('Backup bytes, password and public signing certificate verified.')


def upload(directory, repository, fingerprint):
    def gh(*arguments, value=None):
        return subprocess.run(['gh', *arguments], input=value, check=True,
                              capture_output=True).stdout.decode().strip()

    existing = gh('api', '--paginate', f'repos/{repository}/actions/secrets',
                  '--jq', '.secrets[].name').splitlines()
    pin = gh('api', '--paginate', f'repos/{repository}/actions/variables', '--jq',
             '.variables[] | select(.name == "APK_SIGNING_CERT_SHA256") | .value')
    if pin and pin != fingerprint:
        raise ValueError('GitHub pins another signing identity; no key rotation was performed.')
    if set(existing).intersection(NAMES) and not pin:
        raise ValueError('Unpinned signing secrets already exist. Preserve them and investigate; no overwrite performed.')
    # Pin before writing secrets. Retrying an interrupted upload is allowed only
    # with the identical, backed-up key; an unrelated replacement is rejected.
    gh('variable', 'set', PIN, '--repo', repository, '--body', fingerprint)
    values = [base64.b64encode((directory / KEY).read_bytes()),
              (directory / PASSWORD).read_bytes(), (directory / PASSWORD).read_bytes(), ALIAS.encode()]
    for name, value in zip(NAMES, values):
        gh('secret', 'set', name, '--repo', repository, value=value)
    print('Four signing secrets and the public certificate pin saved to GitHub.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', required=True, type=pathlib.Path)
    parser.add_argument('--backup-directory', type=pathlib.Path)
    parser.add_argument('--repository', default='IBGB-117/HomeOnFire-Plex')
    parser.add_argument('--upload', action='store_true')
    args = parser.parse_args()
    if args.upload and not args.backup_directory:
        parser.error('--upload requires --backup-directory; keep these on your persistent PC, not Codex Cloud.')
    os.umask(0o077)
    try:
        directory = external_directory(args.directory)
        backup = external_directory(args.backup_directory) if args.backup_directory else None
        fingerprint = prepare_key(directory)
        if backup:
            backup_key(directory, backup, fingerprint)
        if args.upload:
            upload(directory, args.repository, fingerprint)
        print('Public certificate SHA-256: ' + fingerprint)
        print('Keep both private files (keystore AND store-password) secure; never commit or post them.')
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        # Do not echo subprocess arguments, input or secret-bearing diagnostics.
        if isinstance(error, subprocess.CalledProcessError):
            parser.exit(1, 'Setup failed: keytool or GitHub access failed. Existing local key files were preserved. Check Java, gh login and repository Secrets/Variables permissions, then retry the same directories.\n')
        parser.exit(1, str(error) + '\n')


if __name__ == '__main__':
    main()
