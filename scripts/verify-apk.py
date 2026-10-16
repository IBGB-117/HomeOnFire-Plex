#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
import pathlib
import sys
import xml.etree.ElementTree as ET
import zipfile

apk = pathlib.Path(sys.argv[1])
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
print('APK contents, isolated test identity, SDK levels and boot manifest verified')
