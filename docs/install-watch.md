# App auf der Smartwatch installieren (Wi-Fi/WLAN)

## 1. Entwickleroptionen auf der Uhr aktivieren

- **Einstellungen → System → Info → Build-Nummer** 7× antippen
- Zurück: **Einstellungen → Entwickleroptionen:**
  - „ADB-Debugging" → aktivieren
  - „Debugging über WLAN" → aktivieren
  - Die angezeigte **IP-Adresse und Port** merken (z.B. `192.168.1.42:5555`)

## 2. ADB verbinden (am PC)

```sh
adb connect 192.168.1.42:5555   # ← IP deiner Uhr einsetzen
```

Beim ersten Mal: auf der Uhr die Verbindung bestätigen („Immer zulassen" ankreuzen).

Prüfen:

```sh
adb devices
# sollte z.B. "192.168.1.42:5555  device" zeigen
```

## 3. APK bauen

```sh
./build.sh apk-wear
```

Oder mit lokalem SDK (ohne Docker):

```sh
VICTRON_NATIVE=1 ./build.sh apk-wear
```

## 4. Auf die Uhr installieren

```sh
./build.sh install-wear
```

## 5. Fertig

Die App erscheint in der App-Liste auf der Uhr. Beim ersten Start die Bluetooth-Scan-Berechtigung erlauben.

---

## Fehlerbehebung

| Problem | Lösung |
|---------|--------|
| `adb devices` zeigt nichts | Uhr und PC müssen im selben WLAN sein; „Debugging über WLAN" erneut prüfen |
| APK nicht gefunden | Erst `./build.sh apk-wear` laufen lassen, bevor `install-wear` |
| Permission denied | Auf der Uhr den ADB-Autorisierungs-Dialog bestätigen |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Alte Version erst deinstallieren: `adb uninstall de.universam.victron` |
