# HomeOnFire Plex Test

Fork of [toolicious/home-on-fire](https://github.com/toolicious/home-on-fire),
GPL-3.0-or-later. Upstream copyright notices and LICENSE are retained.
Target device for acceptance: Fire TV Stick 4K Plus, Fire OS 8.1.8.5.
**This device/firmware has not yet been tested with this change.**

## Isolated test application

- Package: `io.github.ibgb117.homeonfireplex.test`
- App label: **HomeOnFire Plex Test**
- Default target: `com.plexapp.android`; the existing picker can select another app.
- The upstream app `io.github.toolicious.homeonfire` is not updated, removed or modified.
- Android separates the two packages' preferences and permissions. The test version
  cannot read the original app's private settings without its cooperation. Re-enter
  target and remote mappings in the test app. Updates of THIS test package retain
  its existing `home_on_fire` preferences, target and mapping formats.
- No root, firmware modification, network permission or Plex account token is needed.
- No connection, installation, permission grant or other Fire TV change is performed
  by the build scripts, CI workflow or tests. Device installation needs separate approval.

## Session behavior

There is one persistent session per observed boot or actual sleep/wake transition.
A broadcast receiver records locked/full boot; only a bound accessibility service
launches the target after unlock. Boot count (or the readable kernel boot UUID)
identifies a boot. Duplicate broadcasts, unlock, initial screen-on and service
reconnection coalesce rather than rearm a completed session. First enabling the
service on an already running device establishes a baseline, not a synthetic boot.

Wake is the priority: both screen and default-display state changes are observed.
A real awake transition requires a previously observed sleeping state. The current
PowerManager interactive state and display state corroborate events. HDMI changes
and dreaming/unlock signals are hints to sample current power state; they never
independently create a session. Direct-boot lifecycle metadata is stored separately
from credential-protected application preferences.

An active session has **no fixed lifetime or maximum attempt count**. It waits
for trustworthy Home/foreground evidence and retries as needed. Launch commands
back off through 1, 2, 4, 8, 16, 32 and then 60 seconds. After that they remain at
most one command per minute indefinitely while eligible. Background checks also
back off to 60 seconds; relevant window events can advance a check (at most four
checks per second) without bypassing
the persisted command cooldown. No alarms, foreground service, permanent wakelock,
network polling or sleeping-device timer is used. Sleep suspends callbacks; the
next true wake starts its own session.

Success requires two live samples at least one second apart of a visible, focused
Plex application window. Merely returning from `startActivity`, receiving an old
Plex event or finding a Plex process is insufficient. Loss of usable window evidence
restarts confirmation. This proves an accessible foreground window, not network
readiness, completed media loading or a rendered video frame.

On success, all autostart callbacks stop and the result is persisted. Going Home,
opening other apps or Settings, or reconnecting/restarting the service does not
start Plex again. Only another observed boot/wake may start a new session. Terminal
state and command cooldown are committed before reporting success or dispatching a
command. If persistence fails, no launch permission is returned.

## User control and remote mappings

The test variant always disables automatic Home replacement, including focus
inference, Back/OK and long-Home redirects. The old preference key is retained but
cannot enable those behaviors. Boot/wake autostart is independent of that setting.

Navigation key-down, click/scroll interaction evidence or another visible app
(including Settings and this configuration app) cancels an active session. A
navigation key that arrives before the screen-on broadcast also prevents the new
wake session from launching. Dedicated Power/Wakeup/Sleep keys are treated as power
triggers, not subsequent navigation. Conservative cancellation can sacrifice an
autostart rather than displace an app. Turning autostart off or changing its target
also ends the active session; toggling it on does not synthesize a new wake.

Explicit key mappings and observed branded-button window mappings are preserved.
They request a single launch, with a duplicate-window-event guard. The upstream
post-launch reassertions, inferred Apps-button fallback from Home and automatic
return from a mapped app to Plex are removed. Window-based mappings inherently
cannot distinguish a branded key from manually opening the exact same mapped
window; prefer a keycode mapping when that distinction matters. A mapping that
opens Plex is an explicit exception to remaining idle after successful autostart.

**Parallel-test conflict:** the original app's active accessibility service can
still redirect Home or compete for key filtering. This fork never edits that
service's settings. Record which services are active before interpreting results.
Temporarily disabling an original-app feature or service requires separate user
authorization; do not uninstall or overwrite it to work around a conflict.

## Available signals and known limits

| Signal | Use | Limitation |
| --- | --- | --- |
| `LOCKED_BOOT_COMPLETED`, `BOOT_COMPLETED` | Record/deduplicate a boot | OS must deliver them; force-stop can suppress delivery until manual launch. |
| `USER_UNLOCKED`, `USER_PRESENT` | Recheck readiness and power | Not independently evidence of a new wake. |
| Dynamically registered `SCREEN_OFF`, `SCREEN_ON` | Observe sleep/wake edge | Unavailable while service/process is absent; foreground sampling cannot reconstruct all missed history. |
| Default `DisplayManager` display changes | Corroborate display off/on | The logical Fire TV display may stay ON when the physical TV is off. |
| `DREAMING_STARTED`, `DREAMING_STOPPED` | Diagnostic/readiness hint | A screensaver is not necessarily sleep. |
| HDMI plugged/audio-plug broadcasts | Diagnostic/readiness hint | Not a guaranteed HDMI-CEC TV-power notification; may be absent or unrelated to TV power. |
| Accessibility windows/events | Confirm target or respect another app | Firmware can omit events or window trees. Unknown evidence waits; it does not authorize a launch. |

Turning a television off does not necessarily put its stick into standby. If Fire
OS keeps the device interactive and emits no observable off/on edge, the app cannot
reliably detect physical TV power without privileged/vendor support. It deliberately
does not treat an HDMI reconnect alone as a wake. If both screen events are missed
while the process is absent, an entire sleep/wake cycle may be unobservable. If a
previously observed sleep was persisted, reconnecting awake recovers that wake.

If both `boot_count` and the kernel boot UUID are unavailable, diagnostics say so.
The fallback fails closed; reliably distinguishing subsequent full reboots is not
guaranteed on that device. Verify boot identity in the target-device acceptance run.

HOME itself and some branded buttons are reserved by Fire OS and are not delivered
to accessibility key filtering. Home after a visible Plex candidate cancels a pending
session, and an observed other app always wins. A Home press during an initial
Home-only startup can be indistinguishable from automatic launcher startup; no
unprivileged implementation can promise to detect that invisible input. This is a
device-test limitation, not a reason to infer extra wake events.

Live Home view-ID inspection is bounded to 120 nodes and 14 levels. Known Home
activity classes provide another signal. Unknown launcher trees after an OS update
wait rather than assume that Settings or another launcher screen is safe to cover.

## Diagnostics

The existing log viewer adds session number, status, reason and attempt count.
A persistent ring holds at most 200 lines / 32 KiB in app-local storage, alongside
best-effort Logcat output (tag `HomeOnFire`, prefix `Autostart`). It records event
source, boot/session identity, elapsed time, foreground package, accepted/failed
start commands and terminal reason. It does not log UI text, library URLs or Plex
credentials. Repeated unchanged foreground observations are rate-limited to once
per minute. Existing upstream verbose logging remains optional; review any log
before sharing it.

## APK packaging correction (test.2 / versionCode 142)

The first distributed APK was 190,851 bytes. Its signature, ZIP integrity and DEX
were valid, but canonical ZIP repacking compressed `resources.arsc`. Android 11+
rejects target-SDK-30+ APKs unless that resource table is stored uncompressed and
four-byte aligned (`INSTALL_PARSE_FAILED_RESOURCES_ARSC_COMPRESSED`). This is an
actual packaging defect; the small overall file size alone does not diagnose it.
The rule is enforced in AOSP Android 11 `ParsingPackageUtils.parseBaseApk`.

Test.2 preserves aapt2 compression decisions, then aligns stored entries before
signing with the same external test key. The validator explicitly checks resource
storage and the actual payload offset from the ZIP local header. Four packaging
regression tests cover compressed, misaligned, correctly aligned and differing
local/central ZIP padding. The new check also rejects the original distributed APK.
The distribution workflow checks this rule before uploading. Use the `plex-test-2`
release URL to avoid reusing a cached test.1 download. Device installation and Fire
OS execution are still separate acceptance checks and have not been performed.

## Linux / Codex Cloud builds

The primary build is the checked-in Linux pipeline. The historical Windows
`build.ps1` remains upstream tooling and is not used by CI; it has local Windows
paths, bumps versions by default, and expects a separate external key.

```bash
# Existing isolated checkout; no worktree needed.
cd /workspace/HomeOnFire-Plex
export TOOLCHAIN_DIR=/workspace/.onboarding   # or an absolute writable cache directory
bash scripts/setup-android.sh
JAVA_HOME="$TOOLCHAIN_DIR/jdk-21.0.8+9" bash scripts/test.sh
bash scripts/build.sh debug
```

The pinned toolchain is Temurin JDK 21.0.8+9, Android platform 34/ext12 and Android
build-tools 36.1.0. Downloads retain TLS verification and are checked against pinned
SHA-256 digests. Java output targets Java 8; DEX min API 21, APK target API 34.
SDK package checksums originate from Google's repository metadata; JDK checksum
from the corresponding Adoptium release. Review Android SDK license terms for your
use of Google's packages.

Debug output: `build/homeonfire-plex-test-debug.apk` plus SHA-256 and package report.
Debug signatures are local development signatures and **are not stable across fresh
CI runners**. Do not establish an installed update lineage with debug CI artifacts.
Use the permanent test-key build for device tests that need later in-place updates.

## Permanent signing and GitHub Actions

`.github/workflows/apk.yml` runs tests and verifies a debug APK for pushes and PRs.
Trusted pushes/manual runs can additionally produce `homeonfire-plex-test.apk`
using a permanent test key. No pull-request job receives signing secrets, and no
`pull_request_target` workflow executes PR code. Actions are pinned to commit SHAs;
permissions are read-only and artifacts have limited retention. Signing output
contains public certificate fingerprints, never key/password values.

The same package, signing key and an increased manifest `versionCode` are required
for later updates. Never rotate an existing key merely to fix a failed build.
Back up the encrypted keystore and its password securely outside GitHub/Codex;
GitHub Secrets cannot be downloaded later as a backup.

Generate and optionally upload the isolated TEST key using a GitHub identity with
Actions Secrets read/write access. This is not the original app's signing key:

```bash
export JAVA_HOME=/workspace/.onboarding/jdk-21.0.8+9
python3 scripts/configure-signing.py --directory /workspace/.onboarding/plex-test-signing
# After securely backing up this directory, upload without printing secret values:
python3 scripts/configure-signing.py --directory /workspace/.onboarding/plex-test-signing --upload
```

The helper refuses to place permanent key material inside the checkout and refuses
to overwrite existing signing secret names. It saves these four repository secrets:

- `APK_SIGNING_KEYSTORE_BASE64`: base64-encoded PKCS12 keystore (single line).
- `APK_SIGNING_STORE_PASSWORD`: keystore password.
- `APK_SIGNING_KEY_PASSWORD`: same password for the generated PKCS12 key.
- `APK_SIGNING_KEY_ALIAS`: `homeonfireplex-test`.

If secret access is denied, an authorized repository administrator must configure
these securely in GitHub Settings → Secrets and variables → Actions, or run the
helper with a suitably authorized identity. Do not paste secret values into chat.
A missing configuration produces a visible workflow warning and no permanent-key
artifact; a successful debug job does not establish permanent signing readiness.

For a local permanent-key build, set `APK_SIGNING_KEYSTORE`,
`APK_SIGNING_STORE_PASSWORD`, `APK_SIGNING_KEY_PASSWORD` and `APK_SIGNING_KEY_ALIAS`
in the build process environment, then run `bash scripts/build.sh release`. Supply
passwords securely; do not put them in committed files or shell history.

APK ZIP order and timestamps are normalized from `SOURCE_DATE_EPOCH` (default: Git
commit time). With identical sources, toolchain, epoch and signing key, repeated
builds must produce the same APK SHA-256. This is verified locally. Signing with a
different certificate intentionally changes the APK.

## Automated and target-device acceptance

The standalone Java tests exercise lifecycle ordering/duplicates, persistence,
service replacement, delayed foreground confirmation, manual takeover, cooldowns,
indefinite retry beyond five minutes, bounded intervals, foreground classification,
and 10,000 generated terminal-session events. They do not execute the Android
framework, Fire OS activity policy, HDMI-CEC or Plex itself.

CI compiles every application source and resource, converts DEX, signs the APK,
checks v1/v2/v3 signatures and zip alignment, checks archive integrity and verifies
the test package, min/target SDK and direct-boot receiver configuration.

After separate installation authorization, record Fire OS build, Plex version,
boot identity and active accessibility services, then perform:

1. At least 10 standby/wake cycles each for short and long standby; include a
   delay exceeding five minutes before Plex becomes startable, where reproducible.
2. At least 10 physical TV off/on cycles. Record which screen/display/HDMI events
   actually arrive; explicitly record an unobservable TV-power cycle as unsupported.
3. At least 5 reboots/cold starts, checking one session despite multiple boot signals.
4. Plex already open, Amazon Home open, and another content app restored on wake.
5. Early D-pad/Back/Home input, Settings/profile dialogs and another deliberately
   opened app while a retry is pending. Confirm subsequent retries are stopped.
6. After successful confirmation, Home, Back, Settings and several other apps for
   at least 10 minutes: no automatic Plex return. Explicit Plex mappings still work.
7. Service reconnection/process replacement after success and during an unfinished
   session; confirm terminal persistence and continued command throttling.
8. Missing Plex, service disabled and unfamiliar window evidence; confirm diagnostic
   reasons without repeated rapid commands or interference with navigation.

Do not mark Fire OS 8.1.8.5 behavior verified from host tests or a successful APK build.

## Optional direct library entry

No stable library deep link is assumed or enabled. The current source has no Plex
library integration; the device's installed Plex version and supported exported
intents are not available in this cloud task. Inspect those after device-access
approval and prefer a documented package-restricted deep link with a normal-launch
fallback if one is supported. No remote-key simulation, token storage or guessed
URI is introduced. Normal Plex launch remains the supported path in this change.
