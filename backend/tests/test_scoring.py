from app.models.schemas import (
    AircraftPreference,
    AircraftPreferenceStatus,
    OptimizationMode,
    UserPreferences,
    Weights,
)
from app.services.scoring import AircraftScorer, comfort_score, score_itineraries
from tests.conftest import make_itinerary, seg


def _trip(ref, aircraft_out: str, total: float, stops: bool = False, ret_day: str = "2027-01-02"):
    if stops:
        out = [seg(ref, "LX", "ZRH", "FRA", "2026-12-18T07:00", "2026-12-18T08:05", "221"),
               seg(ref, "LH", "FRA", "SIN", "2026-12-18T10:00", "2026-12-19T06:00", aircraft_out)]
    else:
        out = [seg(ref, "SQ", "ZRH", "SIN", "2026-12-18T11:00", "2026-12-19T06:00", aircraft_out)]
    back = [seg(ref, "SQ", "SIN", "ZRH", f"{ret_day}T23:00", "2027-01-03T06:00", "359")]
    return make_itinerary(ref, out, back, total)


def _prefs(mode=OptimizationMode.best_value, wishlist=("A380",), **kw):
    return UserPreferences(mode=mode, aircraft_preferences=[
        AircraftPreference(aircraft_type_id=w, status=AircraftPreferenceStatus.wishlist) for w in wishlist], **kw)


def test_coolness_rules(ref):
    prefs = UserPreferences(aircraft_preferences=[
        AircraftPreference(aircraft_type_id="AT76", status=AircraftPreferenceStatus.wishlist),
        AircraftPreference(aircraft_type_id="A380", status=AircraftPreferenceStatus.flown),
        AircraftPreference(aircraft_type_id="B789", status=AircraftPreferenceStatus.not_interested),
        AircraftPreference(aircraft_type_id="A320", status=AircraftPreferenceStatus.neutral, personal_rating=9),
    ], flown_factor=0.5)
    s = AircraftScorer(ref, prefs)
    assert s.coolness("AT76") == 9.0  # max(6, 7) + 2
    assert s.coolness("A380") == 5.0  # 10 × 0.5
    assert s.coolness("B789") == 0.0
    assert s.coolness("A320") == 9.0  # persönliche Bewertung überschreibt Default
    assert s.coolness(None) == 3.0
    assert s.is_wishlist("AT76") and not s.is_wishlist("A380")


def test_cheapest_mode_prefers_cheapest(ref):
    a380 = _trip(ref, "388", 1200)
    cheap = _trip(ref, "320", 900)
    ranked = score_itineraries([a380, cheap], _prefs(OptimizationMode.cheapest), ref, max_stay_days=16)
    assert ranked[0].itinerary.id == cheap.id
    assert ranked[0].extra_cost_vs_cheapest == 0


def test_aircraft_hunter_prefers_wishlist_with_premium(ref):
    a380 = _trip(ref, "388", 1020)
    cheap = _trip(ref, "320", 900)
    ranked = score_itineraries([cheap, a380], _prefs(OptimizationMode.aircraft_hunter), ref, max_stay_days=16)
    top = ranked[0]
    assert top.itinerary.id == a380.id
    assert top.wishlist_hits == ["A380"]
    assert top.aircraft_premium == 120
    assert any("120 EUR" in t for t in top.explanation.tradeoffs)
    assert any(h.aircraft_type_id == "A380" and h.wishlist for h in top.aircraft_highlights)
    assert any("geplant, nicht garantiert" in u for u in top.explanation.uncertainties)


def test_score_components_sum_to_total(ref):
    ranked = score_itineraries([_trip(ref, "388", 1000), _trip(ref, "320", 800, stops=True)], _prefs(), ref,
                               max_stay_days=16)
    for r in ranked:
        assert abs(sum(c.contribution for c in r.components) - r.total_score) < 0.05
        assert 0 <= r.total_score <= 100
    assert [r.rank for r in ranked] == [1, 2]


def test_custom_weights_override_mode(ref):
    prefs = _prefs(OptimizationMode.aircraft_hunter, weights=Weights(price=10, aircraft=0, travel_time=0, stops=0,
                                                                    stay=0, airline=0, comfort=0))
    ranked = score_itineraries([_trip(ref, "388", 1200), _trip(ref, "320", 900)], prefs, ref, max_stay_days=16)
    assert ranked[0].itinerary.price.total == 900
    assert ranked[0].total_score == 100


def test_longer_stay_scores_higher(ref):
    short = _trip(ref, "320", 900, ret_day="2026-12-24")
    long = _trip(ref, "320", 900, ret_day="2027-01-02")
    ranked = score_itineraries([short, long], _prefs(wishlist=()), ref, max_stay_days=16)
    stay = {r.itinerary.id: next(c for c in r.components if c.key == "stay").score for r in ranked}
    assert stay[long.id] > stay[short.id]
    assert ranked[0].itinerary.id == long.id


def test_cheap_but_long_trip_not_automatically_best(ref):
    direct = _trip(ref, "359", 950)
    cheap_connection = _trip(ref, "320", 900, stops=True)
    ranked = score_itineraries([cheap_connection, direct], _prefs(wishlist=()), ref, max_stay_days=16)
    assert ranked[0].itinerary.id == direct.id


def test_comfort_penalties(ref):
    tight_out = [seg(ref, "LX", "ZRH", "FRA", "2026-12-18T05:00", "2026-12-18T06:05", "221"),
                 seg(ref, "LH", "FRA", "SIN", "2026-12-18T06:40", "2026-12-19T02:40", "388")]
    back = [seg(ref, "SQ", "SIN", "ZRH", "2027-01-02T23:00", "2027-01-03T06:00", "359")]
    score, issues = comfort_score(make_itinerary(ref, tight_out, back, 900))
    assert score < 0.7
    assert any("knapper Umstieg" in i for i in issues)
    assert any("früher Abflug" in i for i in issues)


def test_preferred_airlines(ref):
    a = _trip(ref, "320", 900)  # SQ
    ranked = score_itineraries([a], _prefs(wishlist=(), preferred_airlines=["SQ"]), ref, max_stay_days=16)
    assert next(c for c in ranked[0].components if c.key == "airline").score == 1.0


def test_better_value_alternative_flagged(ref):
    pricey = _trip(ref, "359", 2000)
    value = _trip(ref, "359", 1000, ret_day="2027-01-01")
    ranked = score_itineraries([pricey, value], _prefs(wishlist=()), ref, max_stay_days=16)
    p = next(r for r in ranked if r.itinerary.id == pricey.id)
    assert p.explanation.better_value_alternative_id == value.id
