# Overhead – Flugzeuge über dir

Eine kleine Android-App im Stil von Flightradar24, gebaut um **ein Homescreen-Widget**: Es zeigt, welche Flugzeuge gerade über dir fliegen – mit Mini-Radar, Typ, Rufzeichen, Route, Entfernung, Richtung und Höhe. In der App gibt es dazu eine **Liste als Zusammenfassung** und – per Wischen – eine **Karte**.

Alles kostenlos, ohne Konto, ohne API-Schlüssel.

## APK herunterladen

<!-- APK-DOWNLOADS:START -->
Noch kein stabiles Release mit APK veröffentlicht. Sobald ein Release existiert, steht hier automatisch der direkte Downloadlink.

Alle Releases: <https://github.com/Atomkarnickel/aviation-trip-optimizer/releases>
<!-- APK-DOWNLOADS:END -->

**Installation:** APK auf dem Handy öffnen → ggf. „Unbekannte Apps installieren“ für Browser/Dateimanager erlauben → installieren. Updates installieren sich über die alte Version (alle APKs sind mit demselben Schlüssel signiert), Einstellungen bleiben erhalten. Android 8.0 oder neuer.

**Vorher den Flugplaner installiert?** Overhead (ab 2.0.0) ersetzt ihn: Die APK installiert sich als Update über den alten „Aviation Trip Optimizer“ – danach gibt es nur noch die neue App. Die Releases 1.x auf der Release-Seite gehören zum eingestellten Flugplaner.

**Widget hinzufügen:** lange auf den Startbildschirm drücken → *Widgets* → *Overhead* → auf den Bildschirm ziehen. Oder in der App: *Einstellungen → Widget zum Startbildschirm hinzufügen*.

## Funktionen

| Bereich | Umfang |
|---|---|
| Widget | 3 Größen (klein, breit, groß). Mini-Radar: du in der Mitte, Norden oben, Rand = Suchradius, Farbe nach Höhe (gelb < 3 000 m, türkis < 8 000 m, blau darüber, rot = Notfall-Squawk). Daneben die nächsten Flugzeuge: Typ · Rufzeichen, Route oder Airline, Richtung, Entfernung, Höhe mit Steig-/Sinkpfeil. ⟳-Knopf aktualisiert sofort, Tipp auf eine Zeile öffnet die Details. |
| Liste | Alle Flugzeuge im Umkreis (10/25/50/100 km), nächstes zuerst, mit Blickrichtung (Pfeil + Himmelsrichtung) und Höhenwinkel über dem Horizont. |
| Karte | Wischen nach links: Karte mit gedrehten Flugzeugsymbolen, Rufzeichen, deinem Standort und Radius. Tipp auf ein Flugzeug zeigt Details, langer Druck setzt einen festen Standort. |
| Details | Foto (planespotters.net, mit Fotograf), Route mit Prüfung gegen die aktuelle Position, „Wo am Himmel?“ (Blickrichtung, Winkel, Entfernung), Geschwindigkeit, Kurs, Steigrate, Squawk, Link zum Flugverlauf. |
| Aktualisierung | In der App alle 10 Sekunden. Widget im Hintergrund alle 15/30/60 Minuten oder nur manuell (Android erlaubt nicht öfter). |
| Ausfallsicherheit | Fällt adsb.lol aus, wird automatisch adsb.fi gefragt. Ohne Netz bleibt der letzte Stand sichtbar und wird als „offline“ markiert. |
| Über | Installierte Version, neueste Version auf GitHub, direkter APK-Download mit SHA-256. |

## Datenquellen – alle kostenlos

| Zweck | Quelle | Hinweis |
|---|---|---|
| Live-Positionen | [adsb.lol](https://adsb.lol) (ODbL 1.0), Ersatz: [adsb.fi](https://adsb.fi) Open Data | Freiwilligen-Empfängernetz, kein API-Schlüssel |
| Routen & Flughäfen | [hexdb.io](https://hexdb.io) | Datenbank nach Rufzeichen – kann veraltet sein, wird daher gegen die Position geprüft |
| Fotos | [planespotters.net](https://www.planespotters.net) | mit Fotografennennung und Link |
| Karte | [OpenFreeMap](https://openfreemap.org) + MapLibre | © OpenMapTiles, Daten © OpenStreetMap-Mitwirkende |

**Grenzen:** ADS-B-Abdeckung hängt von freiwilligen Empfängern ab; manche Flugzeuge (Militär, Privatflüge, Gebiete ohne Empfänger) fehlen. Routen stammen aus einer Rufzeichen-Datenbank und sind nicht garantiert – unplausible Routen werden im Widget ausgeblendet und in den Details als „vermutlich veraltet“ markiert.

## Standort und Datenschutz

- Kein Konto, kein Tracking, keine Werbung.
- Standort: aktueller Gerätestandort oder ein fester Ort (langer Druck auf die Karte). Kein Google-Play-Dienst nötig.
- Das Widget nutzt im Hintergrund den aktuellen Standort nur, wenn du „Immer erlauben“ wählst – sonst den zuletzt in der App ermittelten.
- An die Flugdatendienste geht nur ein auf ca. 1 km gerundeter Punkt; Entfernungen rechnet die App auf dem Gerät.
- Keine Cloud-Sicherung der App-Daten.

## Entwicklung

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug   # Tests + Lint
./gradlew :app:assembleDebug                      # Debug-APK
python -m unittest discover -s scripts/tests       # Tests der Release-Skripte
```

Bauen ohne lokale Android-Installation, Signierung und Release-Ablauf: siehe [BUILD.md](BUILD.md). Änderungen je Version: [CHANGELOG.md](CHANGELOG.md).

| Pfad | Inhalt |
|---|---|
| `app/src/main/java/.../domain` | reine Logik: Geometrie (Entfernung, Richtung, Höhenwinkel), Formatierung, Widget-Inhalt, Versionsregeln |
| `app/src/main/java/.../data` | ADS-B-Quellen mit Ersatzquelle, Routen-Cache, Standort, Einstellungen, letzter Abruf |
| `app/src/main/java/.../widget` | Glance-Widget, Mini-Radar |
| `app/src/main/java/.../ui` | Compose-Oberfläche: Liste, Karte (MapLibre), Details, Einstellungen, Über |
| `app/src/main/assets` | ICAO-Typcodes → Namen, Airline-Kennungen → Namen |
| `scripts/` | README-Downloadliste, Changelog-Auszug, Versionssprung |

## Lizenz

MIT – siehe [LICENSE](LICENSE). Flugzeug- und Airline-Namen sind Marken ihrer Inhaber.
