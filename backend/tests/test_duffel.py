import asyncio
import json
from datetime import date

import httpx
import pytest

from app.models.schemas import CabinClass, PriceSource
from app.providers.base import ProviderError, RoundTripQuery
from app.providers.duffel import DuffelProvider, parse_iso_duration

QUERY = RoundTripQuery(origin="ZRH", destination="SIN", departure_date=date(2026, 12, 18),
                       return_date=date(2027, 1, 2), adults=2, cabin_class=CabinClass.economy, max_connections=1,
                       currency="EUR")


def _segment(o, otz, d, dtz, dep, arr, carrier="SQ", number="345", aircraft=("388", "Airbus A380-800"), bags=1):
    return {
        "origin": {"iata_code": o, "time_zone": otz}, "destination": {"iata_code": d, "time_zone": dtz},
        "departing_at": dep, "arriving_at": arr, "duration": None,
        "marketing_carrier": {"iata_code": carrier, "name": "Singapore Airlines"},
        "operating_carrier": {"iata_code": carrier, "name": "Singapore Airlines"},
        "marketing_carrier_flight_number": number,
        "aircraft": {"iata_code": aircraft[0], "name": aircraft[1]} if aircraft else None,
        "passengers": [{"baggages": [{"type": "checked", "quantity": bags}]}],
    }


OFFER = {
    "id": "off_1", "total_amount": "1840.50", "total_currency": "EUR", "base_amount": "1400.00",
    "tax_amount": "440.50", "expires_at": "2026-10-18T20:00:00Z", "owner": {"name": "Singapore Airlines"},
    "slices": [
        {"segments": [_segment("ZRH", "Europe/Zurich", "SIN", "Asia/Singapore",
                               "2026-12-18T11:25:00", "2026-12-19T06:15:00")]},
        {"segments": [_segment("SIN", "Asia/Singapore", "ZRH", "Europe/Zurich",
                               "2027-01-02T23:55:00", "2027-01-03T07:00:00", number="346",
                               aircraft=("359", "Airbus A350-900"), bags=0)]},
    ],
}


def _provider(handler, token="duffel_live_x", ref=None):
    from app.services.reference import get_reference
    return DuffelProvider(token, ref or get_reference(), transport=httpx.MockTransport(handler), max_retries=2)


def test_parse_iso_duration():
    assert parse_iso_duration("PT13H50M") == 830
    assert parse_iso_duration("P1DT2H") == 1560
    assert parse_iso_duration("garbage") is None


def test_parses_offer_into_itinerary():
    seen = {}

    def handler(request: httpx.Request):
        seen["body"] = json.loads(request.content)
        seen["headers"] = request.headers
        return httpx.Response(201, json={"data": {"offers": [OFFER]}})

    p = _provider(handler)
    result = asyncio.run(p.search_round_trip(QUERY))
    assert len(result) == 1
    it = result[0]
    assert seen["headers"]["Duffel-Version"] == "v2"
    assert len(seen["body"]["data"]["passengers"]) == 2
    assert it.price.total == 1840.5 and it.price.per_traveler == 920.25 and it.price.taxes == 440.5
    assert it.price_source == PriceSource.live_offer
    assert it.legs[0].segments[0].aircraft_type_id == "A380"
    assert it.legs[0].segments[0].aircraft_status.value == "scheduled"
    assert it.legs[0].duration_minutes == 11 * 60 + 50  # 11:25 MEZ (10:25 UTC) -> 06:15 SGT (22:15 UTC)
    assert it.nights == 14
    assert "nicht in allen Segmenten" in it.price.baggage_note
    assert it.offer_expires_at is not None


def test_test_mode_is_labelled():
    p = _provider(lambda r: httpx.Response(200, json={"data": {"offers": [OFFER]}}), token="duffel_test_abc")
    assert p.status().mode == "test"
    it = asyncio.run(p.search_round_trip(QUERY))[0]
    assert it.price_source == PriceSource.test_offer
    assert any("Testmodus" in n for n in it.data_notes)


def test_retries_on_server_error_then_succeeds(monkeypatch):
    monkeypatch.setattr("app.providers.duffel.asyncio.sleep", _no_sleep)
    calls = {"n": 0}

    def handler(request):
        calls["n"] += 1
        if calls["n"] < 3:
            return httpx.Response(503)
        return httpx.Response(201, json={"data": {"offers": [OFFER]}})

    assert len(asyncio.run(_provider(handler).search_round_trip(QUERY))) == 1
    assert calls["n"] == 3


def test_auth_error_not_retried():
    calls = {"n": 0}

    def handler(request):
        calls["n"] += 1
        return httpx.Response(401, json={})

    with pytest.raises(ProviderError, match="Token"):
        asyncio.run(_provider(handler).search_round_trip(QUERY))
    assert calls["n"] == 1


def test_gives_up_after_retries(monkeypatch):
    monkeypatch.setattr("app.providers.duffel.asyncio.sleep", _no_sleep)

    def handler(request):
        raise httpx.ConnectTimeout("timeout")

    with pytest.raises(ProviderError, match="Zeitüberschreitung"):
        asyncio.run(_provider(handler).search_round_trip(QUERY))


def test_malformed_offers_are_skipped():
    bad = {**OFFER, "slices": [OFFER["slices"][0]]}
    broken = {**OFFER, "total_amount": None}
    p = _provider(lambda r: httpx.Response(201, json={"data": {"offers": [bad, broken, OFFER]}}))
    assert len(asyncio.run(p.search_round_trip(QUERY))) == 1


async def _no_sleep(_):
    return None
