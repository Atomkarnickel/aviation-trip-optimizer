# Aviation Trip Optimizer

Flugreisen planen wie ein Flugzeugenthusiast: Die App sucht nicht nur den günstigsten Flug, sondern die beste Kombination aus **Preis, Reisezeit, Aufenthaltsdauer, Routing, Airline und Flugzeugerlebnis** – inklusive transparenter Begründung, warum eine Reise empfohlen wird und was der A380 oder die 747 tatsächlich mehr kostet.

- **Android-App** (Kotlin, Jetpack Compose, Material 3) – signierte APK über GitHub Releases
- **Webversion** (Next.js, TypeScript) – direkt im Browser, responsiv
- **Backend** (Python, FastAPI) – Flugsuche, Bewertung, Flugzeugkatalog, Release-Metadaten

## APK herunterladen

<!-- APK-DOWNLOADS:START -->
Noch kein stabiles Release mit APK veröffentlicht. Sobald ein Release existiert, steht hier automatisch der direkte Downloadlink.

Alle Releases: <https://github.com/Atomkarnickel/aviation-trip-optimizer/releases>
<!-- APK-DOWNLOADS:END -->

Die APK ist für Android 8.0+ gedacht. Beim Öffnen fragt Android ggf. nach der Erlaubnis „Unbekannte Apps installieren“ – die Installation bestätigst du selbst. Prüfsumme (SHA-256) und Signaturzertifikat stehen in jedem Release.

## Webversion

<!-- WEB-URL:START -->
Die Webversion wird über GitHub Pages bereitgestellt; die Adresse steht hier, sobald das erste Deployment erfolgreich war.
<!-- WEB-URL:END -->

## Funktionen

| Bereich | Umfang |
|---|---|
| Suche | mehrere Abflughäfen, feste oder offene Ziele (mehrere), flexibler Zeitraum (z. B. 18.12.–06.01.) mit automatisch geplanten Datumskombinationen, min./max. Reisedauer, Reisende, Budget, Wunschflugzeuge, bevorzugte/ausgeschlossene Airlines, max. Umstiege, max. Reisezeit, Abflugzeitfenster, Kabine, Gepäckbedarf |
| Optimierung | Modi **Best Value**, **Aircraft Hunter**, **Cheapest Trip**; frei einstellbare Gewichtung (Preis, Flugzeug, Reisezeit, Umstiege, Aufenthalt, Airline, Komfort) mit Neubewertung ohne neue Suche |
| Ergebnisse | Gesamtpreis + Bestandteile, Daten, Airline/Flugnummer, Umstiege mit Risiko, Reise- und Aufenthaltsdauer, Flugzeugtyp je Segment (geplant/bestätigt), Score-Aufschlüsselung, Begründung, Kompromisse, Wunschflugzeug-Aufpreis, bessere Preis-Leistungs-Alternative, Unsicherheiten, Datenaktualität, Buchungslink (offizielle Airline-Website) |
| Vergleich | 2–4 Reisen nebeneinander, bester Wert je Kriterium markiert |
| Preiskalender | Heatmap: günstigster erhaltener Preis je Abflugtag × Aufenthaltsdauer; Klick filtert die Ergebnisse |
| Typverlauf | pro Flugnummer: welche Flugzeugtypen sie in den letzten Tagen tatsächlich geflogen ist (OpenSky + hexdb.io) |
| Flugzeuge | kuratierter Katalog (A380, 747, A340, A350, 787, A330, 757, ATR 72 …; historische Typen wie DC-10 nur als Katalogeintrag), persönliche Markierung (Wunschliste / geflogen / uninteressant) und eigene Coolness-Bewertung |
| Favoriten & Logbuch | lokal gespeichert (Android: Room/DataStore, Web: localStorage), kein Konto nötig |
| About | installierte/laufende Version, neueste stabile APK (GitHub Releases API), Update-Status, direkter APK-Download mit Prüfsumme |

**Ehrliche Daten:** Ohne konfigurierten Flugdaten-Provider zeigt die App keine Ergebnisse an, statt welche zu erfinden. Geschätzte, Demo- und Testdaten sind deutlich gekennzeichnet. Fehlende Angaben (Umsteigeflughafen, Flugzeugtyp) erscheinen als „unbekannt“. Flugzeugtypen gelten höchstens als *geplant* bzw. *historisch beobachtet*, nie als garantiert.

## Datenquellen – komplett kostenlos

| Zweck | Quelle |
|---|---|
| Preise | Travelpayouts / Aviasales Data API (kostenlos, zwischengespeicherte Preise → als **geschätzt** gekennzeichnet) |
| Flugzeugtyp-Verlauf | OpenSky Network (kostenloses Konto) + hexdb.io |
| Hosting | GitHub Pages (Web), GitHub Releases (APK), Render Free (Backend, SQLite) |

Optional: Duffel (Sandbox kostenlos). Die Amadeus Self-Service APIs wurden im Juli 2026 abgeschaltet. Details und Einrichtung: [docs/PROVIDERS.md](docs/PROVIDERS.md), [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md).

## Schnellstart

```bash
# Backend mit Demodaten (klar gekennzeichnet)
cd backend && python -m venv .venv && .venv/Scripts/pip install -r requirements-dev.txt
DEMO_MODE=true .venv/Scripts/uvicorn app.main:app --reload        # http://localhost:8000/docs

# Web
cd apps/web && npm ci && NEXT_PUBLIC_API_BASE_URL=http://localhost:8000 npm run dev

# Android
cd apps/android && ./gradlew :app:assembleDebug
```

Mehr: [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) · [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) · [docs/RELEASING.md](docs/RELEASING.md) · [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) · [shared/RELEASE_METADATA.md](shared/RELEASE_METADATA.md)

## Projektstruktur

```
VERSION                 einzige Versionsquelle (SemVer)
backend/                FastAPI-Backend, Provider, Bewertung, Alembic, Tests, Dockerfile
apps/web/               Next.js-Webversion (statischer Export für GitHub Pages)
apps/android/           Android-App (Kotlin, Compose, Hilt, Room, DataStore, Retrofit)
shared/                 OpenAPI-Spezifikation, Release-Konfiguration, gemeinsame Testvektoren
scripts/                README-Generator, Versions-Bump
docs/                   Architektur, Provider, Release, Deployment, Entwicklung
.github/workflows/      CI, Release (APK), Web-Deployment
```

## Datenschutz

Keine Konten, kein Tracking. Favoriten, Logbuch und Einstellungen bleiben auf dem Gerät bzw. im Browser. An das Backend gehen nur Suchparameter und Präferenzen. API-Schlüssel liegen ausschließlich im Backend.

## Lizenz

MIT – siehe [LICENSE](LICENSE). Flugzeug- und Airline-Namen sind Marken ihrer Inhaber; es werden keine Flugzeugbilder ohne Nutzungserlaubnis verwendet.
