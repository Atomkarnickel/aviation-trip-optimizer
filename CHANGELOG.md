# Änderungsprotokoll

Format: [Keep a Changelog](https://keepachangelog.com/de/1.1.0/), Versionierung: [SemVer](https://semver.org/lang/de/).
Die erste Zeile jedes Abschnitts erscheint als Kurzbeschreibung in der README-Downloadtabelle und in der App unter „Über“.

## [2.0.0] – 2026-10-24

Neue App „Overhead“: Homescreen-Widget mit Mini-Radar, Live-Liste und Karte der Flugzeuge über dir – ersetzt den Flugplaner.

### Neu
- **Widget „Flugzeuge über dir“** in drei Größen: Mini-Radar (Norden oben, Farbe nach Höhe), die nächsten Flugzeuge mit Typ, Rufzeichen, Route, Entfernung, Richtung und Höhe; Aktualisieren-Knopf; Tipp auf eine Zeile öffnet die Details.
- **Liste** aller Flugzeuge im Umkreis (10–100 km), nächstes zuerst, mit Blickrichtung und Höhenwinkel über dem Horizont.
- **Karte** per Wischen: OpenFreeMap-Karte mit Flugzeugsymbolen (gedreht nach Kurs), deinem Standort und Suchradius; langer Druck legt einen festen Standort fest.
- **Details** mit Foto (planespotters.net), Route inkl. Plausibilitätsprüfung gegen die aktuelle Position, Geschwindigkeit, Steigrate, Squawk und Link zum Flugverlauf.
- Live-Aktualisierung alle 10 s in der App, im Hintergrund alle 15/30/60 min oder nur manuell.
- Automatischer Wechsel zur Ersatzquelle (adsb.fi), wenn adsb.lol nicht erreichbar ist; bei Ausfall bleibt der letzte Stand sichtbar und wird als „offline“ markiert.
- Versionsprüfung und APK-Download unter „Über“.
- Alles kostenlos und ohne Konto: adsb.lol, adsb.fi, hexdb.io, planespotters.net, OpenFreeMap.

### Entfernt
- Der Flugplaner (Aviation Trip Optimizer 1.x) mit Backend und Webversion ist eingestellt. Die App installiert sich als Update über die alte Version und ersetzt sie.
