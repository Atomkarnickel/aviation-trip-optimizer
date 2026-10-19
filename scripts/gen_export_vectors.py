#!/usr/bin/env python3
"""Referenzimplementierung für Logbuch-Statistik, Logbuch-CSV und Kalender-Export (.ics).

Erzeugt shared/test-vectors/exports.json. Web (TypeScript) und Android (Kotlin) prüfen ihre Implementierungen
gegen genau diese erwarteten Werte, damit beide Plattformen identisch rechnen und identische Dateien schreiben.

Aufruf: python scripts/gen_export_vectors.py
"""

from __future__ import annotations

import json
import math
from datetime import UTC, datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

CSV_COLUMNS = ["date", "status", "airline", "flight_number", "origin", "destination", "aircraft_type_id", "notes"]
STATUS_TEXT = {
    "confirmed": "bestätigt",
    "scheduled": "geplant, nicht garantiert",
    "historical": "historisch beobachtet, nicht garantiert",
}


def distance_km(a: list[float], b: list[float]) -> float:
    lat1, lon1, lat2, lon2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2
    return 2 * 6371.0 * math.asin(math.sqrt(h))


def stats(entries: list[dict], airports: dict[str, list[float]], wishlist: list[str]) -> dict:
    flown = [e for e in entries if e["status"] == "flown"]
    total = 0.0
    unknown = 0
    longest = None
    counts: dict[str, int] = {}
    for e in flown:
        counts[e["aircraft_type_id"]] = counts.get(e["aircraft_type_id"], 0) + 1
        a, b = airports.get(e["origin"]), airports.get(e["destination"])
        if not a or not b:
            unknown += 1
            continue
        d = distance_km(a, b)
        total += d
        if longest is None or d > longest[1]:
            longest = (e["id"], d)
    top = sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))[:3]
    return {
        "flights": len(flown),
        "planned": len(entries) - len(flown),
        "types": len(counts),
        "distance_km": round(total),
        "unknown_distance": unknown,
        "longest": {"id": longest[0], "km": round(longest[1])} if longest else None,
        "top_types": [{"id": k, "count": v} for k, v in top],
        "wishlist_flown": sorted(w for w in wishlist if w in counts),
        "wishlist_total": len(wishlist),
    }


def csv_field(value: str) -> str:
    if any(c in value for c in ',"\r\n'):
        return '"' + value.replace('"', '""') + '"'
    return value


def to_csv(entries: list[dict]) -> str:
    lines = [",".join(CSV_COLUMNS)]
    for e in entries:
        lines.append(",".join(csv_field(e[c]) for c in CSV_COLUMNS))
    return "\r\n".join(lines) + "\r\n"


def ics_text(value: str) -> str:
    return value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")


def fold(line: str) -> list[str]:
    """RFC 5545: max. 75 Oktette je Zeile, Folgezeilen beginnen mit einem Leerzeichen; Zeichen nie zerteilen."""
    out: list[str] = []
    current, size, limit = "", 0, 75
    for ch in line:
        n = len(ch.encode("utf-8"))
        if size + n > limit:
            out.append(current)
            current, size, limit = " ", 1, 75
        current += ch
        size += n
    out.append(current)
    return out


def ics_utc(value: str) -> str:
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(UTC).strftime("%Y%m%dT%H%M%SZ")


def ics_local(value: str) -> str:
    return datetime.fromisoformat(value).strftime("%Y%m%dT%H%M%S")


def to_ics(calendar_name: str, flights: list[dict], now: str) -> str:
    lines = ["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Aviation Trip Optimizer//DE", "CALSCALE:GREGORIAN",
             "METHOD:PUBLISH", "X-WR-CALNAME:" + ics_text(calendar_name)]
    for f in flights:
        number = f" {f['flight_number']}" if f["flight_number"] else ""
        if f["aircraft"]:
            status = STATUS_TEXT.get(f["aircraft_status"], "Status unbekannt")
            aircraft = f"Flugzeug: {f['aircraft']} ({status})"
        else:
            aircraft = "Flugzeug: unbekannt"
        description = (aircraft + "\nNicht gebucht – Vorschlag aus Aviation Trip Optimizer. "
                       "Vor der Buchung Zeiten und Flugzeugtyp bei der Airline prüfen.")
        if f["departure_utc"] and f["arrival_utc"]:
            start, end = ics_utc(f["departure_utc"]), ics_utc(f["arrival_utc"])
        else:
            # Ohne UTC-Angabe: „floating“ Ortszeit (wird in der Zeitzone des Kalenders angezeigt)
            start, end = ics_local(f["departure_local"]), ics_local(f["arrival_local"])
        lines += ["BEGIN:VEVENT", f"UID:{f['uid']}@aviation-trip-optimizer", "DTSTAMP:" + ics_utc(now),
                  "DTSTART:" + start, "DTEND:" + end,
                  "SUMMARY:" + ics_text(f"✈ {f['carrier']}{number} {f['origin']} → {f['destination']}"),
                  "LOCATION:" + ics_text(f["origin"]), "DESCRIPTION:" + ics_text(description), "END:VEVENT"]
    lines.append("END:VCALENDAR")
    return "".join(part + "\r\n" for line in lines for part in fold(line))


def build_vectors() -> dict:
    airports = {"ZRH": [47.46, 8.55], "FRA": [50.03, 8.56], "DXB": [25.25, 55.36], "SIN": [1.36, 103.99],
                "SFO": [37.62, -122.38]}
    wishlist = ["A380", "B747", "A340"]
    entries = [
        {"id": "a", "date": "2026-09-01", "status": "flown", "airline": "LX", "flight_number": "38", "origin": "ZRH",
         "destination": "SFO", "aircraft_type_id": "A380", "notes": 'Oberdeck, "toll"'},
        {"id": "b", "date": "2026-08-15", "status": "flown", "airline": "EK", "flight_number": "88", "origin": "ZRH",
         "destination": "DXB", "aircraft_type_id": "A380", "notes": ""},
        {"id": "c", "date": "2026-07-01", "status": "flown", "airline": "LH", "flight_number": "1196",
         "origin": "FRA", "destination": "ZRH", "aircraft_type_id": "A320", "notes": "kurz; aber gut"},
        {"id": "d", "date": "2026-12-20", "status": "planned", "airline": "SQ", "flight_number": "345",
         "origin": "ZRH", "destination": "SIN", "aircraft_type_id": "B777", "notes": ""},
        {"id": "e", "date": "2026-05-05", "status": "flown", "airline": "", "flight_number": "", "origin": "",
         "destination": "", "aircraft_type_id": "B747", "notes": "Rundflug\nmit Zeilenumbruch"},
    ]
    flights = [
        {"uid": "it1-0", "carrier": "LX", "flight_number": "38", "origin": "ZRH", "destination": "SFO",
         "departure_utc": "2026-12-18T12:10:00Z", "arrival_utc": "2026-12-19T00:00:00Z",
         "departure_local": "2026-12-18T13:10:00", "arrival_local": "2026-12-18T16:00:00",
         "aircraft": "Airbus A380", "aircraft_status": "scheduled"},
        {"uid": "it1-1", "carrier": "LX", "flight_number": "", "origin": "SFO", "destination": "ZRH",
         "departure_utc": None, "arrival_utc": None,
         "departure_local": "2027-01-05T18:00:00", "arrival_local": "2027-01-06T14:00:00",
         "aircraft": None, "aircraft_status": "unknown"},
    ]
    now = "2026-10-19T05:00:00Z"
    calendar_name = "Reise ZRH → SFO (nicht gebucht)"
    return {
        "_comment": "Erzeugt von scripts/gen_export_vectors.py – nicht von Hand bearbeiten.",
        "logbook": {"airports": airports, "wishlist": wishlist, "entries": entries,
                    "expected_stats": stats(entries, airports, wishlist), "expected_csv": to_csv(entries)},
        "ics": {"calendar_name": calendar_name, "now": now, "flights": flights,
                "expected": to_ics(calendar_name, flights, now)},
    }


def render() -> str:
    return json.dumps(build_vectors(), ensure_ascii=False, indent=1) + "\n"


def main() -> None:
    out = ROOT / "shared" / "test-vectors" / "exports.json"
    out.write_text(render(), encoding="utf-8", newline="\n")
    print(f"{out.relative_to(ROOT)} geschrieben.")


if __name__ == "__main__":
    main()
