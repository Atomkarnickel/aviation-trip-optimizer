"""Flexible Reisezeiträume: sinnvolle Hin-/Rückflug-Kombinationen innerhalb eines Urlaubsfensters."""

from __future__ import annotations

from datetime import date, timedelta


def all_date_combinations(earliest: date, latest: date, min_stay: int, max_stay: int) -> list[tuple[date, date]]:
    """Alle (Abflug, Rückflug)-Paare mit min_stay ≤ Nächte ≤ max_stay im Fenster [earliest, latest]."""
    combos: list[tuple[date, date]] = []
    day = earliest
    while day < latest:
        for stay in range(min_stay, max_stay + 1):
            ret = day + timedelta(days=stay)
            if ret > latest:
                break
            combos.append((day, ret))
        day += timedelta(days=1)
    return combos


def plan_date_combinations(earliest: date, latest: date, min_stay: int, max_stay: int,
                           limit: int) -> tuple[list[tuple[date, date]], int]:
    """Wählt höchstens `limit` repräsentative Kombinationen aus.

    Immer enthalten: die längstmögliche Reise ab dem frühesten Abflug. Der Rest wird gleichmäßig über
    Abflugdaten und Aufenthaltsdauern verteilt, damit früh/spät und kurz/lang abgedeckt sind.
    Rückgabe: (Auswahl, Gesamtanzahl möglicher Kombinationen).
    """
    combos = all_date_combinations(earliest, latest, min_stay, max_stay)
    total = len(combos)
    if total <= limit:
        return combos, total
    longest = max(combos, key=lambda c: ((c[1] - c[0]).days, -c[0].toordinal()))
    chosen: list[tuple[date, date]] = [longest]
    if limit > 1:
        step = (total - 1) / (limit - 1)
        for i in range(limit):
            candidate = combos[round(i * step)]
            if candidate not in chosen:
                chosen.append(candidate)
            if len(chosen) >= limit:
                break
    return sorted(chosen), total
