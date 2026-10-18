from __future__ import annotations

import json
from datetime import UTC, datetime
from pathlib import Path

import pytest

from app.models.schemas import AircraftAssignmentStatus, PriceBreakdown, PriceSource, Segment
from app.services.itinerary_builder import build_itinerary
from app.services.reference import ReferenceData, get_reference

REPO_ROOT = Path(__file__).resolve().parents[2]


@pytest.fixture(scope="session")
def ref() -> ReferenceData:
    return get_reference()


@pytest.fixture(scope="session")
def semver_vectors() -> dict:
    return json.loads((REPO_ROOT / "shared" / "test-vectors" / "semver.json").read_text(encoding="utf-8"))


def seg(ref: ReferenceData, carrier: str, origin: str, dest: str, dep: str, arr: str, aircraft: str | None = "320",
        number: str = "100") -> Segment:
    dep_local = datetime.fromisoformat(dep)
    arr_local = datetime.fromisoformat(arr)
    dep_utc = ref.to_utc(dep_local, origin)
    arr_utc = ref.to_utc(arr_local, dest)
    return Segment(marketing_carrier=carrier, flight_number=number, origin=origin, destination=dest,
                   departure_local=dep_local, arrival_local=arr_local, departure_utc=dep_utc, arrival_utc=arr_utc,
                   duration_minutes=int((arr_utc - dep_utc).total_seconds() // 60),
                   aircraft_code=aircraft, aircraft_type_id=ref.resolve_aircraft(aircraft),
                   aircraft_status=AircraftAssignmentStatus.scheduled if aircraft else AircraftAssignmentStatus.unknown)


def make_itinerary(ref: ReferenceData, outbound, inbound, total: float, currency: str = "EUR", travelers: int = 1,
                   **kwargs):
    price = PriceBreakdown(total=total, currency=currency, per_traveler=total / travelers, travelers=travelers)
    return build_itinerary(ref, provider="test", price_source=kwargs.pop("price_source", PriceSource.live_offer),
                           outbound=outbound, inbound=inbound, price=price,
                           fetched_at=datetime(2026, 10, 18, tzinfo=UTC), **kwargs)
