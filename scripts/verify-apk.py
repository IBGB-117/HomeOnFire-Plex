#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
import pathlib
import re
import struct
import sys
import xml.etree.ElementTree as ET
import zipfile


def verify_signing_certificate(report, expected):
    if not re.fullmatch(r'[0-9a-fA-F]{64}', expected):
        raise ValueError('A SHA-256 certificate pin is required for permanent signing')
    actual = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$',
                        report, re.MULTILINE)
    if len(actual) != 1 or actual[0].lower() != expected.lower():
        raise ValueError('APK signing certificate differs from the backed-up permanent key')


def verify_resource_table(apk):
    """Android 11+ rejects compressed or misaligned resources for target SDK >=30.

    apksigner/zipalign alone do not reject an APK containing compressed resources.
    Read the LOCAL header's extra length: zipalign adds padding there, not
    necessarily to the central-directory ZipInfo.extra field.
    """
    with zipfile.ZipFile(apk) as archive:
        info = archive.getinfo('resources.arsc')
        if info.compress_type != zipfile.ZIP_STORED:
            raise ValueError('resources.arsc must be uncompressed for Android 11+ / target SDK 30+')
        with open(apk, 'rb') as source:
            source.seek(info.header_offset)
            header = source.read(30)
        if len(header) != 30 or header[:4] != b'PK\x03\x04':
            raise ValueError('Invalid resources.arsc ZIP local header')
        name_length, extra_length = struct.unpack_from('<HH', header, 26)
        data_offset = info.header_offset + 30 + name_length + extra_length
        if data_offset % 4:
            raise ValueError('resources.arsc must be aligned on a 4-byte boundary')


def verify_apk(apk):
    apk = pathlib.Path(apk)
    verify_resource_table(apk)
    with zipfile.ZipFile(apk) as archive:
        assert archive.testzip() is None, 'Corrupt APK entry'
        assert {'AndroidManifest.xml', 'classes.dex', 'resources.arsc'} <= set(archive.namelist())
        assert archive.read('classes.dex').startswith(b'dex\n')
    badging = pathlib.Path(str(apk) + '.badging.txt').read_text()
    assert "package: name='io.github.ibgb117.homeonfireplex.test'" in badging
    assert "minSdkVersion:'21'" in badging
    assert "targetSdkVersion:'34'" in badging
    assert 'android.permission.INTERNET' not in badging
    manifest = ET.parse('AndroidManifest.xml').getroot()
    android = '{http://schemas.android.com/apk/res/android}'
    receiver = manifest.find('./application/receiver')
    assert receiver.get(android + 'directBootAware') == 'true'
    actions = {a.get(android + 'name') for a in receiver.findall('./intent-filter/action')}
    assert {'android.intent.action.LOCKED_BOOT_COMPLETED', 'android.intent.action.BOOT_COMPLETED',
            'android.intent.action.USER_UNLOCKED'} <= actions
    print('APK contents, uncompressed/aligned resources, test identity, SDK levels and boot manifest verified')


if __name__ == '__main__':
    verify_apk(sys.argv[1])
