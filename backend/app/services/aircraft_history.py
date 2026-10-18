"""Flugzeugtyp-Verlauf einer Flugnummer aus beobachteten Flügen (kostenlos).

1. OpenSky Network: Abflüge eines Flughafens je UTC-Tag (`/flights/departure`) → Rufzeichen + Transponder (icao24).
   Rufzeichen = ICAO-Airlinecode + Flugnummer (z. B. LX 38 → SWR38). Abweichende Rufzeichen werden nicht gefunden.
2. hexdb.io: Flugzeugtyp (ICAO-Typcode) je Transponder.
Beide Ergebnisse werden in der Datenbank zwischengespeichert, um Kontingente zu schonen.
"""

from __future__ import annotations

import re
import time
from collections import Counter
from datetime import UTC, date, datetime, timedelta

import httpx
from sqlalchemy.orm import Session, sessionmaker

from app.core.config import Settings
from app.db.models import AircraftRegistryRow, OpenSkyDepartureDayRow
from app.models.schemas import AircraftHistory, AircraftObservation, AircraftTypeShare
from app.services.reference import ReferenceData

FLIGHT_NUMBER = re.compile(r"^\d{1,4}[A-Z]?$")


class OpenSkyLimit(Exception):
    pass


def callsign_for(icao_airline: str, flight_number: str) -> str | None:
    number = flight_number.strip().upper()
    if not FLIGHT_NUMBER.match(number):
        return None
    digits = number.lstrip("0") or "0"
    return f"{icao_airline}{digits}"


class AircraftHistoryService:
    def __init__(self, settings: Settings, ref: ReferenceData, session_factory: sessionmaker[Session],
                 transport: httpx.AsyncBaseTransport | None = None, today: date | None = None) -> None:
        self.settings = settings
        self.ref = ref
        self.session_factory = session_factory
        self._transport = transport
        self._today = today
        self._token: tuple[str, float] | None = None

    def _client(self) -> httpx.AsyncClient:
        return httpx.AsyncClient(timeout=httpx.Timeout(25.0, connect=10.0), transport=self._transport,
                                 headers={"User-Agent": "aviation-trip-optimizer"}, follow_redirects=True)

    async def _auth_header(self, client: httpx.AsyncClient) -> dict:
        cid, secret = self.settings.opensky_client_id, self.settings.opensky_client_secret
        if not (cid and secret):
            raise OpenSkyLimit("OpenSky-Zugangsdaten fehlen.")
        if self._token and self._token[1] > time.monotonic():
            return {"Authorization": f"Bearer {self._token[0]}"}
        resp = await client.post(self.settings.opensky_token_url, data={
            "grant_type": "client_credentials", "client_id": cid, "client_secret": secret})
        if resp.status_code != 200:
            raise OpenSkyLimit("OpenSky-Anmeldung fehlgeschlagen – OPENSKY_CLIENT_ID/SECRET prüfen.")
        body = resp.json()
        self._token = (body["access_token"], time.monotonic() + min(int(body.get("expires_in", 1800)), 1800) - 120)
        return {"Authorization": f"Bearer {self._token[0]}"}

    async def _departures(self, client: httpx.AsyncClient, airport_icao: str, day: date) -> dict[str, list[str]]:
        key = day.isoformat()
        with self.session_factory() as session:
            row = session.get(OpenSkyDepartureDayRow, (airport_icao, key))
            if row is not None:
                return row.callsigns
        begin = int(datetime(day.year, day.month, day.day, tzinfo=UTC).timestamp())
        resp = await client.get(f"{self.settings.opensky_base_url}/flights/departure",
                                params={"airport": airport_icao, "begin": begin, "end": begin + 86400},
                                headers=await self._auth_header(client))
        if resp.status_code == 404:
            mapping: dict[str, list[str]] = {}
        elif resp.status_code in (401, 403, 429):
            raise OpenSkyLimit("OpenSky-Kontingent erschöpft oder Anmeldung nötig (kostenloses Konto + "
                               "OPENSKY_CLIENT_ID/SECRET erhöht das Tageskontingent).")
        elif resp.status_code != 200:
            raise OpenSkyLimit(f"OpenSky antwortete mit HTTP {resp.status_code}.")
        else:
            mapping = {}
            for flight in resp.json() or []:
                cs = (flight.get("callsign") or "").strip().upper()
                hex_ = (flight.get("icao24") or "").strip().lower()
                if cs and hex_:
                    mapping.setdefault(cs, [])
                    if hex_ not in mapping[cs]:
                        mapping[cs].append(hex_)
        with self.session_factory() as session:
            session.merge(OpenSkyDepartureDayRow(airport_icao=airport_icao, day=key, callsigns=mapping,
                                                 fetched_at=datetime.now(UTC)))
            session.commit()
        return mapping

    async def _aircraft(self, client: httpx.AsyncClient, icao24: str) -> AircraftRegistryRow:
        with self.session_factory() as session:
            row = session.get(AircraftRegistryRow, icao24)
            if row is not None:
                return row
        type_code = registration = description = None
        try:
            resp = await client.get(f"{self.settings.hexdb_base_url}/api/v1/aircraft/{icao24}")
            if resp.status_code == 200:
                body = resp.json()
                type_code = (body.get("ICAOTypeCode") or "").strip().upper() or None
                registration = (body.get("Registration") or "").strip() or None
                description = (body.get("Type") or "").strip()[:120] or None
            elif resp.status_code != 404:
                return AircraftRegistryRow(icao24=icao24, fetched_at=datetime.now(UTC))  # nicht cachen
        except (httpx.HTTPError, ValueError):
            return AircraftRegistryRow(icao24=icao24, fetched_at=datetime.now(UTC))
        row = AircraftRegistryRow(icao24=icao24, type_code=type_code, registration=registration,
                                  description=description, fetched_at=datetime.now(UTC))
        with self.session_factory() as session:
            session.merge(row)
            session.commit()
        return row

    async def history(self, carrier: str, flight_number: str, origin: str, days: int = 5) -> AircraftHistory:
        carrier, origin = carrier.upper(), origin.upper()
        flight = f"{carrier} {flight_number}"
        now = datetime.now(UTC)
        base = AircraftHistory(status="not_supported", flight=flight, origin=origin, days_checked=0, checked_at=now)
        airline = self.ref.airlines.get(carrier)
        airport = self.ref.airports.get(origin)
        if not self.settings.aircraft_history_enabled:
            return base.model_copy(update={"message": "Typverlauf ist im Backend deaktiviert."})
        if not (self.settings.opensky_client_id and self.settings.opensky_client_secret):
            # OpenSky beantwortet Flugdaten-Abfragen nur mit (kostenlosem) Konto – anonym: HTTP 403.
            return base.model_copy(update={"status": "not_configured", "message": (
                "Typverlauf noch nicht eingerichtet: kostenloses OpenSky-Konto anlegen und OPENSKY_CLIENT_ID/"
                "OPENSKY_CLIENT_SECRET im Backend setzen (siehe docs/PROVIDERS.md).")})
        if not airline or not airline.icao:
            return base.model_copy(update={"message": f"Kein ICAO-Code für Airline {carrier} hinterlegt."})
        if not airport or not airport.icao:
            return base.model_copy(update={"message": f"Kein ICAO-Code für Flughafen {origin} hinterlegt."})
        callsign = callsign_for(airline.icao, flight_number)
        if not callsign:
            return base.model_copy(update={"message": "Flugnummer unbekannt oder ungültig."})

        today = self._today or now.date()
        # Abflugdaten stehen bei OpenSky erst ab dem Folgetag zur Verfügung.
        day_list = [today - timedelta(days=i) for i in range(1, days + 1)]
        observations: list[AircraftObservation] = []
        checked = 0
        message = None
        async with self._client() as client:
            for day in day_list:
                try:
                    mapping = await self._departures(client, airport.icao, day)
                except OpenSkyLimit as exc:
                    message = str(exc)
                    break
                except httpx.HTTPError:
                    message = "OpenSky nicht erreichbar."
                    break
                checked += 1
                for hex_ in mapping.get(callsign, []):
                    ac = await self._aircraft(client, hex_)
                    type_id = self.ref.resolve_aircraft(ac.type_code, ac.description)
                    observations.append(AircraftObservation(
                        date=day, callsign=callsign, icao24=hex_, registration=ac.registration, type_code=ac.type_code,
                        aircraft_type_id=type_id,
                        aircraft_name=self.ref.aircraft_name(type_id) if type_id else ac.description))

        counts = Counter((o.aircraft_type_id, o.aircraft_name or o.type_code or "unbekannt") for o in observations)
        total = sum(counts.values())
        distribution = [AircraftTypeShare(aircraft_type_id=k[0], name=k[1], count=c, share=round(c / total, 3))
                        for k, c in counts.most_common()] if total else []
        if checked == 0:
            status = "unavailable"
        elif observations:
            status = "ok"
        else:
            status = "no_observations"
            message = message or (f"Keine Beobachtung von {callsign} ab {origin} in den letzten {checked} Tagen. "
                                  "Möglicherweise nutzt die Airline ein abweichendes Rufzeichen.")
        return AircraftHistory(status=status, flight=flight, callsign=callsign, origin=origin, days_checked=checked,
                               observations=observations, distribution=distribution, message=message, checked_at=now)
