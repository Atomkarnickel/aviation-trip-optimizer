from __future__ import annotations

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import inspect

from app.core.config import Settings
from app.db.session import make_engine
from app.main import create_app
from app.models.schemas import ProviderStatus, ReleaseStatus
from app.providers.base import FlightProvider, ProviderError

SEARCH = {"origins": ["ZRH"], "destinations": ["SIN"], "earliest_departure": "2026-12-18",
          "latest_return": "2027-01-06", "min_stay_days": 7, "max_stay_days": 16, "max_date_combinations": 3}


def _client(tmp_path, provider=None, **settings):
    s = Settings(database_url=f"sqlite:///{(tmp_path / 'test.db').as_posix()}", github_repository="", **settings)
    return TestClient(create_app(s, provider=provider))


@pytest.fixture
def demo(tmp_path):
    with _client(tmp_path, demo_mode=True) as c:
        yield c


def test_migrations_create_all_tables(tmp_path):
    with _client(tmp_path):
        pass
    tables = set(inspect(make_engine(f"sqlite:///{(tmp_path / 'test.db').as_posix()}")).get_table_names())
    assert {"airports", "airlines", "aircraft_types", "search_requests", "search_results",
            "release_information", "alembic_version"} <= tables


def test_unconfigured_provider_returns_no_fake_results(tmp_path):
    with _client(tmp_path) as c:
        assert c.get("/api/v1/meta").json()["provider"]["configured"] is False
        data = c.post("/api/v1/search", json=SEARCH).json()
        assert data["status"] == "provider_not_configured"
        assert data["results"] == []
        assert "DUFFEL_ACCESS_TOKEN" in data["warnings"][0]


def test_validation_errors(demo):
    bad = {**SEARCH, "origins": ["ZURICH"]}
    r = demo.post("/api/v1/search", json=bad)
    assert r.status_code == 422 and r.json()["errors"][0]["field"].startswith("origins")
    r = demo.post("/api/v1/search", json={**SEARCH, "min_stay_days": 20, "max_stay_days": 10})
    assert r.status_code == 422
    r = demo.post("/api/v1/search", json={**SEARCH, "destinations": ["ZRH"]})
    assert r.status_code == 422


def test_demo_search_is_labelled_and_ranked(demo):
    data = demo.post("/api/v1/search", json=SEARCH).json()
    assert data["status"] == "ok" and data["is_demo"] is True
    assert data["results"]
    assert all(r["itinerary"]["is_demo"] and r["itinerary"]["booking_options"] == [] for r in data["results"])
    scores = [r["total_score"] for r in data["results"]]
    assert scores == sorted(scores, reverse=True)
    assert data["cheapest_total"] == min(r["itinerary"]["price"]["total"] for r in data["results"])


def test_identical_search_uses_cache(demo):
    first = demo.post("/api/v1/search", json=SEARCH).json()
    second = demo.post("/api/v1/search", json={**SEARCH, "preferences": {"mode": "cheapest"}}).json()
    assert second["cached"] is True and second["search_id"] == first["search_id"]
    assert second["mode"] == "cheapest"


def test_budget_and_excluded_airline_filters(demo):
    base = demo.post("/api/v1/search", json=SEARCH).json()
    cheapest = base["cheapest_total"]
    limited = demo.post("/api/v1/search", json={**SEARCH, "max_budget": cheapest}).json()
    assert all(r["itinerary"]["price"]["total"] <= cheapest for r in limited["results"])
    assert limited["stats"]["filtered_out"].get("budget", 0) > 0
    none_left = demo.post("/api/v1/search", json={**SEARCH, "excluded_airlines": ["D1", "D2"]}).json()
    assert none_left["status"] == "no_results"


def test_rescore_and_compare(demo):
    data = demo.post("/api/v1/search", json=SEARCH).json()
    sid = data["search_id"]
    cheap = demo.post(f"/api/v1/search/{sid}/rescore", json={"preferences": {"mode": "cheapest"}}).json()
    assert cheap["results"][0]["itinerary"]["price"]["total"] == data["cheapest_total"]
    ids = [r["itinerary"]["id"] for r in data["results"][:3]]
    cmp = demo.post(f"/api/v1/search/{sid}/compare", json={"itinerary_ids": ids}).json()
    assert len(cmp["rows"][0]["values"]) == 3
    assert demo.post(f"/api/v1/search/{sid}/compare", json={"itinerary_ids": ["x", "y"]}).status_code == 404
    assert demo.get("/api/v1/search/doesnotexist").status_code == 404


def test_reference_endpoints(demo):
    aircraft = demo.get("/api/v1/aircraft").json()
    assert aircraft[0]["default_coolness"] >= aircraft[-1]["default_coolness"]
    dc10 = demo.get("/api/v1/aircraft/dc10").json()
    assert dc10["bookable"] is False and dc10["status"] == "historical"
    assert demo.get("/api/v1/airports", params={"q": "zür"}).json()[0]["iata"] == "ZRH"
    assert any(a["iata"] == "LX" for a in demo.get("/api/v1/airlines").json())
    assert set(demo.get("/api/v1/modes").json()) == {"best_value", "aircraft_hunter", "cheapest"}


class FailingProvider(FlightProvider):
    name = "failing"

    def status(self):
        return ProviderStatus(name="failing", configured=True, mode="live", message="")

    async def search_round_trip(self, query):
        raise ProviderError("Netzwerkfehler bei der Verbindung zu Duffel.", retryable=True)


def test_provider_errors_reported(tmp_path):
    with _client(tmp_path, provider=FailingProvider()) as c:
        data = c.post("/api/v1/search", json=SEARCH).json()
        assert data["status"] == "provider_error"
        assert data["results"] == []
        assert any("Netzwerkfehler" in w for w in data["warnings"])


def test_rate_limit(tmp_path):
    with _client(tmp_path, demo_mode=True, search_rate_limit_per_minute=2) as c:
        assert c.post("/api/v1/search", json=SEARCH).status_code == 200
        assert c.post("/api/v1/search", json=SEARCH).status_code == 200
        r = c.post("/api/v1/search", json=SEARCH)
        assert r.status_code == 429 and "Retry-After" in r.headers


def test_releases_endpoint_not_configured(demo):
    data = demo.get("/api/v1/releases/latest").json()
    assert ReleaseStatus.model_validate(data).status == "not_configured"
