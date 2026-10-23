# Build-Anleitung (für Menschen und KI-Assistenten)

Diese Datei erklärt, wie aus dem Quellcode eine installierbare APK entsteht – **ohne lokale Android-Installation**, rein über GitHub Actions.

## Kurzfassung

1. Quellcode ändern.
2. Änderungen pushen (Branch egal).
3. GitHub baut automatisch (Tab **Actions** → Workflow *APK bauen*, ca. 5–8 Minuten): Tests, Lint, signierte APK und App Bundle.
4. Im fertigen Lauf ganz unten unter **Artifacts** `Overhead-APK` herunterladen → ZIP entpacken → APK aufs Handy → installieren.

Für eine **offizielle Version** mit dauerhaftem Download-Link in README und Releases:

```bash
python scripts/bump_version.py patch      # oder minor | major | 1.4.0 → ändert nur die Datei VERSION
# CHANGELOG.md: Abschnitt „## [X.Y.Z] – Datum“ ergänzen (erste Zeile = Kurzbeschreibung)
git commit -am "Release X.Y.Z"
git tag vX.Y.Z
git push origin main vX.Y.Z
```

Der Workflow *Release* prüft Tag ↔ `VERSION` ↔ `CHANGELOG.md`, testet, baut und signiert, prüft Zertifikat und Paketdaten, veröffentlicht `overhead-X.Y.Z.apk` + `.sha256` als GitHub Release, lädt den Download zur Kontrolle erneut herunter und aktualisiert danach die Downloadliste in der README.

## Version

`VERSION` ist die einzige Versionsquelle. Gradle leitet daraus ab: `versionName = X.Y.Z`, `versionCode = X*10000 + Y*100 + Z` (muss bei jedem Release steigen – Android prüft das bei Updates). Veröffentlichte Releases nie überschreiben – bei Fehlern ein neues Patch-Release erstellen.

## Signierung – wichtig für Updates

Android installiert ein Update nur, wenn es mit **demselben Schlüssel** signiert ist wie die installierte Version.

- Der Schlüssel liegt **nicht** im Repository (öffentlich!), sondern als GitHub-Secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`; dazu die Variable `ANDROID_CERT_SHA256` (erwartetes Zertifikat).
- Gradle liest ihn aus den Umgebungsvariablen `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`. Fehlen sie, bleibt der Release-Build unsigniert; der Release-Workflow bricht dann bewusst ab.
- **Den Schlüssel niemals ersetzen oder neu erzeugen**, sonst lassen sich bestehende Installationen nicht mehr aktualisieren. Sicherung des Originals außerhalb des Repos aufbewahren.

## Warum über GitHub?

Ein Android-Build braucht Android SDK, Build-Tools und viele Maven-Abhängigkeiten (`dl.google.com`, `maven.google.com`, `repo.maven.apache.org`, `services.gradle.org`). In manchen KI-Sandboxen sind diese Server gesperrt – dann nicht versuchen, Sperren zu umgehen, sondern Code schreiben, pushen und GitHub bauen lassen. Schlägt der Build fehl: Lauf öffnen → Job *build* → fehlgeschlagenen Schritt aufklappen; Zeilen mit `e: file:///…kt:Zeile:Spalte` nennen die Kotlin-Fehler. Test- und Lint-Berichte liegen als Artifact `test-und-lint-berichte` am Lauf.

## Beteiligte Dateien

| Datei | Zweck |
|---|---|
| `.github/workflows/build-apk.yml` | bei jedem Push: Tests, Lint, signierte APK + AAB als Artifacts |
| `.github/workflows/release.yml` | bei Tag `vX.Y.Z`: offizielles Release + README-Update |
| `gradlew`, `gradle/wrapper/*` | Gradle-Wrapper (8.9) – muss im Repo liegen |
| `gradle/libs.versions.toml` | alle Bibliotheksversionen (AGP 8.7.3, Kotlin 2.0.21, Compose, Glance, WorkManager, MapLibre 11.8) |
| `app/build.gradle.kts` | SDK 26–35, Version aus `VERSION`, Signierung aus Umgebungsvariablen, R8 |
| `scripts/update_readme.py` | README-Downloadliste aus echten Releases, prüft jeden Link |
| `scripts/changelog.py` | Abschnitt einer Version aus `CHANGELOG.md` (Release-Notes) |
| `scripts/bump_version.py` | Versionssprung mit monotonem versionCode |

## Bekannte Stolperfallen

- **Native Kartenbibliotheken:** MapLibre liefert `.so`-Dateien; `abiFilters` beschränkt auf arm64-v8a, armeabi-v7a, x86_64, `useLegacyPackaging` komprimiert sie (APK ≈ 13 statt 34 MB).
- **Kartenschrift:** OpenFreeMap-Stile liefern nur „Noto Sans“-Glyphen – eigene Symbol-Layer müssen `textFont("Noto Sans Regular")` setzen, sonst fehlen Beschriftungen.
- **Widget-Aktualisierung:** WorkManager erlaubt periodisch minimal 15 Minuten. Häufiger nur per ⟳-Knopf (einmaliger Auftrag) oder in der App.
- **Hintergrund-Standort:** ohne „Immer erlauben“ liefert Android im Worker keinen Standort; die App fällt dann auf den zuletzt gespeicherten zurück.
- **apksigner-Ausgabe:** neuere Build-Tools schreiben `V3.0 Signer:` statt `Signer #1` – die Workflows prüfen beide Formate.
- Kein lokales `build/`, `.gradle/`, `local.properties`, keine `*.jks` committen (steht in `.gitignore`).
