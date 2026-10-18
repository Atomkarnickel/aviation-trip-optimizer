# Release-Prozess

Ein Release besteht aus: signierter Android-APK + SHA-256-Prüfsumme als GitHub-Release-Assets, aktualisierter README-Downloadliste und deployter Webversion. Alles läuft über `.github/workflows/release.yml`, ausgelöst durch einen Tag `vX.Y.Z`.

## 1. Version erhöhen

```bash
python scripts/bump_version.py patch      # oder minor | major | 1.4.0
git commit -am "chore: Version 1.0.1"
```

`VERSION` ist die **einzige** Versionsquelle. Daraus leiten sich ab: Android `versionName`/`versionCode` (Gradle), Web-App-Version (next.config.mjs), Backend-Version, erwarteter Release-Tag. Das Skript verhindert nicht-monotone versionCodes.

## 2. Tests lokal ausführen

```bash
(cd backend && .venv/Scripts/python -m pytest)          # bzw. .venv/bin/python unter Linux/macOS
(cd apps/web && npm test && npm run typecheck && npm run lint)
(cd apps/android && ./gradlew :app:testDebugUnitTest :app:lintDebug)
```

## 3. Tag erstellen und pushen

```bash
git push origin main
git tag v1.0.1
git push origin v1.0.1
```

Vorabversion: `git tag v1.1.0-beta.1` (VERSION muss `1.1.0` sein). Vorabversionen werden als *Pre-release* markiert, erscheinen **nicht** in README/„latest“ und lösen kein Web-Deployment aus.

## 4. Was der Workflow tut

1. Tag-Format und Übereinstimmung mit `VERSION` prüfen – sonst Abbruch.
2. Komplette CI (Backend, Web, Android) als wiederverwendbarer Workflow.
3. Prüfen, dass alle Signier-Secrets vorhanden sind – **sonst Abbruch, keine unsignierte APK**.
4. `testReleaseUnitTest` + `assembleRelease` mit Keystore aus Secrets.
5. `apksigner verify`: v2-Signatur, **Zertifikat-SHA-256 muss `vars.ANDROID_CERT_SHA256` entsprechen** (schützt die Update-Fähigkeit).
6. `aapt2 dump badging`: Paketname, `versionName`, `versionCode` prüfen.
7. Umbenennen in `aviation-optimizer-X.Y.Z.apk`, `sha256sum` erzeugen und verifizieren.
8. GitHub Release zunächst als **Entwurf** mit Assets anlegen, Assets prüfen, dann veröffentlichen (stabil: `--latest`).
9. Öffentlichen Download erneut laden und gegen die Prüfsumme verifizieren.
10. README-Abschnitt „APK herunterladen“ neu generieren (`scripts/update_readme.py`, prüft jeden Link per HTTP) und auf `main` committen.
11. Webversion bauen, testen und auf GitHub Pages deployen (mit Erreichbarkeitsprüfung).

Schlägt ein Schritt fehl, wird kein Release veröffentlicht bzw. bleibt höchstens ein **Entwurf** zurück (nur für Maintainer sichtbar).

## 5. Ergebnis kontrollieren

- Release-Seite: `https://github.com/Atomkarnickel/aviation-trip-optimizer/releases`
- README → Abschnitt „APK herunterladen“ zeigt die neue Version.
- About-Bereich (Web/Android) zeigt die neue Version als „neueste stabile“ (nach max. 15 min Cache).

## 6. Fehler beheben

- **Tag passt nicht zu VERSION**: Tag löschen (`git push --delete origin vX.Y.Z; git tag -d vX.Y.Z`), korrigieren, neu taggen.
- **Build/Test fehlgeschlagen**: Fehler beheben, **neues Patch-Release** (`bump_version.py patch`) – veröffentlichte Releases/Assets nie überschreiben.
- **Entwurf übrig geblieben**: im GitHub-UI löschen, Ursache beheben, Tag neu pushen.
- **README-Job fehlgeschlagen** (z. B. Link-Prüfung): `python scripts/update_readme.py` lokal ausführen und committen.

## Signierung und Keystore

| Secret / Variable | Inhalt |
|---|---|
| `ANDROID_KEYSTORE_BASE64` (Secret) | `base64` des PKCS12-Keystores |
| `ANDROID_KEYSTORE_PASSWORD` (Secret) | Keystore-Passwort |
| `ANDROID_KEY_ALIAS` (Secret) | `aviation-optimizer` |
| `ANDROID_KEY_PASSWORD` (Secret) | Schlüssel-Passwort |
| `ANDROID_CERT_SHA256` (Variable) | erwarteter Zertifikat-Fingerprint (öffentlich) |
| `API_BASE_URL` (Variable, optional) | öffentliche Backend-URL als Voreinstellung für APK und Web |

Einrichtung (einmalig):

```bash
keytool -genkeypair -keystore release.jks -storetype PKCS12 -alias aviation-optimizer \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks | gh secret set ANDROID_KEYSTORE_BASE64
gh secret set ANDROID_KEYSTORE_PASSWORD
gh secret set ANDROID_KEY_ALIAS --body aviation-optimizer
gh secret set ANDROID_KEY_PASSWORD
keytool -list -v -keystore release.jks | grep SHA256   # -> Fingerprint
gh variable set ANDROID_CERT_SHA256 --body "<fingerprint>"
```

Der Projekt-Keystore wurde am 18.10.2026 erzeugt und liegt **außerhalb des Repositorys** in `%USERPROFILE%\.aviation-optimizer-signing\` (`release.jks` + `signing.env`). Zertifikat-SHA-256: `abc5ab6ebba904be23104ca3b4f3714e72d5433dfa1b77eed9a9be3ec86bfb94`.

**Sicherung**: Keystore und Passwörter zusätzlich offline/verschlüsselt sichern (z. B. Passwortmanager). Ohne diesen Schlüssel können installierte Apps **keine Updates** mehr erhalten.

**Verlust oder Schlüsselwechsel**: Ein neuer Schlüssel erzeugt eine inkompatible Signatur. Nutzer müssen die App deinstallieren und neu installieren (lokale Favoriten/Logbuch gehen verloren). Vorgehen: neuen Keystore erzeugen, Secrets und `ANDROID_CERT_SHA256` aktualisieren, MAJOR-Version erhöhen, in den Release Notes deutlich darauf hinweisen. Für eine spätere Play-Store-Veröffentlichung Play App Signing nutzen (Upload-Key ist dann austauschbar).

## Android App Bundle (für eine spätere Store-Veröffentlichung)

```bash
cd apps/android
ANDROID_KEYSTORE_PATH=... ANDROID_KEYSTORE_PASSWORD=... ANDROID_KEY_ALIAS=... ANDROID_KEY_PASSWORD=... \
  ./gradlew :app:bundleRelease
# Ergebnis: app/build/outputs/bundle/release/app-release.aab
```
