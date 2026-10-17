# SPDX-License-Identifier: GPL-3.0-or-later
import importlib.util
import pathlib
import tempfile
import unittest
import zipfile

root = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('verify_apk', root / 'scripts/verify-apk.py')
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class ApkPackagingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.apk = pathlib.Path(self.temp.name) / 'test.apk'

    def test_rejects_compressed_resources_even_when_zip_is_valid(self):
        with zipfile.ZipFile(self.apk, 'w') as archive:
            archive.writestr('resources.arsc', b'resource table' * 100,
                             compress_type=zipfile.ZIP_DEFLATED)
        with zipfile.ZipFile(self.apk) as archive:
            self.assertIsNone(archive.testzip())
        with self.assertRaisesRegex(ValueError, 'must be uncompressed'):
            verifier.verify_resource_table(self.apk)

    def test_rejects_stored_but_misaligned_resources(self):
        with zipfile.ZipFile(self.apk, 'w') as archive:
            archive.writestr('prefix', b'x', compress_type=zipfile.ZIP_STORED)
            archive.writestr('resources.arsc', b'resource table', compress_type=zipfile.ZIP_STORED)
        with self.assertRaisesRegex(ValueError, '4-byte boundary'):
            verifier.verify_resource_table(self.apk)

    def test_accepts_stored_and_aligned_resources(self):
        # resources.arsc local header + filename = 44 bytes, already aligned.
        with zipfile.ZipFile(self.apk, 'w') as archive:
            archive.writestr('resources.arsc', b'resource table', compress_type=zipfile.ZIP_STORED)
        verifier.verify_resource_table(self.apk)

    def test_uses_actual_local_padding_instead_of_central_directory_extra(self):
        with zipfile.ZipFile(self.apk, 'w') as archive:
            # Valid five-byte ZIP extra field makes the payload offset 49.
            info = zipfile.ZipInfo('resources.arsc')
            info.extra = b'\xfe\xca\x01\x00\x00'
            archive.writestr(info, b'resource table')
        # Remove the central-directory extra length/content; leave LOCAL extra
        # intact. A validator that guesses from ZipInfo.extra would accept it.
        data = bytearray(self.apk.read_bytes())
        central = data.index(b'PK\x01\x02')
        import struct
        name_size, extra_size = struct.unpack_from('<HH', data, central + 28)
        extra_at = central + 46 + name_size
        del data[extra_at:extra_at + extra_size]
        struct.pack_into('<H', data, central + 30, 0)
        end = data.index(b'PK\x05\x06')
        central_size = struct.unpack_from('<I', data, end + 12)[0]
        struct.pack_into('<I', data, end + 12, central_size - extra_size)
        self.apk.write_bytes(data)
        with self.assertRaisesRegex(ValueError, '4-byte boundary'):
            verifier.verify_resource_table(self.apk)


if __name__ == '__main__':
    unittest.main()
