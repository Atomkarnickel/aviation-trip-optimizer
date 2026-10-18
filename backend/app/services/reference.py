"""Kuratierte Referenzdaten (Flughäfen, Airlines, Flugzeugtypen) aus app/data/*.json."""

from __future__ import annotations

import json
import math
from datetime import UTC, datetime
from functools import lru_cache
from pathlib import Path
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from app.models.schemas import AircraftType, Airline, Airport, ReferenceSource

DATA_DIR = Path(__file__).resolve().parents[1] / "data"

# Kandidaten für die offene Zielsuche: Drehkreuze mit großem Langstrecken-/Flugzeugtyp-Angebot.
OPEN_SEARCH_CANDIDATES = ["DXB", "DOH", "SIN", "LHR", "JFK", "BKK", "ICN", "HKG", "LAX", "IST"]


class ReferenceData:
    def __init__(self, data_dir: Path = DATA_DIR) -> None:
        aircraft_raw = json.loads((data_dir / "aircraft_types.json").read_text(encoding="utf-8"))
        airports_raw = json.loads((data_dir / "airports.json").read_text(encoding="utf-8"))
        airlines_raw = json.loads((data_dir / "airlines.json").read_text(encoding="utf-8"))

        self.aircraft: dict[str, AircraftType] = {}
        self._aircraft_by_code: dict[str, str] = {}
        for raw in aircraft_raw["types"]:
            at = AircraftType(**raw, bookable=raw["status"] != "historical")
            self.aircraft[at.id] = at
            for code in at.iata_codes + at.icao_codes:
                self._aircraft_by_code.setdefault(code.upper(), at.id)

        self.airports: dict[str, Airport] = {a["iata"]: Airport(**a) for a in airports_raw["airports"]}
        self.airlines: dict[str, Airline] = {a["iata"]: Airline(**a) for a in airlines_raw["airlines"]}
        self.sources = [
            ReferenceSource(source=f"Flugzeugtypen: {aircraft_raw['source']}", updated=aircraft_raw["updated"]),
            ReferenceSource(source=f"Flughäfen: {airports_raw['source']}", updated=airports_raw["updated"]),
            ReferenceSource(source=f"Airlines: {airlines_raw['source']}", updated=airlines_raw["updated"]),
        ]

    # ------------------------------------------------------------------ Flugzeuge
    def resolve_aircraft(self, code: str | None, name: str | None = None) -> str | None:
        """Ordnet einen IATA/ICAO-Gerätecode (oder Namen) einem Katalogtyp zu."""
        if code:
            hit = self._aircraft_by_code.get(code.strip().upper())
            if hit:
                return hit
            if code.strip().upper() in self.aircraft:
                return code.strip().upper()
        if name:
            normalized = name.lower().replace("boeing", "").replace("airbus", "").strip()
            for at in self.aircraft.values():
                model = at.model.lower()
                if normalized and (normalized == model or normalized.startswith(model.split("/")[0])):
                    return at.id
        return None

    def aircraft_name(self, type_id: str) -> str:
        at = self.aircraft.get(type_id)
        if not at:
            return type_id
        return at.model if at.model.startswith(at.manufacturer) else f"{at.manufacturer} {at.model}"

    # ------------------------------------------------------------------ Flughäfen
    def timezone_for(self, iata: str) -> ZoneInfo | None:
        ap = self.airports.get(iata)
        if not ap:
            return None
        try:
            return ZoneInfo(ap.tz)
        except ZoneInfoNotFoundError:
            return None

    def to_utc(self, local: datetime, iata: str, tz_name: str | None = None) -> datetime | None:
        """Lokale Flughafenzeit -> UTC. Nutzt die Provider-Zeitzone, sonst die kuratierte."""
        if local.tzinfo is not None:
            return local.astimezone(UTC)
        zone = None
        if tz_name:
            try:
                zone = ZoneInfo(tz_name)
            except ZoneInfoNotFoundError:
                zone = None
        zone = zone or self.timezone_for(iata)
        if zone is None:
            return None
        return local.replace(tzinfo=zone).astimezone(UTC)

    def distance_km(self, a: str, b: str) -> float | None:
        pa, pb = self.airports.get(a), self.airports.get(b)
        if not pa or not pb:
            return None
        lat1, lon1, lat2, lon2 = map(math.radians, (pa.lat, pa.lon, pb.lat, pb.lon))
        h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2
        return 2 * 6371.0 * math.asin(math.sqrt(h))

    def mct(self, iata: str) -> int:
        ap = self.airports.get(iata)
        return ap.mct if ap else 60

    def same_city(self, a: str, b: str) -> bool:
        pa, pb = self.airports.get(a), self.airports.get(b)
        return bool(pa and pb and pa.city == pb.city)

    def search_airports(self, query: str, limit: int = 20) -> list[Airport]:
        q = query.strip().lower()
        if not q:
            return list(self.airports.values())[:limit]
        exact = [a for a in self.airports.values() if a.iata.lower() == q]
        rest = [a for a in self.airports.values()
                if a not in exact and (q in a.name.lower() or q in a.city.lower() or q in a.iata.lower())]
        return (exact + rest)[:limit]


@lru_cache
def get_reference() -> ReferenceData:
    return ReferenceData()
