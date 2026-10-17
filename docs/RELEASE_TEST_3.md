# HomeOnFire Plex Test 3

Test version 1.5.3-plex-test.3 / versionCode 143 for Fire OS 8.1.8.5.
Package: `io.github.ibgb117.homeonfireplex.test`.

During early boot, the app now waits if Fire OS shows its Settings window before
Home/Plex. It never launches over Settings; observed manual input still cancels.
Settings after Home/Plex, or during wake, retains foreground priority. The boot-only
readiness boundary survives service replacement. Wake timing and terminal session
behavior are unchanged: success stops autostart until the next observed boot/wake.

**One-time reinstall of the TEST app is required when migrating from test.2.**
The previous private test signing key was lost in the restored cloud environment.
The owner has accepted loss of this test app's settings for the new signing
identity. The owner has now created and verified the new key backup on their
persistent PC and configured Actions Secrets. The successful signed release build
is [Actions run 39015357903](https://github.com/IBGB-117/HomeOnFire-Plex/actions/runs/39015357903).
The key is pinned
by the public `APK_SIGNING_CERT_SHA256` repository variable. Subsequent updates
must retain this key and package. Do not uninstall or change the original Home on
Fire app. Re-enter the test target/mappings, enable its accessibility service and
boot/wake option, and grant its secure-settings permission again after reinstalling.

Downloads include the APK, its SHA-256 checksum and the public certificate report.
The checksum file uses the APK basename; download both files into the same folder
and run `sha256sum -c homeonfire-plex-test.apk.sha256`. No
GitHub login is needed for public release downloads. No APK installation is
performed by CI. Hardware acceptance of the boot fix remains pending; automated
checks cannot establish all Fire OS/TV-power behavior or Plex media readiness.
The separately reported Amazon Shop CS2 error has not been diagnosed as a Plex
error and is not claimed fixed by this change.

Source and GPL license: https://github.com/IBGB-117/HomeOnFire-Plex
Upstream: https://github.com/toolicious/home-on-fire

Published APK: **203,139 bytes**, versionCode **143**.

SHA-256: `d7dc9af891e607aaeb59cc52fa6a817066738fd18e1b197ddf01ba0d5317e92e`

Signing certificate SHA-256: `5a95268bedb65dd069766689b5d091d5fbc28ea9feeb5a38efd1e70c1e53ea17`

Source build commit: `de2aa94f4ae24d31c35f960f420b06b9b51d9e20`.
All 50 automated tests passed; the public APK was independently checked for
signatures, package/version, ZIP/DEX integrity, uncompressed/aligned resources and
presence of the boot-readiness correction. Hardware acceptance remains pending.
