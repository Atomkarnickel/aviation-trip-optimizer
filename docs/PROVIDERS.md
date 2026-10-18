# Datenquellen – kostenloser Betrieb

Stand der Recherche: **18.10.2026**. Vorgabe des Projekts: **alles kostenlos**. Bedingungen der Anbieter können sich ändern – vor produktiver Nutzung prüfen.

## Überblick

| Zweck | Quelle | Kosten | Konto nötig | Im Code |
|---|---|---|---|---|
| Preise (Standard) | **Travelpayouts / Aviasales Data API** | kostenlos | ja (kostenlos), Token | `providers/travelpayouts.py` |
| Flugzeugtyp je Flugnummer (beobachtet) | **OpenSky Network** REST API | kostenlos (nicht-kommerziell), Tageskontingent | ja (kostenlos), OAuth2-Client | `services/aircraft_history.py` |
| Flugzeugtyp je Transponder | **hexdb.io** | kostenlos (spendenfinanziert), kein Schlüssel | nein | `services/aircraft_history.py` |
| Live-Angebote (optional) | Duffel Flights API | Testmodus kostenlos (nur Sandbox-Daten); echte Angebote kostenpflichtig | ja | `providers/duffel.py` |
| Demo | synthetisch | – | – | `providers/demo.py` (`DEMO_MODE=true`) |

Nicht mehr nutzbar: **Amadeus Self-Service** (abgeschaltet am 17.07.2026). Nicht kostenlos: Cirium, FlightAware AeroAPI (nutzungsbasiert).

## Was jede Quelle liefert – und was nicht

Die App trennt strikt (`Itinerary.price_source`, `Segment.aircraft_status`, `Leg.details_complete`):

**Travelpayouts** (`price_source = estimated`)
- liefert: Preis pro Person, Airline, Flugnummer des Hinflugs, Abflug/Rückflug mit Zeitzone, Anzahl Umstiege je Richtung, Dauern, Aviasales-Suchlink.
- Datenbasis: Suchen anderer Nutzer der letzten Tage (bis ca. 7 Tage alt) → **Richtwert, nicht garantiert**.
- liefert **nicht**: Flugzeugtyp, Umsteigeflughäfen, Anschlussflüge, Rückflugnummer, Gepäck. Die App zeigt diese Angaben als unbekannt (z. B. „1 Umstieg – Umsteigeflughafen nicht übermittelt“) und erfindet nichts. Bei Verbindungen mit Umstieg wird auch die Flugnummer nicht übernommen, weil sie nicht eindeutig einem Segment zugeordnet ist.
- Abfragen: pro Route ein Aufruf je (Abflugmonat, Rückflugmonat) – z. B. 18.12.–06.01. mit ≥ 7 Nächten = 2 Aufrufe. Alle Datumspaare im Fenster werden dadurch abgedeckt.

**OpenSky + hexdb** (Typverlauf, auf Knopfdruck pro Segment)
- OpenSky `/flights/departure`: alle beobachteten Abflüge eines Flughafens pro UTC-Tag (Daten ab Vortag) → Rufzeichen + Transponder. Rufzeichen = ICAO-Airlinecode + Flugnummer (LX 38 → `SWR38`). Airlines mit abweichenden Rufzeichen (z. B. `SWR2TK`) werden nicht gefunden – die App meldet das ehrlich.
- hexdb.io: ICAO-Typcode je Transponder (z. B. `3C4B26` → D-ABYF, `B748`). Geprüft am 18.10.2026.
- Ergebnis: Verteilung der Typen der letzten 1–7 Tage („A340-300 4×, A330-300 1×“), gekennzeichnet als **historisch beobachtet – keine Garantie**.
- Kontingent: Eine Tagesabfrage kostet bei OpenSky ca. 30 Credits; ein kostenloses Konto hat 4.000 Credits/Tag. Abflüge werden pro Flughafen und Tag in der Datenbank gecacht und für alle Flugnummern wiederverwendet, Flugzeugtypen pro Transponder ebenso. Zusätzlich: Rate-Limit 6 Abfragen/Minute/IP.
- **Anonym liefert OpenSky keine Flugdaten (HTTP 403, getestet am 18.10.2026)** – daher ist ein kostenloses Konto nötig.

## Einrichtung (alles kostenlos)

### Travelpayouts (Preise)
1. Konto auf <https://www.travelpayouts.com> anlegen (kostenlos).
2. Im Dashboard unter *Tools → API* den **API-Token** kopieren, optional den **Marker** (Partner-ID).
3. Im Backend setzen: `TRAVELPAYOUTS_TOKEN=…` und optional `TRAVELPAYOUTS_MARKER=…` (wird an Aviasales-Links angehängt).
4. `/api/v1/meta` zeigt dann `provider.mode = "estimated"`.

### OpenSky (Typverlauf)
1. Konto auf <https://opensky-network.org> anlegen (kostenlos).
2. Unter *Account → API Client* einen Client erstellen → `client_id` und `client_secret`.
3. Im Backend setzen: `OPENSKY_CLIENT_ID=…`, `OPENSKY_CLIENT_SECRET=…`.
4. Ohne diese Werte zeigt die App beim Typverlauf „noch nicht eingerichtet“.

Nutzungsbedingungen: OpenSky-Daten sind für nicht-kommerzielle Nutzung frei. Bei kommerzieller Nutzung der App ist eine Lizenz bei OpenSky nötig.

### Duffel (optional, nicht kostenlos für echte Angebote)
`DUFFEL_ACCESS_TOKEN=duffel_test_…` aktiviert den Sandbox-Modus (fiktive Airline, als TESTMODUS gekennzeichnet). Ist ein Duffel-Token gesetzt, hat Duffel Vorrang vor Travelpayouts; mit `FLIGHT_PROVIDER=travelpayouts` lässt sich die Wahl erzwingen.

## Eigene Provider ergänzen

1. Klasse in `backend/app/providers/` von `FlightProvider` ableiten. Entweder `search_round_trip()` (einzelne Datumspaare) oder `supports_window = True` + `search_window()` (ganzes Reisefenster).
2. Segmente in `Segment` übersetzen und das Itinerary über `build_itinerary()` bauen (Zeitzonen, Umstiege, Aufenthaltstage, Umwege). Fehlen Umsteigedetails: `outbound_stops`/`inbound_stops` übergeben → `details_complete = false`.
3. In `providers/registry.py` registrieren.
4. Tests mit `httpx.MockTransport` (siehe `tests/test_free_sources.py`).

Quellen: [Travelpayouts – Aviasales Data API](https://support.travelpayouts.com/hc/en-us/articles/203956163), [OpenSky REST API](https://openskynetwork.github.io/opensky-api/rest.html), [hexdb.io](https://hexdb.io/), [Phocuswire – Amadeus Self-Service eingestellt](https://www.phocuswire.com/amadeus-shut-down-self-service-apis-portal-developers), [Duffel API](https://duffel.com/docs/api/v2/offers/schema).
