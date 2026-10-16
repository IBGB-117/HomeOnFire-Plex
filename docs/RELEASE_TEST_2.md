Corrected signed APK: **1.5.3-plex-test.2 (versionCode 142)**.

The first APK incorrectly compressed `resources.arsc` during ZIP repacking. Android
11+ rejects this for apps targeting SDK 30 or later, even when signature and ZIP
integrity checks pass. This version preserves aapt2 storage decisions and explicitly
verifies that the resource table is uncompressed and four-byte aligned.

- Package: `io.github.ibgb117.homeonfireplex.test`
- Filename: `homeonfire-plex-test.apk`
- Size: **203139 bytes**
- SHA-256: `65f472164a4ab5e2017e4ac215fc4fc57b46c4886d4cf3063e5698a35b77e756`
- Same separate test certificate as test.1; no original-app replacement.
- 30 session tests and four packaging regression tests passed in Codex Cloud.
- Signature, resource storage/alignment, APK contents and repeated-build byte
  reproducibility checked. The old APK fails the new packaging check.

Download this version afresh rather than reusing an old test.1 file. `adb push`
only copies a file; it does not install an app or add a launcher entry. No Fire TV
installation or execution was performed in Cloud, and Fire OS 8.1.8.5 device
acceptance remains pending. See `docs/PLEX_AUTOSTART.md` and PR #1 for behavior,
limitations, build instructions and the outstanding CI signing-secrets setup.

GPL-3.0-or-later; based on toolicious/home-on-fire. Complete source and LICENSE
are available in this repository and the release's source archive. The APK's
application/build inputs were built at commit bc43dc4; the release tag also includes
the resulting binary and publication instructions. No private keys are committed.
