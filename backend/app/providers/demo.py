"""Synthetischer Demo-Provider – NUR für Entwicklung/Vorführung (DEMO_MODE=true).

Alle Ergebnisse sind als Demo markiert: fiktive Airlines (Codes D1–D3), fiktive Flugnummern, keine Buchungslinks.
Die Daten sind deterministisch, damit Tests reproduzierbar bleiben.
"""

from __future__ import annotations

import hashlib
import random
from datetime import UTC, datetime, time, timedelta

from app.models.schemas import (
    AircraftAssignmentStatus,
    Itinerary,
    PriceBreakdown,
    PriceSource,
    ProviderStatus,
    Segment,
)
from app.providers.base import FlightProvider, RoundTripQuery
from app.services.itinerary_builder import InvalidItinerary, build_itinerary
from app.services.reference import ReferenceData

DEMO_AIRLINES = {"D1": "Demo Atlantic (fiktiv)", "D2": "Demo Pacific (fiktiv)", "D3": "Demo Regional (fiktiv)"}
DEMO_HUBS = ["DXB", "DOH", "IST", "FRA", "LHR", "SIN", "AMS", "ICN", "HEL", "JFK"]
LONG_HAUL = ["388", "74H", "359", "789", "77W", "346", "333", "351", "744"]
MEDIUM_HAUL = ["321", "339", "752", "7M8", "223", "320"]
SHORT_HAUL = ["320", "738", "AT7", "E90", "DH4", "223", "319"]
PREMIUM = {"388": 1.12, "74H": 1.15, "744": 1.1, "346": 1.08, "351": 1.05}


def _rng(*parts: object) -> random.Random:
    seed = hashlib.sha256("|".join(map(str, parts)).encode()).hexdigest()
    return random.Random(int(seed[:16], 16))


class DemoProvider(FlightProvider):
    name = "demo"

    def __init__(self, ref: ReferenceData) -> None:
        self._ref = ref

    def status(self) -> ProviderStatus:
        return ProviderStatus(name="demo", configured=True, mode="demo",
                              message="DEMO-MODUS: synthetische Beispieldaten. Keine echten Flüge, Preise "
                                      "oder Buchungsmöglichkeiten.")

    def _segment(self, rng: random.Random, carrier: str, origin: str, dest: str, depart_local: datetime,
                 aircraft_code: str) -> Segment:
        km = self._ref.distance_km(origin, dest) or 1000
        minutes = int(km / 820 * 60 + 30)
        dep_utc = self._ref.to_utc(depart_local, origin)
        arr_utc = dep_utc + timedelta(minutes=minutes) if dep_utc else None
        dest_tz = self._ref.timezone_for(dest)
        if arr_utc and dest_tz:
            arr_local = arr_utc.astimezone(dest_tz).replace(tzinfo=None)
        else:
            arr_local = depart_local + timedelta(minutes=minutes)
        return Segment(
            marketing_carrier=carrier, marketing_carrier_name=DEMO_AIRLINES[carrier],
            operating_carrier=carrier, operating_carrier_name=DEMO_AIRLINES[carrier],
            flight_number=f"{rng.randint(100, 989)}", origin=origin, destination=dest,
            departure_local=depart_local, arrival_local=arr_local, departure_utc=dep_utc, arrival_utc=arr_utc,
            duration_minutes=minutes, aircraft_code=aircraft_code,
            aircraft_type_id=self._ref.resolve_aircraft(aircraft_code),
            aircraft_status=AircraftAssignmentStatus.scheduled, checked_bags_included=rng.choice([0, 1]))

    def _pick_aircraft(self, rng: random.Random, km: float) -> str:
        pool = LONG_HAUL if km > 4500 else MEDIUM_HAUL if km > 1500 else SHORT_HAUL
        return rng.choice(pool)

    def _route(self, rng: random.Random, origin: str, dest: str, day, hub: str | None) -> list[Segment] | None:
        carrier = rng.choice(["D1", "D2"])
        start = datetime.combine(day, time(rng.choice([6, 8, 10, 13, 16, 19, 21]), rng.choice([0, 15, 30, 45])))
        if hub is None:
            km = self._ref.distance_km(origin, dest) or 1000
            return [self._segment(rng, carrier, origin, dest, start, self._pick_aircraft(rng, km))]
        km1 = self._ref.distance_km(origin, hub) or 1000
        first = self._segment(rng, "D3" if km1 < 900 else carrier, origin, hub, start,
                              self._pick_aircraft(rng, km1))
        layover = rng.choice([55, 80, 110, 150, 240, 420])
        dep2 = first.arrival_local + timedelta(minutes=layover)
        km2 = self._ref.distance_km(hub, dest) or 1000
        second = self._segment(rng, carrier, hub, dest, dep2, self._pick_aircraft(rng, km2))
        return [first, second]

    def _hubs(self, origin: str, dest: str, count: int) -> list[str]:
        direct = self._ref.distance_km(origin, dest) or 1
        scored = []
        for hub in DEMO_HUBS:
            if hub in (origin, dest):
                continue
            a, b = self._ref.distance_km(origin, hub), self._ref.distance_km(hub, dest)
            if a and b and a > 300 and b > 300:
                scored.append(((a + b) / direct, hub))
        return [h for ratio, h in sorted(scored) if ratio < 1.6][:count]

    async def search_round_trip(self, query: RoundTripQuery) -> list[Itinerary]:
        if not (self._ref.airports.get(query.origin) and self._ref.airports.get(query.destination)):
            return []
        rng = _rng(query.origin, query.destination, query.departure_date, query.return_date)
        km = self._ref.distance_km(query.origin, query.destination) or 1000
        hubs = self._hubs(query.origin, query.destination, 3) if query.max_connections > 0 else []
        options: list[str | None] = ([None] if km < 13000 else []) + hubs
        results: list[Itinerary] = []
        fetched = datetime.now(UTC)
        for out_hub in options:
            for in_hub in options[:2]:
                out = self._route(rng, query.origin, query.destination, query.departure_date, out_hub)
                back = self._route(rng, query.destination, query.origin, query.return_date, in_hub)
                if not out or not back:
                    continue
                per_person = 40 + km * 2 * rng.uniform(0.045, 0.085)
                for seg in out + back:
                    per_person *= PREMIUM.get(seg.aircraft_code or "", 1.0)
                if len(out) + len(back) > 2:
                    per_person *= rng.uniform(0.78, 0.95)
                per_person = round(per_person, 0)
                total = per_person * query.adults
                price = PriceBreakdown(total=total, currency=query.currency, base=round(total * 0.72, 2),
                                       taxes=round(total * 0.28, 2), per_traveler=per_person,
                                       travelers=query.adults, baggage_note="Demo: Gepäckregeln fiktiv.")
                try:
                    results.append(build_itinerary(
                        self._ref, provider=self.name, price_source=PriceSource.demo, outbound=out,
                        inbound=back, price=price, fetched_at=fetched, booking_options=[],
                        notes=["DEMO-DATEN: fiktive Airline, Flugnummer und Preis – nicht buchbar."]))
                except InvalidItinerary:
                    continue
        return results
