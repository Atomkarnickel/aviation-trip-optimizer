"""Tests für die kostenlosen Datenquellen: Travelpayouts, OpenSky/hexdb-Typverlauf, Preiskalender."""

import asyncio
from datetime import date

import httpx
from fastapi.testclient import TestClient

from app.core.config import Settings
from app.db.models import Base
from app.db.session import make_engine, make_session_factory
from app.main import create_app
from app.models.schemas import PriceSource
from app.providers.base import WindowQuery
from app.providers.travelpayouts import TravelpayoutsProvider, month_pairs
from app.services.aircraft_history import AircraftHistoryService, callsign_for
from app.services.search import build_calendar

WINDOW = WindowQuery("ZRH", "SIN", date(2026, 12, 18), date(2027, 1, 6), 7, 16, 2, 1, "EUR")


def ticket(**over):
    base = {
        "origin": "ZRH", "destination": "SIN", "origin_airport": "ZRH", "destination_airport": "SIN",
        "price": 812, "airline": "SQ", "flight_number": "345",
        "departure_at": "2026-12-19T11:25:00+01:00", "return_at": "2027-01-03T23:55:00+08:00",
        "transfers": 0, "return_transfers": 1, "duration": 1580, "duration_to": 710, "duration_back": 870,
        "link": "/search/ZRH1912SIN03011?t=SQ123",
    }
    base.update(over)
    return base


def tp(handler, marker=None):
    from app.services.reference import get_reference
    return TravelpayoutsProvider("tok", get_reference(), marker=marker, transport=httpx.MockTransport(handler),
                                 max_retries=0)


def test_month_pairs_cover_christmas_window():
    # Abflug im Januar ist bei >= 7 Nächten und Rückkehr bis 06.01. unmöglich -> 2 Paare
    assert month_pairs(date(2026, 12, 18), date(2027, 1, 6), 7) == [("2026-12", "2026-12"), ("2026-12", "2027-01")]
    assert month_pairs(date(2026, 12, 18), date(2027, 1, 20), 7) == [("2026-12", "2026-12"), ("2026-12", "2027-01"),
                                                                     ("2027-01", "2027-01")]
    assert month_pairs(date(2026, 5, 1), date(2026, 5, 20), 3) == [("2026-05", "2026-05")]


def test_travelpayouts_parsing_is_honest():
    seen = []

    def handler(req: httpx.Request):
        seen.append(req)
        return httpx.Response(200, json={"success": True, "currency": "eur", "data": [
            ticket(), ticket(transfers=1, flight_number="999", price=640, departure_at="2026-12-20T07:00:00+01:00"),
            ticket(departure_at="2026-12-01T10:00:00+01:00"),  # außerhalb des Fensters
            ticket(price=0),
        ]})

    items, calls = asyncio.run(tp(handler, marker="12345").search_window(WINDOW))
    assert calls == 2 and len(seen) == 2
    assert seen[0].headers["X-Access-Token"] == "tok"
    assert seen[0].url.params["one_way"] == "false" and seen[0].url.params["currency"] == "eur"
    assert len(items) == 4 and len({i.id for i in items}) == 2  # Mock liefert je Monatspaar dieselben Daten
    direct = next(i for i in items if i.legs[0].stops == 0)
    assert direct.price_source == PriceSource.estimated
    assert direct.price.total == 1624 and direct.price.per_traveler == 812
    seg = direct.legs[0].segments[0]
    assert seg.flight_number == "345" and seg.aircraft_type_id is None and seg.aircraft_status.value == "unknown"
    assert seg.departure_utc.isoformat() == "2026-12-19T10:25:00+00:00"
    assert seg.arrival_local.isoformat() == "2026-12-20T06:15:00"
    # Rückflug mit 1 Umstieg: keine erfundenen Segmente/Flugnummern
    back = direct.legs[1]
    assert back.stops == 1 and not back.details_complete and back.segments[0].flight_number == ""
    assert direct.stops == 1
    with_stop = next(i for i in items if i.legs[0].stops == 1)
    assert with_stop.legs[0].segments[0].flight_number == ""  # Flugnummer nicht eindeutig -> nicht übernommen
    assert direct.booking_options[0].kind == "meta_search"
    assert direct.booking_options[0].url.startswith("https://www.aviasales.com/search/") and "marker=12345" in \
        direct.booking_options[0].url
    assert any(b.url == "https://www.singaporeair.com" for b in direct.booking_options)
    assert any("Umsteigeflughäfen" in n for n in direct.data_notes)


def test_travelpayouts_errors():
    from app.providers.base import ProviderError

    p = tp(lambda r: httpx.Response(401))
    try:
        asyncio.run(p.search_window(WINDOW))
        raise AssertionError("erwarteter Fehler")
    except ProviderError as exc:
        assert "TRAVELPAYOUTS_TOKEN" in str(exc)
    p = tp(lambda r: httpx.Response(200, json={"success": False, "error": "bad"}))
    try:
        asyncio.run(p.search_window(WINDOW))
        raise AssertionError("erwarteter Fehler")
    except ProviderError as exc:
        assert "bad" in str(exc)


def test_window_search_through_api_with_calendar(tmp_path):
    def handler(req):
        return httpx.Response(200, json={"success": True, "data": [
            ticket(), ticket(price=700, departure_at="2026-12-19T15:00:00+01:00", flight_number="347"),
            ticket(price=650, departure_at="2026-12-22T11:25:00+01:00", return_at="2027-01-04T23:55:00+08:00")]})

    s = Settings(database_url=f"sqlite:///{(tmp_path / 't.db').as_posix()}", github_repository="")
    with TestClient(create_app(s, provider=tp(handler))) as c:
        assert c.get("/api/v1/meta").json()["provider"]["mode"] == "estimated"
        data = c.post("/api/v1/search", json={"origins": ["ZRH"], "destinations": ["SIN"],
                                                "earliest_departure": "2026-12-18", "latest_return": "2027-01-06",
                                                "min_stay_days": 7, "max_stay_days": 16}).json()
        assert data["status"] == "ok"
        assert data["stats"]["provider_calls"] == 2
        assert any("Richtwerte" in w for w in data["warnings"])
        cal = {(c["departure_date"], c["nights"]): c for c in data["calendar"]}
        # Abflug 19.12., Ankunft 20.12. in SIN, Rückflug 03.01. -> 14 Nächte; zwei Angebote, günstigstes 700 p. P.
        assert cal[("2026-12-19", 14)]["min_price"] == 700 and cal[("2026-12-19", 14)]["offers"] == 2
        assert ("2026-12-22", 12) in cal


def test_calendar_from_itineraries_only():
    assert build_calendar([]) == []


# ------------------------------------------------------------------ Typverlauf


def history_service(tmp_path, handler, **settings):
    tmp_path.mkdir(parents=True, exist_ok=True)
    engine = make_engine(f"sqlite:///{(tmp_path / 'h.db').as_posix()}")
    Base.metadata.create_all(engine)
    from app.services.reference import get_reference
    settings.setdefault("opensky_client_id", "cid")
    settings.setdefault("opensky_client_secret", "secret")
    s = Settings(database_url="sqlite://", **settings)
    return AircraftHistoryService(s, get_reference(), make_session_factory(engine), httpx.MockTransport(handler),
                                  today=date(2026, 10, 18))


def test_callsign():
    assert callsign_for("SWR", "038") == "SWR38"
    assert callsign_for("SWR", "38") == "SWR38"
    assert callsign_for("SWR", "") is None
    assert callsign_for("SWR", "ab") is None


def test_history_counts_observed_types_and_caches(tmp_path):
    calls = {"opensky": 0, "hexdb": 0}

    def handler(req: httpx.Request):
        if req.url.host == "auth.opensky-network.org":
            return httpx.Response(200, json={"access_token": "tok", "expires_in": 1800})
        if req.url.host == "opensky-network.org":
            assert req.headers["Authorization"] == "Bearer tok"
            calls["opensky"] += 1
            assert req.url.params["airport"] == "LSZH"
            day = int(req.url.params["begin"])
            hex_ = "4b1801" if day % (3 * 86400) else "4b1802"
            return httpx.Response(200, json=[{"callsign": "SWR38   ", "icao24": hex_},
                                             {"callsign": "DLH1234", "icao24": "3c0000"}])
        calls["hexdb"] += 1
        types = {"4b1801": ("A343", "HB-JMA", "Airbus A340-313"), "4b1802": ("A333", "HB-JHA", "Airbus A330-343")}
        t = types[req.url.path.rsplit("/", 1)[-1]]
        return httpx.Response(200, json={"ICAOTypeCode": t[0], "Registration": t[1], "Type": t[2]})

    svc = history_service(tmp_path, handler)
    h = asyncio.run(svc.history("LX", "38", "ZRH", days=5))
    assert h.status == "ok" and h.callsign == "SWR38" and h.days_checked == 5
    assert len(h.observations) == 5
    assert sum(d.count for d in h.distribution) == 5
    assert {d.aircraft_type_id for d in h.distribution} <= {"A343", "A333"}
    assert h.distribution[0].share >= 0.5
    assert calls == {"opensky": 5, "hexdb": 2}
    # zweiter Aufruf komplett aus dem Cache
    asyncio.run(svc.history("LX", "38", "ZRH", days=5))
    assert calls == {"opensky": 5, "hexdb": 2}


def with_token(fallback):
    def handler(req: httpx.Request):
        if req.url.host == "auth.opensky-network.org":
            return httpx.Response(200, json={"access_token": "tok", "expires_in": 1800})
        return fallback(req)
    return handler


def test_history_requires_free_opensky_account(tmp_path):
    svc = history_service(tmp_path, lambda r: httpx.Response(500), opensky_client_id=None)
    h = asyncio.run(svc.history("LX", "38", "ZRH"))
    assert h.status == "not_configured" and "OPENSKY_CLIENT_ID" in h.message


def test_history_limits_and_unsupported(tmp_path):
    svc = history_service(tmp_path, with_token(lambda r: httpx.Response(429)))
    h = asyncio.run(svc.history("LX", "38", "ZRH", days=3))
    assert h.status == "unavailable" and "Kontingent" in h.message
    bad_login = history_service(tmp_path / "b", lambda r: httpx.Response(401))
    assert "Anmeldung" in asyncio.run(bad_login.history("LX", "38", "ZRH", days=1)).message
    assert asyncio.run(svc.history("ZZ", "38", "ZRH")).status == "not_supported"
    assert asyncio.run(svc.history("LX", "38", "XXX")).status == "not_supported"


def test_history_no_observations(tmp_path):
    svc = history_service(tmp_path, with_token(lambda r: httpx.Response(404)))
    h = asyncio.run(svc.history("LX", "38", "ZRH", days=2))
    assert h.status == "no_observations" and "Rufzeichen" in h.message


def test_history_endpoint_validation(tmp_path):
    s = Settings(database_url=f"sqlite:///{(tmp_path / 'e.db').as_posix()}", github_repository="",
                 opensky_client_id="cid", opensky_client_secret="secret")
    transport = httpx.MockTransport(with_token(lambda r: httpx.Response(404)))
    with TestClient(create_app(s, history_transport=transport)) as c:
        assert c.get("/api/v1/aircraft-history", params={"carrier": "LX", "flight_number": "38<",
                                                         "origin": "ZRH"}).status_code == 422
        r = c.get("/api/v1/aircraft-history", params={"carrier": "LX", "flight_number": "38", "origin": "ZRH",
                                                      "days": 1})
        assert r.status_code == 200 and r.json()["status"] == "no_observations"
