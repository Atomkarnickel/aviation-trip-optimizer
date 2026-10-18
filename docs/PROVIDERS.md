# Flugdaten-Provider – Recherche und Integration

Stand der Recherche: **18.10.2026**. Preise und Bedingungen ändern sich – vor produktiver Nutzung beim Anbieter prüfen.

## Grundsatz

Die App trennt strikt zwischen:

| Datenart | Feld im API-Modell | Quelle |
|---|---|---|
| Flugplandaten (Flugnummer, Zeiten, Strecke) | `Segment.*` | Provider-Antwort |
| Tatsächlich verfügbare Preise | `Itinerary.price_source = live_offer` | Live-Angebot (Duffel live) |
| Test-/Sandbox-Preise | `price_source = test_offer` | Duffel-Testmodus |
| Historische/geschätzte Preise | `price_source = estimated` | *(noch kein Provider angebunden)* |
| Demodaten | `price_source = demo` | `DemoProvider`, nur bei `DEMO_MODE=true` |
| Flugzeugtyp-Zuordnung | `Segment.aircraft_status` (`scheduled`/`confirmed`/`historical`/`unknown`) | Angebot/Flugplan bzw. Statusdienst |
| Buchungslinks | `Itinerary.booking_options` | nur verifizierte, offizielle Airline-Startseiten (`is_deep_link=false`) |

Es werden **keine** Preise, Flugnummern, Flugzeugtypen oder Buchungslinks erfunden. Ist kein Provider konfiguriert, liefert `/api/v1/search` den Status `provider_not_configured` und eine leere Ergebnisliste.

## Bewertete Anbieter

| Anbieter | Daten | Zugang / Kosten | Bewertung |
|---|---|---|---|
| **Amadeus Self-Service APIs** | Flight Offers Search inkl. Gerätecode | **Am 17.07.2026 abgeschaltet** (Registrierung seit Feb. 2026 gesperrt). Nur noch Amadeus Enterprise (Vertrag). | Nicht mehr nutzbar für dieses Projekt. |
| **Duffel Flights API** | Live-Angebote (NDC/GDS), je Segment `aircraft {iata_code, name}`, Carrier, Flugnummer, lokale Zeiten + Zeitzone, Preisaufschlüsselung, Gepäck, `expires_at` | Self-Service-Registrierung, kostenloser Testmodus. Abrechnung pro Buchung (laut Drittquellen ca. 3 USD + 1 % pro Order); Suche selbst wird nach Duffel-Bedingungen abgerechnet bzw. begrenzt („excess search“). Aktuelle Preise: duffel.com/pricing | **Primärer Provider (implementiert)**. |
| **FlightAware AeroAPI** | Flugstatus, tatsächlich eingesetzter Flugzeugtyp und Registrierung, Flugpläne (`/schedules`) | Nutzungsbasiert, gestaffelte Tarife; Query-Limits je Tarif | Geeignet, um Flugzeugtypen kurz vor Abflug zu **bestätigen** (`aircraft_status=confirmed`) und historische Typzuordnungen zu liefern. Nicht implementiert (Kosten). |
| **Cirium (SkyAPI / Schedules)** | Umfassende Flugplandaten inkl. Gerät, historische Daten | Individuelles Angebot, Vertrag | Fachlich ideal für Flugplan-Recherche („welche Route fliegt A380?“), aber ohne Vertrag nicht nutzbar. |
| Offizielle Airline-Websites | Buchung | frei | Werden als nicht vorausgefüllte Links angeboten (kuratierte Liste `backend/app/data/airlines.json`). |

Quellen: [Phocuswire – Amadeus schließt Self-Service-Portal](https://www.phocuswire.com/amadeus-shut-down-self-service-apis-portal-developers), [Duffel API – Offer Requests](https://duffel.com/docs/api/v2/offer-requests/create-offer-request), [Duffel API – Offers](https://duffel.com/docs/api/v2/offers/schema), [FlightAware AeroAPI](https://flightaware.com/commercial/aeroapi/).

## Duffel einrichten

1. Konto unter <https://app.duffel.com> anlegen.
2. Unter *Developers → Access tokens* einen **Test-Token** (`duffel_test_…`) erzeugen.
3. Im Backend setzen: `DUFFEL_ACCESS_TOKEN=duffel_test_…` (lokal in `backend/.env`, produktiv als Secret beim Hosting-Anbieter).
4. Backend neu starten – `/api/v1/meta` zeigt `provider.mode = "test"`. Die Apps kennzeichnen Ergebnisse als **TESTMODUS** (Sandbox-Airline „Duffel Airways“, keine echten Preise).
5. Für echte Angebote: Live-Modus im Duffel-Dashboard freischalten (Verifizierung/Zahlungsdaten) und `duffel_live_…`-Token setzen. Nutzungsbedingungen zu Suchvolumen beachten – die App begrenzt Abfragen pro Suche (`MAX_PROVIDER_CALLS_PER_SEARCH`, Standard 24) und cached identische Suchen 15 Minuten.

Der Token liegt **ausschließlich im Backend**. APK und Web-App enthalten keine Provider-Schlüssel.

## Eigene Provider ergänzen

1. Klasse in `backend/app/providers/` von `FlightProvider` ableiten (`status()`, `search_round_trip()`).
2. Segmente in `Segment` übersetzen (lokale Zeiten + UTC via `ReferenceData.to_utc`), Itinerary über `build_itinerary()` bauen – damit gelten automatisch Umstiegsprüfung, Aufenthaltstage und Umwegerkennung.
3. In `providers/registry.py` registrieren (z. B. `FLIGHT_PROVIDER=meinprovider`).
4. Tests mit `httpx.MockTransport` analog `tests/test_duffel.py`.

Für Flugzeugtyp-Bestätigungen (FlightAware) ist ein separater Anreicherungsschritt vorgesehen, der `aircraft_status` von `scheduled` auf `confirmed` setzt – nur wenn der Dienst den Typ für das konkrete Flugdatum liefert.
