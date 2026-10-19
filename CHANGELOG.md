# Änderungsprotokoll

Format: [Keep a Changelog](https://keepachangelog.com/de/1.1.0/), Versionierung: [SemVer](https://semver.org/lang/de/).
Die erste Zeile jedes Abschnitts erscheint als Kurzbeschreibung in der README-Downloadtabelle und im About-Bereich.

## [1.1.0] – 2026-10-19

Logbuch-Statistik, CSV-Export/-Import, Reisen in den Kalender übernehmen und Webversion als App installierbar.

### Neu
- **Logbuch-Statistik** (Web und Android): Flüge, Flugzeugtypen, geflogene Großkreisdistanz, längster Flug, häufigste Typen und Fortschritt der Wunschliste.
- **Logbuch als CSV exportieren und importieren** (Web und Android). Doppelte Einträge werden beim Import übersprungen, fehlerhafte Zeilen mit Zeilennummer gemeldet.
- **Reise in den Kalender** (.ics, ein Termin je Flug, Web und Android). Termine sind klar als „nicht gebucht“ gekennzeichnet; der Flugzeugtyp erscheint mit seinem Status (geplant/bestätigt).
- **Webversion installierbar** („Zum Startbildschirm hinzufügen“) über ein Web-App-Manifest mit Icon.

### Verbessert
- Web und Android rechnen nachweislich identisch: gemeinsame Testvektoren (`shared/test-vectors/exports.json`) aus einer Python-Referenzimplementierung.
- Release-Notes und README-Tabelle enthalten jetzt die Änderungen aus diesem Protokoll.
- GitHub Actions auf aktuelle Hauptversionen aktualisiert (keine veralteten Node-Laufzeiten mehr), Runner fest auf Ubuntu 24.04.

## [1.0.2] – 2026-10-19

Erstes veröffentlichtes Release der Android-App.

### Behoben
- Release-Prüfung des Signaturzertifikats unterstützt das neue Ausgabeformat von `apksigner`.
- Versionssprung aktualisiert auch die Version in der OpenAPI-Spezifikation.

## [1.0.1] – 2026-10-19

Nicht veröffentlicht (Release-Prüfung schlug fehl, behoben in 1.0.2).

## [1.0.0] – 2026-10-18

Nicht veröffentlicht (Release-Prüfung schlug fehl, behoben in 1.0.2). Enthält den ursprünglichen Funktionsumfang: Suche mit flexiblen Daten, drei Optimierungsmodi, Flugzeugkatalog und Wunschliste, Preiskalender, Typverlauf, Favoriten, Logbuch, About-Bereich mit Updateprüfung.
