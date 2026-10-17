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
identity. The new key must have a verified backup on the owner's persistent PC and
be configured in Actions Secrets before this release can be published. It is pinned
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
