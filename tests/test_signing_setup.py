# SPDX-License-Identifier: GPL-3.0-or-later
"""Signing safety: verified backups, no rotation, safe retries, pinned APK identity."""
import importlib.util
import contextlib
import io
import pathlib
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[1]


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / filename)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


signing = module('signing', 'configure-signing.py')
verifier = module('verify_apk', 'verify-apk.py')


class SigningSetupTest(unittest.TestCase):
    def test_rejects_secrets_inside_checkout(self):
        with self.assertRaisesRegex(ValueError, 'outside every Git checkout'):
            signing.external_directory(ROOT / '.local-signing' / 'permanent')

    def test_backup_requires_identical_bytes_and_separate_directory(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = pathlib.Path(temporary) / 'key'
            backup = pathlib.Path(temporary) / 'backup'
            directory.mkdir()
            backup.mkdir()
            (directory / signing.KEY).write_bytes(b'private-test-data')
            (directory / signing.PASSWORD).write_bytes(b'private-test-password')
            with patch.object(signing, 'certificate', return_value='a' * 64), contextlib.redirect_stdout(io.StringIO()):
                signing.backup_key(directory, backup, 'a' * 64)
                signing.backup_key(directory, backup, 'a' * 64)
                self.assertEqual((backup / signing.KEY).stat().st_mode & 0o777, 0o600)
                (backup / signing.PASSWORD).write_bytes(b'different-backup')
                with self.assertRaisesRegex(ValueError, 'refusing to overwrite'):
                    signing.backup_key(directory, backup, 'a' * 64)
                with self.assertRaisesRegex(ValueError, 'separate'):
                    signing.backup_key(directory, directory, 'a' * 64)

    def upload(self, names, pin):
        with tempfile.TemporaryDirectory() as temporary:
            directory = pathlib.Path(temporary)
            (directory / signing.KEY).write_bytes(b'test-keystore')
            (directory / signing.PASSWORD).write_bytes(b'test-password')
            def gh(arguments, **kwargs):
                if 'api' in arguments:
                    output = pin if 'variables' in arguments[3] else names
                    return subprocess.CompletedProcess(arguments, 0, stdout=output.encode())
                return subprocess.CompletedProcess(arguments, 0, stdout=b'')
            with patch.object(signing.subprocess, 'run', side_effect=gh) as runner, contextlib.redirect_stdout(io.StringIO()):
                try:
                    signing.upload(directory, 'owner/repository', 'a' * 64)
                finally:
                    self.calls = runner.call_args_list

    def test_existing_unpinned_secrets_are_never_overwritten(self):
        with self.assertRaisesRegex(ValueError, 'Unpinned'):
            self.upload(signing.NAMES[0], '')
        self.assertEqual(len(self.calls), 2)

    def test_another_key_pin_is_never_rotated(self):
        with self.assertRaisesRegex(ValueError, 'another signing identity'):
            self.upload('', 'b' * 64)
        self.assertEqual(len(self.calls), 2)

    def test_partial_upload_can_resume_only_with_same_identity(self):
        self.upload(signing.NAMES[0], 'a' * 64)
        self.assertEqual(len(self.calls), 7)
        secret_calls = self.calls[3:]
        self.assertEqual([c.args[0][3] for c in secret_calls], signing.NAMES)
        self.assertTrue(all('test-password' not in str(c.args) for c in secret_calls))
        self.assertEqual(secret_calls[1].kwargs['input'], b'test-password')

    def test_certificate_pin_rejects_missing_changed_or_multiple_signers(self):
        report = 'Signer #1 certificate SHA-256 digest: ' + 'a' * 64 + '\n'
        verifier.verify_signing_certificate(report, 'A' * 64)
        for expected, actual in [('', report), ('b' * 64, report),
                                 ('a' * 64, ''), ('a' * 64, report + report)]:
            with self.assertRaises(ValueError):
                verifier.verify_signing_certificate(actual, expected)

    def test_real_key_backup_is_reusable_without_printing_password(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            command = [sys.executable, str(ROOT / 'scripts/configure-signing.py'),
                       '--directory', str(root / 'primary'),
                       '--backup-directory', str(root / 'backup')]
            first = subprocess.run(command, check=True, capture_output=True, text=True)
            original = (root / 'primary' / signing.KEY).read_bytes()
            second = subprocess.run(command, check=True, capture_output=True, text=True)
            self.assertEqual(original, (root / 'backup' / signing.KEY).read_bytes())
            self.assertEqual(original, (root / 'primary' / signing.KEY).read_bytes())
            self.assertEqual(signing.certificate(root / 'backup'), signing.certificate(root / 'primary'))
            password = (root / 'primary' / signing.PASSWORD).read_text()
            self.assertNotIn(password, first.stdout + first.stderr + second.stdout + second.stderr)


if __name__ == '__main__':
    unittest.main()
