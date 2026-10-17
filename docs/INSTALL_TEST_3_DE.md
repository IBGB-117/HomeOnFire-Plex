# Test.3 am PC herunterladen und auf dem Fire TV installieren

Version: **1.5.3-plex-test.3 / 143**, Datei: **homeonfire-plex-test.apk**,
Größe: **203.139 Bytes** (etwa 200 KB). Die kleine Datei und eine Übertragung unter
einer Sekunde sind bei dieser App normal. Entscheidend sind Prüfsumme und gültige
APK-Signatur, nicht die Übertragungsdauer.

Die APK ist öffentlich verfügbar, eine GitHub-Anmeldung am Fernseher ist nicht
nötig. Wenn Downloader Probleme macht, die Datei am PC laden und direkt per ADB
installieren. Ein vorheriges Verschieben nach `/sdcard/Download` ist dann unnötig.

## Herunterladen und prüfen (Linux-PC)

```bash
cd "$HOME/Downloads" &&
curl -fL --retry 3 -o homeonfire-plex-test-v3.apk 'https://github.com/IBGB-117/HomeOnFire-Plex/releases/download/plex-test-3/homeonfire-plex-test.apk' &&
echo 'd7dc9af891e607aaeb59cc52fa6a817066738fd18e1b197ddf01ba0d5317e92e  homeonfire-plex-test-v3.apk' | sha256sum -c -
```

Erwartet: `homeonfire-plex-test-v3.apk: OK`. Nur dann installieren.

## ADB-Verbindung prüfen

```bash
adb devices
```

Der Fire TV muss mit Status `device` erscheinen. Bei leerer Liste mit seiner
aktuellen IP verbinden (`FIRE_TV_IP` ersetzen) und eine mögliche Freigabe am TV
bestätigen:

```bash
adb connect FIRE_TV_IP:5555
```

## Einmalige Neuinstallation ausschließlich der TEST-App

Die bisherige Test.2-Signatur kann wegen des verlorenen alten privaten Schlüssels
nicht weiterverwendet werden. Der Nutzer hat die einmalige Neuinstallation mit
Verlust ihrer Test-Einstellungen freigegeben. Der folgende Befehl deinstalliert
**nur** `io.github.ibgb117.homeonfireplex.test`. Plex und die Original-App
`io.github.toolicious.homeonfire` werden nicht verändert.

Nach erfolgreicher Download-Prüfung:

```bash
adb uninstall io.github.ibgb117.homeonfireplex.test &&
adb install "$HOME/Downloads/homeonfire-plex-test-v3.apk" &&
adb shell pm grant io.github.ibgb117.homeonfireplex.test android.permission.WRITE_SECURE_SETTINGS &&
adb shell am start -n io.github.ibgb117.homeonfireplex.test/.MainActivity
```

Falls die alte Test-App bereits deinstalliert wurde, nur den ersten Befehl
überspringen. Für spätere APK-Updates mit demselben neuen Schlüssel wieder
`adb install -r DATEI.apk` verwenden; nicht erneut deinstallieren.

Danach in **HomeOnFire Plex Test**:

1. Plex als Ziel prüfen, Accessibility-Dienst der Test-App wieder aktivieren.
2. **Launch on boot** für Boot/Standby-Wake einschalten.
3. Gewünschte explizite Tastenbelegungen wieder einrichten. Home bleibt frei.
4. Zunächst ohne Fernbedienungseingabe einen Standby/Wake testen. Nach bestätigtem
   Plex-Start Home, Einstellungen und andere Apps öffnen: kein automatischer
   Rücksprung zu Plex. Anschließend auch den vollständigen Neustart prüfen.

Die automatisierten Tests ersetzen diese Fire-OS-Prüfung nicht. Bei Problemen den
Diagnose-Log in der Test-App nach dem jeweiligen Test fotografieren. Die Original-
App niemals deinstallieren oder ihre Berechtigungen für diesen Test ändern.

## Nur verschieben, ohne Installation (optional)

```bash
adb push "$HOME/Downloads/homeonfire-plex-test-v3.apk" /sdcard/Download/homeonfire-plex-test-v3.apk &&
adb exec-out cat /sdcard/Download/homeonfire-plex-test-v3.apk | cmp "$HOME/Downloads/homeonfire-plex-test-v3.apk" -
```

Bei erfolgreichem `cmp` gibt es keine Ausgabe und Exit-Code 0: Die Datei auf dem
Stick ist bytegleich. Downloader zeigt per ADB kopierte Dateien nicht immer in
seiner Downloadliste; die direkte Installation oben umgeht diese Dateiauswahl.
