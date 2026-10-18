"""Duffel Flights API (https://duffel.com/docs). Liefert echte, buchbare Angebote inkl. Flugzeugtyp je Segment.

Token-Präfix `duffel_test_` = Testmodus (Sandbox, fiktive Airline "Duffel Airways"), `duffel_live_` = Live.
"""

from __future__ import annotations

import asyncio
import logging
import re
from datetime import UTC, datetime

import httpx

from app.models.schemas import (
    AircraftAssignmentStatus,
    Itinerary,
    PriceBreakdown,
    PriceSource,
    ProviderStatus,
    Segment,
)
from app.providers.base import FlightProvider, ProviderError, RoundTripQuery
from app.services.itinerary_builder import InvalidItinerary, build_itinerary
from app.services.reference import ReferenceData

log = logging.getLogger(__name__)

_DURATION = re.compile(r"^P(?:(?P<d>\d+)D)?(?:T(?:(?P<h>\d+)H)?(?:(?P<m>\d+)M)?(?:\d+S)?)?$")


def parse_iso_duration(value: str | None) -> int | None:
    if not value:
        return None
    m = _DURATION.match(value)
    if not m:
        return None
    return int(m["d"] or 0) * 1440 + int(m["h"] or 0) * 60 + int(m["m"] or 0)


def _parse_dt(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def _amount(value) -> float | None:
    try:
        return round(float(value), 2) if value is not None else None
    except (TypeError, ValueError):
        return None


class DuffelProvider(FlightProvider):
    name = "duffel"

    def __init__(self, token: str, ref: ReferenceData, *, base_url: str = "https://api.duffel.com",
                 timeout: float = 25.0, max_retries: int = 2, max_offers: int = 40,
                 transport: httpx.AsyncBaseTransport | None = None) -> None:
        self._token = token
        self._ref = ref
        self._max_retries = max_retries
        self._max_offers = max_offers
        self.test_mode = token.startswith("duffel_test")
        self._client = httpx.AsyncClient(
            base_url=base_url, timeout=httpx.Timeout(timeout, connect=10.0), transport=transport,
            headers={"Authorization": f"Bearer {token}", "Duffel-Version": "v2",
                     "Accept": "application/json", "Content-Type": "application/json"})

    def status(self) -> ProviderStatus:
        if self.test_mode:
            return ProviderStatus(name="duffel", configured=True, mode="test",
                                  message="Duffel-Testmodus: Angebote stammen aus der Sandbox und sind "
                                          "keine echten Preise.")
        return ProviderStatus(name="duffel", configured=True, mode="live",
                              message="Duffel Live: tatsächlich angebotene Preise (zeitlich begrenzt gültig).")

    async def aclose(self) -> None:
        await self._client.aclose()

    async def _post_offer_request(self, body: dict) -> dict:
        attempt = 0
        while True:
            try:
                resp = await self._client.post("/air/offer_requests",
                                               params={"return_offers": "true", "supplier_timeout": 20000},
                                               json={"data": body})
            except httpx.TimeoutException as exc:
                err = ProviderError("Zeitüberschreitung bei der Flugsuche (Duffel).", retryable=True)
                err.__cause__ = exc
            except httpx.HTTPError as exc:
                err = ProviderError("Netzwerkfehler bei der Verbindung zu Duffel.", retryable=True)
                err.__cause__ = exc
            else:
                if resp.status_code in (200, 201):
                    try:
                        return resp.json()
                    except ValueError as exc:
                        raise ProviderError("Ungültige Antwort von Duffel.") from exc
                if resp.status_code in (401, 403):
                    raise ProviderError("Duffel-Zugang abgelehnt – Token prüfen (DUFFEL_ACCESS_TOKEN).")
                if resp.status_code == 422:
                    raise ProviderError("Duffel hat die Suchanfrage als ungültig abgelehnt.")
                retryable = resp.status_code == 429 or resp.status_code >= 500
                err = ProviderError(f"Duffel antwortete mit HTTP {resp.status_code}.", retryable=retryable)
            if not err.retryable or attempt >= self._max_retries:
                raise err
            attempt += 1
            await asyncio.sleep(0.5 * (3 ** (attempt - 1)))

    async def search_round_trip(self, query: RoundTripQuery) -> list[Itinerary]:
        body = {
            "slices": [
                {"origin": query.origin, "destination": query.destination,
                 "departure_date": query.departure_date.isoformat()},
                {"origin": query.destination, "destination": query.origin,
                 "departure_date": query.return_date.isoformat()},
            ],
            "passengers": [{"type": "adult"} for _ in range(query.adults)],
            "cabin_class": query.cabin_class.value,
            "max_connections": query.max_connections,
        }
        payload = await self._post_offer_request(body)
        offers = (payload.get("data") or {}).get("offers") or []
        fetched = datetime.now(UTC)
        offers = sorted(offers, key=lambda o: _amount(o.get("total_amount")) or float("inf"))
        results: list[Itinerary] = []
        for offer in offers[: self._max_offers]:
            try:
                itin = self.parse_offer(offer, query.adults, fetched)
            except (InvalidItinerary, KeyError, TypeError, ValueError) as exc:
                log.info("Duffel-Angebot verworfen: %s", exc)
                continue
            if itin:
                results.append(itin)
        return results

    def parse_offer(self, offer: dict, adults: int, fetched: datetime) -> Itinerary | None:
        slices = offer.get("slices") or []
        if len(slices) != 2:
            return None
        total = _amount(offer.get("total_amount"))
        if total is None:
            return None
        currency = offer.get("total_currency") or "?"
        legs: list[list[Segment]] = []
        bags: list[int] = []
        for sl in slices:
            segs: list[Segment] = []
            for raw in sl.get("segments") or []:
                seg = self._parse_segment(raw)
                segs.append(seg)
                if seg.checked_bags_included is not None:
                    bags.append(seg.checked_bags_included)
            if not segs:
                return None
            legs.append(segs)

        if bags and min(bags) > 0:
            bag_note = f"{min(bags)} Aufgabegepäckstück(e) pro Person laut Tarif inklusive."
        elif bags:
            bag_note = "Aufgabegepäck nicht in allen Segmenten inklusive – Zusatzkosten möglich."
        else:
            bag_note = "Gepäckregeln nicht übermittelt – bitte beim Anbieter prüfen."

        price = PriceBreakdown(total=total, currency=currency, base=_amount(offer.get("base_amount")),
                               taxes=_amount(offer.get("tax_amount")), per_traveler=round(total / adults, 2),
                               travelers=adults, baggage_note=bag_note)
        expires = offer.get("expires_at")
        notes = ["Flugzeugtyp laut Angebot/Flugplan geplant – Gerätewechsel durch die Airline möglich."]
        if self.test_mode:
            notes.insert(0, "Duffel-Testmodus: kein echtes, buchbares Angebot.")
        owner = (offer.get("owner") or {}).get("name")
        if owner:
            notes.append(f"Angebot von {owner} über Duffel (Buchung nicht über diese App).")
        return build_itinerary(
            self._ref, provider=self.name,
            price_source=PriceSource.test_offer if self.test_mode else PriceSource.live_offer,
            outbound=legs[0], inbound=legs[1], price=price, fetched_at=fetched,
            offer_expires_at=_parse_dt(expires) if expires else None, notes=notes)

    def _parse_segment(self, raw: dict) -> Segment:
        origin = raw["origin"]
        destination = raw["destination"]
        dep_local = _parse_dt(raw["departing_at"])
        arr_local = _parse_dt(raw["arriving_at"])
        o_code, d_code = origin["iata_code"], destination["iata_code"]
        dep_utc = self._ref.to_utc(dep_local, o_code, origin.get("time_zone"))
        arr_utc = self._ref.to_utc(arr_local, d_code, destination.get("time_zone"))
        dep_local, arr_local = dep_local.replace(tzinfo=None), arr_local.replace(tzinfo=None)

        duration = parse_iso_duration(raw.get("duration"))
        if duration is None and dep_utc and arr_utc:
            duration = int((arr_utc - dep_utc).total_seconds() // 60)
        if duration is None or duration <= 0:
            raise InvalidItinerary("Segmentdauer fehlt oder ist unplausibel")

        marketing = raw.get("marketing_carrier") or {}
        operating = raw.get("operating_carrier") or {}
        aircraft = raw.get("aircraft") or {}
        ac_code = aircraft.get("iata_code")
        ac_name = aircraft.get("name")
        type_id = self._ref.resolve_aircraft(ac_code, ac_name)

        checked = None
        for pax in raw.get("passengers") or []:
            count = sum(b.get("quantity", 0) for b in pax.get("baggages") or [] if b.get("type") == "checked")
            checked = count if checked is None else min(checked, count)

        return Segment(
            marketing_carrier=marketing.get("iata_code") or "??",
            marketing_carrier_name=marketing.get("name"),
            operating_carrier=operating.get("iata_code"),
            operating_carrier_name=operating.get("name"),
            flight_number=str(raw.get("marketing_carrier_flight_number") or "?"),
            origin=o_code, destination=d_code,
            departure_local=dep_local, arrival_local=arr_local,
            departure_utc=dep_utc, arrival_utc=arr_utc,
            duration_minutes=duration,
            aircraft_code=ac_code, aircraft_name=ac_name, aircraft_type_id=type_id,
            aircraft_status=AircraftAssignmentStatus.scheduled if (ac_code or ac_name)
            else AircraftAssignmentStatus.unknown,
            checked_bags_included=checked,
        )
