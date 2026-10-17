# Dauerhafte Signatur für die Test-App einrichten

Die einmalige Neuinstallation der Test-App ist freigegeben. Der bisherige private
Testschlüssel ist verloren; die Cloud-Anbindung darf keine Actions-Secrets setzen
(HTTP 403). Deshalb muss der neue Schlüssel auf deinem dauerhaft gespeicherten PC
angelegt, gesichert und mit deinem GitHub-Konto an GitHub übertragen werden. Die
Entwicklung und die APK-Builds bleiben in GitHub/Codex Cloud. Schlüssel und
Passwort gehören weder ins Repository noch in diesen Chat.

## Einmalig auf deinem Linux-PC

Voraussetzung: Dein GitHub-Konto darf im Repository Actions-Secrets und Variables
verwalten. Für Ubuntu/Debian können die benötigten Programme so installiert werden:

```bash
sudo apt update && sudo apt install -y default-jre-headless gh python3 curl
```

Dann bei GitHub anmelden (die Anmeldung erfolgt über den Browser):

```bash
gh auth login --hostname github.com --git-protocol https --web
```

Das überprüfbare Einrichtungsskript herunterladen und starten:

```bash
curl -fL --retry 3 -o "$HOME/Downloads/homeonfire-signierung.py" 'https://raw.githubusercontent.com/IBGB-117/HomeOnFire-Plex/feat/plex-wake-autostart/scripts/configure-signing.py' &&
python3 "$HOME/Downloads/homeonfire-signierung.py" --directory "$HOME/.local/share/homeonfire-plex-signierung" --backup-directory "$HOME/HomeOnFire-Plex-Schluesselsicherung" --upload
```

Das Skript erstellt einen passwortgeschützten PKCS12-Testschlüssel und ein zufälliges
Passwort außerhalb von Git. Beide Verzeichnisse sind nur für deinen Benutzer
zugänglich. Es prüft die Sicherung bytegenau und öffnet deren Zertifikat mit dem
gesicherten Passwort. Erst dann setzt es die vier Actions-Secrets und die öffentliche
Zertifikatskennung. Geheimwerte werden weder ausgegeben noch als Kommandozeilen-
Argumente an `gh` übergeben. Die identische Einrichtung lässt sich wiederholen,
auch nach einem unterbrochenen Upload. Eine abweichende vorhandene Signatur oder
ältere Secrets ohne Zertifikatskennung werden nicht überschrieben.

**Beide Verzeichnisse behalten.** Im Sicherungsverzeichnis liegen
`homeonfire-plex-test.p12`, `store-password` und die öffentliche
`certificate-sha256.txt`. Das Passwort ist dort als private Datei gespeichert;
wer Schlüssel und Passwort besitzt, kann diese Test-App signieren. Sichere den
kompletten Ordner zusätzlich auf einem geschützten externen Datenträger oder in
einem verschlüsselten Backup. Zwei Ordner auf demselben PC schützen nicht vor dem
Verlust dieses PCs. GitHub Secrets sind kein herunterladbares Backup.

Bei Fehlern nichts löschen oder mit anderen Verzeichnissen neu erzeugen. Nur die
Fehlermeldung weitergeben, niemals die beiden privaten Dateien oder deren Inhalt.

## Test.3 automatisch bauen und veröffentlichen

Nach erfolgreicher Schlüsselübertragung:

```bash
gh workflow run apk.yml --repo IBGB-117/HomeOnFire-Plex --ref feat/plex-wake-autostart -f release_test3=true
```

Der Workflow führt die automatisierten Tests aus und baut/signiert die APK mit
der gesicherten Signatur. Er prüft unter anderem APK-Signatur, Zertifikatskennung,
ZIP-Inhalt, Android-11-Ressourcenformat, Ausrichtung, Paketkennung und Version.
Nur bei Erfolg veröffentlicht er `plex-test-3` als öffentliche Vorabversion.
Ein bereits öffentliches Test.3-APK wird nicht überschrieben. Pull-Request-Jobs
bekommen keine Signaturschlüssel. Der PR wird nicht gemergt.

**Die installierte Test-App vorerst behalten.** Erst die neue APK herunterladen
und prüfen; danach kann ausschließlich `io.github.ibgb117.homeonfireplex.test`
neu installiert werden. Einstellungen und Berechtigungen dieser Test-App gehen
bei ihrer Deinstallation verloren. Die Original-App und Plex bleiben erhalten.
