"""Transparente, testbare Bewertungsfunktion für vollständige Reisevarianten.

Jede Komponente liefert einen Wert zwischen 0 und 1. Der Gesamtscore ist der gewichtete Mittelwert × 100.
Die Gewichte stammen aus dem gewählten Modus oder aus nutzerdefinierten Gewichten.
"""

from __future__ import annotations

from dataclasses import dataclass

from app.models.schemas import (
    AircraftAssignmentStatus,
    AircraftHighlight,
    AircraftPreferenceStatus,
    Explanation,
    Itinerary,
    OptimizationMode,
    PriceSource,
    ScoreComponent,
    ScoredItinerary,
    Ticketing,
    UserPreferences,
    Weights,
)
from app.services.reference import ReferenceData

MODE_WEIGHTS: dict[OptimizationMode, Weights] = {
    OptimizationMode.best_value: Weights(price=5, aircraft=4, travel_time=3, stops=3, stay=3, airline=1, comfort=2),
    OptimizationMode.aircraft_hunter: Weights(price=2, aircraft=9, travel_time=2, stops=1, stay=2, airline=1,
                                              comfort=1),
    OptimizationMode.cheapest: Weights(price=10, aircraft=0, travel_time=1, stops=1, stay=1, airline=0, comfort=1),
}

LABELS = {
    "price": "Preis",
    "aircraft": "Flugzeugerlebnis",
    "travel_time": "Reisezeit",
    "stops": "Umstiege",
    "stay": "Aufenthaltsdauer",
    "airline": "Airline-Präferenz",
    "comfort": "Komfort & Umsteigequalität",
}

WISHLIST_BONUS = 2.0


def effective_weights(prefs: UserPreferences) -> Weights:
    return prefs.weights or MODE_WEIGHTS[prefs.mode]


def fmt_money(value: float, currency: str) -> str:
    return f"{value:,.0f} {currency}".replace(",", ".")


def fmt_minutes(minutes: int) -> str:
    return f"{minutes // 60} h {minutes % 60:02d} min"


@dataclass
class AircraftScorer:
    ref: ReferenceData
    prefs: UserPreferences

    def __post_init__(self) -> None:
        self._prefs = {p.aircraft_type_id.upper(): p for p in self.prefs.aircraft_preferences}

    def is_wishlist(self, type_id: str | None) -> bool:
        p = self._prefs.get((type_id or "").upper())
        return bool(p and p.status == AircraftPreferenceStatus.wishlist)

    def coolness(self, type_id: str | None) -> float:
        """Persönlicher Coolness-Wert 0–10 für einen Flugzeugtyp."""
        if not type_id:
            return 3.0  # unbekannter Typ: neutral-niedrig, keine Annahmen
        base = self.ref.aircraft[type_id].default_coolness if type_id in self.ref.aircraft else 3.0
        pref = self._prefs.get(type_id.upper())
        if pref is None:
            return base
        if pref.personal_rating is not None:
            base = pref.personal_rating
        if pref.status == AircraftPreferenceStatus.not_interested:
            return 0.0
        if pref.status == AircraftPreferenceStatus.wishlist:
            return min(10.0, max(base, 7.0) + WISHLIST_BONUS)
        if pref.status == AircraftPreferenceStatus.flown:
            return base * self.prefs.flown_factor
        return base

    def itinerary_score(self, itin: Itinerary) -> tuple[float, str]:
        segments = [s for leg in itin.legs for s in leg.segments]
        total_minutes = sum(s.duration_minutes for s in segments) or 1
        values = []
        for s in segments:
            c = self.coolness(s.aircraft_type_id)
            if s.aircraft_status == AircraftAssignmentStatus.unknown:
                c *= 0.5
            values.append((c, s.duration_minutes))
        best = max(v for v, _ in values)
        weighted = sum(v * m for v, m in values) / total_minutes
        score = (0.6 * best + 0.4 * weighted) / 10
        names = ", ".join(self.ref.aircraft_name(t) for t in itin.aircraft_type_ids) or "unbekannt"
        return min(1.0, score), f"Bestes Erlebnis {best:.1f}/10, Ø {weighted:.1f}/10 ({names})"


def comfort_score(itin: Itinerary) -> tuple[float, list[str]]:
    score = 1.0
    issues: list[str] = []
    for leg in itin.legs:
        for c in leg.connections:
            if c.risk == "tight":
                score -= 0.3
                issues.append(f"knapper Umstieg in {c.airport}")
            elif c.risk == "long":
                score -= 0.15
                issues.append(f"langer Aufenthalt in {c.airport}")
            if c.overnight:
                score -= 0.25
                issues.append(f"Übernachtung beim Umstieg in {c.airport}")
            if c.airport_change:
                score -= 0.3
                issues.append(f"Flughafenwechsel in {c.airport}")
        if not leg.details_complete and leg.stops > 0:
            score -= 0.1
            issues.append("Umsteigedetails unbekannt")
        if leg.detour_ratio and leg.detour_ratio > 1.35:
            score -= min(0.3, (leg.detour_ratio - 1.35))
            issues.append(f"Umweg (Faktor {leg.detour_ratio:.2f})")
        first, last = leg.segments[0], leg.segments[-1]
        if first.departure_local.hour < 6:
            score -= 0.1
            issues.append("sehr früher Abflug")
        if last.arrival_local.hour < 5:
            score -= 0.1
            issues.append("Ankunft mitten in der Nacht")
    if itin.ticketing == Ticketing.separate_tickets:
        score -= 0.3
        issues.append("getrennte Tickets")
    return max(0.0, score), issues


def _airline_score(itin: Itinerary, preferred: list[str]) -> tuple[float, str]:
    if not preferred:
        return 0.5, "Keine Airline-Präferenz gesetzt"
    carriers = [s.marketing_carrier for leg in itin.legs for s in leg.segments]
    share = sum(1 for c in carriers if c in preferred) / len(carriers)
    return share, f"{share:.0%} der Segmente mit bevorzugten Airlines"


def score_itineraries(
    itineraries: list[Itinerary],
    prefs: UserPreferences,
    ref: ReferenceData,
    *,
    max_stay_days: int,
) -> list[ScoredItinerary]:
    if not itineraries:
        return []
    weights = effective_weights(prefs)
    w = weights.model_dump()
    w_total = sum(w.values()) or 1.0
    aircraft_scorer = AircraftScorer(ref, prefs)
    preferred = [*prefs.preferred_airlines]

    min_price = min(i.price.total for i in itineraries)
    min_time = min(i.total_travel_minutes for i in itineraries)
    target_days = max(1, max_stay_days - 1)

    scored: list[ScoredItinerary] = []
    for itin in itineraries:
        a_score, a_detail = aircraft_scorer.itinerary_score(itin)
        c_score, c_issues = comfort_score(itin)
        al_score, al_detail = _airline_score(itin, preferred)
        stops_per_leg = itin.stops / max(1, len(itin.legs))
        raw = {
            "price": (min_price / itin.price.total if itin.price.total > 0 else 0.0,
                      f"{fmt_money(itin.price.total, itin.price.currency)} "
                      f"(+{fmt_money(itin.price.total - min_price, itin.price.currency)} ggü. günstigster)"),
            "aircraft": (a_score, a_detail),
            "travel_time": (min_time / itin.total_travel_minutes if itin.total_travel_minutes else 0.0,
                            f"{fmt_minutes(itin.total_travel_minutes)} Flugreisezeit gesamt"),
            "stops": (max(0.0, 1 - stops_per_leg / 2), f"{itin.stops} Umstieg(e) insgesamt"),
            "stay": (min(1.0, itin.full_stay_days / target_days),
                     f"{itin.full_stay_days} volle Tage vor Ort ({itin.nights} Nächte)"),
            "airline": (al_score, al_detail),
            "comfort": (c_score, ", ".join(c_issues) if c_issues else "Keine Auffälligkeiten"),
        }
        components = []
        total = 0.0
        for key, (score, detail) in raw.items():
            score = max(0.0, min(1.0, score))
            contribution = score * w[key] / w_total * 100
            total += contribution
            components.append(ScoreComponent(key=key, label=LABELS[key], score=round(score, 4),
                                             weight=w[key], contribution=round(contribution, 2), detail=detail))

        highlights: list[AircraftHighlight] = []
        hits: list[str] = []
        for leg in itin.legs:
            for s in leg.segments:
                if not s.aircraft_type_id:
                    continue
                wish = aircraft_scorer.is_wishlist(s.aircraft_type_id)
                if wish and s.aircraft_type_id not in hits:
                    hits.append(s.aircraft_type_id)
                if wish or aircraft_scorer.coolness(s.aircraft_type_id) >= 7:
                    highlights.append(AircraftHighlight(
                        aircraft_type_id=s.aircraft_type_id, name=ref.aircraft_name(s.aircraft_type_id),
                        status=s.aircraft_status, wishlist=wish,
                        segment=f"{s.marketing_carrier} {s.flight_number} {s.origin}–{s.destination}"))

        scored.append(ScoredItinerary(
            rank=0, itinerary=itin, total_score=round(total, 2), components=components,
            aircraft_highlights=highlights, wishlist_hits=hits,
            extra_cost_vs_cheapest=round(itin.price.total - min_price, 2),
            explanation=Explanation(summary="", reasons=[], tradeoffs=[], uncertainties=[])))

    if prefs.mode == OptimizationMode.cheapest and prefs.weights is None:
        # Cheapest Trip: harte Filter sind bereits angewendet, Rangfolge strikt nach Gesamtpreis.
        scored.sort(key=lambda s: (s.itinerary.price.total, -s.total_score))
    else:
        scored.sort(key=lambda s: (-s.total_score, s.itinerary.price.total))
    for i, s in enumerate(scored, start=1):
        s.rank = i
    _explain(scored, ref)
    return scored


def _explain(scored: list[ScoredItinerary], ref: ReferenceData) -> None:
    cheapest = min(scored, key=lambda s: s.itinerary.price.total)
    without_wish = [s for s in scored if not s.wishlist_hits]
    cheapest_without_wish = min(without_wish, key=lambda s: s.itinerary.price.total) if without_wish else None

    def value_ratio(s: ScoredItinerary) -> float:
        return s.total_score / max(1.0, s.itinerary.price.total)

    for s in scored:
        it = s.itinerary
        cur = it.price.currency
        top = sorted(s.components, key=lambda c: -c.contribution)[:2]
        reasons = [f"Stark bei {c.label}: {c.detail}" for c in top if c.weight > 0]
        if s.wishlist_hits:
            names = ", ".join(ref.aircraft_name(t) for t in s.wishlist_hits)
            reasons.append(f"Enthält Wunschflugzeug(e): {names}")
        reasons.append(f"{it.full_stay_days} volle Aufenthaltstage in {it.destination}")

        tradeoffs: list[str] = []
        if s.extra_cost_vs_cheapest > 0:
            tradeoffs.append(f"{fmt_money(s.extra_cost_vs_cheapest, cur)} teurer als die günstigste Option "
                             f"({fmt_money(cheapest.itinerary.price.total, cur)}).")
        else:
            tradeoffs.append("Günstigste gefundene Option.")
        if s.wishlist_hits and cheapest_without_wish and cheapest_without_wish is not s:
            premium = round(it.price.total - cheapest_without_wish.itinerary.price.total, 2)
            s.aircraft_premium = premium
            if premium > 0:
                per = premium / len(s.wishlist_hits)
                tradeoffs.append(f"Wunschflugzeug-Aufpreis: {fmt_money(premium, cur)} ggü. der günstigsten Reise "
                                 f"ohne Wunschflugzeug (≈ {fmt_money(per, cur)} pro Wunschtyp).")
            else:
                tradeoffs.append("Wunschflugzeug ohne Aufpreis ggü. Alternativen ohne Wunschflugzeug.")
        weak = [c for c in s.components if c.weight > 0 and c.score < 0.5]
        for c in weak[:2]:
            tradeoffs.append(f"Schwächer bei {c.label}: {c.detail}")

        uncertainties: list[str] = []
        if it.price_source == PriceSource.demo:
            uncertainties.append("Demodaten – keine echte Verfügbarkeit, kein echter Preis.")
        elif it.price_source == PriceSource.test_offer:
            uncertainties.append("Testmodus des Providers – kein echtes Angebot.")
        elif it.price_source == PriceSource.estimated:
            uncertainties.append("Geschätzter Preis aus zwischengespeicherten Suchen – vor Buchung prüfen.")
        if not it.aircraft_type_ids:
            uncertainties.append("Flugzeugtyp unbekannt – Typverlauf der Flugnummer prüfen.")
        if any(seg.aircraft_status != AircraftAssignmentStatus.confirmed
               for leg in it.legs for seg in leg.segments if seg.aircraft_type_id):
            uncertainties.append("Flugzeugtypen sind geplant, nicht garantiert – Gerätewechsel möglich.")
        if it.offer_expires_at:
            uncertainties.append(
                f"Angebot gültig bis {it.offer_expires_at:%d.%m.%Y %H:%M} UTC; Preis kann sich ändern.")
        if it.price.baggage_note:
            uncertainties.append(it.price.baggage_note)
        for leg in it.legs:
            for c in leg.connections:
                if c.risk in ("tight", "invalid") or c.airport_change:
                    uncertainties.append(c.note or f"Umstieg in {c.airport} prüfen")

        better = None
        better_note = None
        candidates = [o for o in scored if o is not s and o.itinerary.price.total < it.price.total
                      and value_ratio(o) > value_ratio(s) * 1.1 and o.total_score >= s.total_score - 8]
        if candidates:
            better = max(candidates, key=value_ratio)
            diff = it.price.total - better.itinerary.price.total
            better_note = (f"Rang {better.rank} ist {fmt_money(diff, cur)} günstiger bei nur "
                           f"{s.total_score - better.total_score:.1f} Punkten weniger – besseres Preis-Leistungs-"
                           f"Verhältnis.")

        stops_text = "nonstop" if it.stops == 0 else f"{it.stops} Umstieg(e)"
        aircraft_text = ", ".join(ref.aircraft_name(t) for t in it.aircraft_type_ids[:3]) or "Typ unbekannt"
        summary = (f"{it.origin} ⇄ {it.destination}, {it.outbound_date:%d.%m.}–{it.return_date:%d.%m.%Y}, "
                   f"{fmt_money(it.price.total, cur)}, {stops_text}, {aircraft_text}. Score {s.total_score:.0f}/100.")
        s.explanation = Explanation(summary=summary, reasons=reasons, tradeoffs=tradeoffs,
                                    uncertainties=list(dict.fromkeys(uncertainties)),
                                    better_value_alternative_id=better.itinerary.id if better else None,
                                    better_value_note=better_note)
