# Release-Metadaten – gemeinsame Regeln

Quelle der Wahrheit: **GitHub Releases** des Repositorys aus `shared/release-config.json`.
Umgesetzt identisch in:

- Backend + README-Generator: `backend/app/services/releases.py`, `scripts/update_readme.py`
- Android: `domain/ReleaseRules.kt`, `domain/VersionUtils.kt`
- Web: `src/lib/releases.ts`, `src/lib/semver.ts`

Alle drei Implementierungen laufen gegen dieselben Testvektoren (`shared/test-vectors/semver.json`).

## Regeln

1. **Version**: Datei `VERSION` (nur `MAJOR.MINOR.PATCH`). Tag = `v` + Version. Vorabversion: `vX.Y.Z-suffix`.
2. **versionCode** = `MAJOR*10000 + MINOR*100 + PATCH` (MINOR, PATCH < 100) → streng monoton.
3. **Stabil** = nicht Draft, `prerelease == false`, Tag ohne Vorabkennung.
4. **APK-Asset** = exakt `aviation-optimizer-<version>.apk`, Prüfsumme `aviation-optimizer-<version>.apk.sha256` (Format von `sha256sum`).
5. **Zulässige URLs**: nur `https://github.com/<repo>/releases/download/…`. Andere Hosts werden verworfen.
6. **Neueste stabile Version** = höchste SemVer-Version (nicht das Datum, nicht alphabetisch) unter den stabilen Releases mit gültigem APK-Asset.
7. **Update-Status**: installiert = neueste → *Aktuell*; installiert < neueste → *Update verfügbar*; API-Fehler/ungültige Version → *Versionsprüfung nicht möglich*.
8. **Cache**: 900 s (`releaseCacheSeconds`). Bei API-Ausfall wird ein vorhandener Stand als „zwischengespeichert“ angezeigt, sonst *unbekannt*.
