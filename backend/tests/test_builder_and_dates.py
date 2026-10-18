from datetime import date

import pytest

from app.models.schemas import Ticketing
from app.services.dates import all_date_combinations, plan_date_combinations
from app.services.itinerary_builder import InvalidItinerary
from tests.conftest import make_itinerary, seg


def test_date_combinations_respect_stay_and_window():
    combos = all_date_combinations(date(2026, 12, 18), date(2027, 1, 6), 7, 16)
    assert combos
    assert all(7 <= (r - d).days <= 16 for d, r in combos)
    assert all(d >= date(2026, 12, 18) and r <= date(2027, 1, 6) for d, r in combos)
    assert (date(2026, 12, 18), date(2027, 1, 3)) in combos


def test_plan_includes_longest_trip_and_respects_limit():
    chosen, total = plan_date_combinations(date(2026, 12, 18), date(2027, 1, 6), 7, 19, 5)
    assert len(chosen) == 5
    assert total > 5
    assert (date(2026, 12, 18), date(2027, 1, 6)) in chosen  # gesamter Urlaub
    assert chosen == sorted(chosen)


def test_plan_returns_all_when_small():
    chosen, total = plan_date_combinations(date(2026, 5, 1), date(2026, 5, 5), 3, 4, 10)
    assert total == len(chosen) == 3


def test_timezone_and_date_line(ref):
    # LAX 22:30 -> SYD 06:40 übernächster Tag (Datumsgrenze): 13 h 10 min Flugzeit
    out = [seg(ref, "QF", "LAX", "SYD", "2026-12-18T22:30", "2026-12-20T06:40", "388")]
    back = [seg(ref, "QF", "SYD", "LAX", "2027-01-04T11:00", "2027-01-04T06:00", "388")]
    it = make_itinerary(ref, out, back, 1500)
    assert it.legs[0].duration_minutes == 13 * 60 + 10
    assert it.legs[1].duration_minutes == 14 * 60  # Ankunft "vor" Abflug in Ortszeit
    assert it.nights == 15
    assert it.full_stay_days == 14
    assert it.legs[0].segments[0].arrival_utc.isoformat() == "2026-12-19T19:40:00+00:00"


def test_connection_validation(ref):
    ok = [seg(ref, "LX", "ZRH", "FRA", "2026-12-18T07:00", "2026-12-18T08:05", "221"),
          seg(ref, "LH", "FRA", "SIN", "2026-12-18T10:00", "2026-12-19T06:00", "388")]
    tight = [seg(ref, "LX", "ZRH", "FRA", "2026-12-18T07:00", "2026-12-18T08:05", "221"),
             seg(ref, "LH", "FRA", "SIN", "2026-12-18T08:40", "2026-12-19T04:40", "388")]
    invalid = [seg(ref, "LX", "ZRH", "FRA", "2026-12-18T07:00", "2026-12-18T08:05", "221"),
               seg(ref, "LH", "FRA", "SIN", "2026-12-18T08:20", "2026-12-19T04:20", "388")]
    back = [seg(ref, "SQ", "SIN", "ZRH", "2027-01-02T23:00", "2027-01-03T06:00", "359")]
    assert make_itinerary(ref, ok, back, 900).legs[0].connections[0].risk == "ok"
    assert make_itinerary(ref, tight, back, 900).legs[0].connections[0].risk == "tight"
    assert make_itinerary(ref, invalid, back, 900).legs[0].connections[0].risk == "invalid"
    sep = make_itinerary(ref, ok, back, 900, ticketing=Ticketing.separate_tickets)
    assert sep.legs[0].connections[0].risk == "invalid"  # 115 min < MCT 45 + 120 Puffer


def test_airport_change_requires_transfer(ref):
    out = [seg(ref, "BA", "ZRH", "LHR", "2026-12-18T07:00", "2026-12-18T07:45", "320"),
           seg(ref, "VS", "LGW", "JFK", "2026-12-18T09:30", "2026-12-18T12:30", "789")]
    back = [seg(ref, "BA", "JFK", "ZRH", "2027-01-02T18:00", "2027-01-03T08:00", "77W")]
    conn = make_itinerary(ref, out, back, 900).legs[0].connections[0]
    assert conn.airport_change
    assert conn.min_connection_minutes >= 180
    assert conn.risk == "invalid"


def test_overnight_and_long_connection(ref):
    out = [seg(ref, "TK", "ZRH", "IST", "2026-12-18T19:00", "2026-12-18T23:00", "321"),
           seg(ref, "TK", "IST", "BKK", "2026-12-19T09:00", "2026-12-19T22:30", "77W")]
    back = [seg(ref, "TK", "BKK", "ZRH", "2027-01-02T10:00", "2027-01-02T19:00", "359")]
    conn = make_itinerary(ref, out, back, 700).legs[0].connections[0]
    assert conn.overnight and conn.risk == "long"


def test_return_before_arrival_rejected(ref):
    out = [seg(ref, "LH", "FRA", "JFK", "2026-12-18T10:00", "2026-12-18T13:00", "748")]
    back = [seg(ref, "LH", "JFK", "FRA", "2026-12-18T12:00", "2026-12-19T02:00", "748")]
    with pytest.raises(InvalidItinerary):
        make_itinerary(ref, out, back, 900)


def test_detour_ratio(ref):
    out = [seg(ref, "XX", "ZRH", "SIN", "2026-12-18T10:00", "2026-12-18T20:00", "320"),
           seg(ref, "XX", "SIN", "FRA", "2026-12-19T01:00", "2026-12-19T08:00", "320")]
    back = [seg(ref, "LH", "FRA", "ZRH", "2026-12-25T10:00", "2026-12-25T11:00", "320")]
    it = make_itinerary(ref, out, back, 300)
    assert it.legs[0].detour_ratio and it.legs[0].detour_ratio > 2.5


def test_booking_options_only_verified_airline_sites(ref):
    out = [seg(ref, "LX", "ZRH", "JFK", "2026-12-18T10:00", "2026-12-18T13:00", "333")]
    back = [seg(ref, "ZZ", "JFK", "ZRH", "2027-01-02T18:00", "2027-01-03T08:00", "333")]
    it = make_itinerary(ref, out, back, 900)
    assert [b.url for b in it.booking_options] == ["https://www.swiss.com"]
    assert all(not b.is_deep_link for b in it.booking_options)


def test_aircraft_resolution(ref):
    assert ref.resolve_aircraft("388") == "A380"
    assert ref.resolve_aircraft("74H") == "B748"
    assert ref.resolve_aircraft("AT7") == "AT76"
    assert ref.resolve_aircraft("XYZ") is None
    assert ref.aircraft["DC10"].bookable is False
    assert ref.aircraft_name("AT76") == "ATR 72"
