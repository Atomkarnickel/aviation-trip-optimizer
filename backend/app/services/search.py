"""Orchestrierung der Suche: Datumsplanung → Provider-Abfragen → Filter → Bewertung → Persistenz."""

from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import uuid
from collections import Counter
from datetime import UTC, datetime, timedelta

from sqlalchemy import select
from sqlalchemy.orm import Session, sessionmaker

from app.core.config import Settings
from app.db.models import SearchRequestRow, SearchResultRow
from app.models.schemas import (
    AircraftPreference,
    AircraftPreferenceStatus,
    CalendarCell,
    CompareResponse,
    CompareRow,
    Itinerary,
    SearchRequest,
    SearchResponse,
    SearchStats,
    UserPreferences,
)
from app.providers.base import FlightProvider, ProviderError, RoundTripQuery, WindowQuery
from app.services.dates import plan_date_combinations
from app.services.reference import OPEN_SEARCH_CANDIDATES, ReferenceData
from app.services.scoring import effective_weights, fmt_minutes, fmt_money, score_itineraries

log = logging.getLogger(__name__)

MAX_DETOUR_RATIO = 2.5
PROVIDER_FIELDS = ("origins", "destinations", "earliest_departure", "latest_return", "min_stay_days",
                   "max_stay_days", "adults", "cabin_class", "currency", "max_stops", "max_date_combinations")


class SearchNotFound(LookupError):
    pass


def provider_request_hash(req: SearchRequest, provider: str) -> str:
    data = req.model_dump(mode="json", include=set(PROVIDER_FIELDS))
    return hashlib.sha256(f"{provider}|{json.dumps(data, sort_keys=True)}".encode()).hexdigest()


def merged_preferences(req: SearchRequest, prefs: UserPreferences | None = None) -> UserPreferences:
    """Ergänzt die Präferenzen um Wunschflugzeuge/Airlines aus der Suchmaske."""
    prefs = (prefs or req.preferences).model_copy(deep=True)
    known = {p.aircraft_type_id.upper() for p in prefs.aircraft_preferences}
    for type_id in req.desired_aircraft:
        if type_id.upper() not in known:
            prefs.aircraft_preferences.append(
                AircraftPreference(aircraft_type_id=type_id.upper(), status=AircraftPreferenceStatus.wishlist))
    for code in req.preferred_airlines:
        if code not in prefs.preferred_airlines:
            prefs.preferred_airlines.append(code)
    return prefs


def build_calendar(itins: list[Itinerary]) -> list[CalendarCell]:
    """Günstigster Preis je (Abflugdatum, Nächte) – ausschließlich aus erhaltenen Angeboten."""
    best: dict[tuple, Itinerary] = {}
    counts: Counter = Counter()
    for it in itins:
        key = (it.outbound_date, it.nights, it.price.currency)
        counts[key] += 1
        if key not in best or it.price.total < best[key].price.total:
            best[key] = it
    return [CalendarCell(departure_date=k[0], nights=k[1], min_price=v.price.total, currency=k[2], offers=counts[k],
                         itinerary_id=v.id) for k, v in sorted(best.items(), key=lambda kv: (kv[0][0], kv[0][1]))]


def apply_filters(itins: list[Itinerary], req: SearchRequest) -> tuple[list[Itinerary], Counter]:
    removed: Counter = Counter()
    kept: list[Itinerary] = []
    seen: set[str] = set()
    excluded = set(req.excluded_airlines)
    for it in itins:
        if it.id in seen:
            removed["duplicate"] += 1
            continue
        seen.add(it.id)
        segments = [s for leg in it.legs for s in leg.segments]
        reason = None
        if req.max_budget is not None and it.price.total > req.max_budget:
            reason = "budget"
        elif excluded and any(s.marketing_carrier in excluded or (s.operating_carrier or "") in excluded
                              for s in segments):
            reason = "excluded_airline"
        elif any(leg.stops > req.max_stops for leg in it.legs):
            reason = "stops"
        elif req.max_travel_hours and any(leg.duration_minutes > req.max_travel_hours * 60 for leg in it.legs):
            reason = "travel_time"
        elif req.outbound_departure_window and not req.outbound_departure_window.contains(
                it.legs[0].segments[0].departure_local.hour):
            reason = "time_window"
        elif req.return_departure_window and not req.return_departure_window.contains(
                it.legs[-1].segments[0].departure_local.hour):
            reason = "time_window"
        elif any(c.risk == "invalid" for leg in it.legs for c in leg.connections):
            reason = "connection"
        elif any(leg.detour_ratio and leg.detour_ratio > MAX_DETOUR_RATIO for leg in it.legs):
            reason = "detour"
        elif it.nights < req.min_stay_days - 1 or it.nights > req.max_stay_days + 1:
            reason = "stay"
        if reason:
            removed[reason] += 1
        else:
            kept.append(it)
    return kept, removed


class SearchService:
    def __init__(self, settings: Settings, ref: ReferenceData, provider: FlightProvider,
                 session_factory: sessionmaker[Session]) -> None:
        self.settings = settings
        self.ref = ref
        self.provider = provider
        self.session_factory = session_factory
        self._inflight: dict[str, asyncio.Future] = {}

    # ------------------------------------------------------------------ öffentliche API
    async def search(self, req: SearchRequest) -> SearchResponse:
        status = self.provider.status()
        if not status.configured:
            return self._respond(str(uuid.uuid4().hex), req, [], "provider_not_configured",
                                 SearchStats(combinations_total=0, combinations_searched=0, provider_calls=0,
                                             provider_errors=0, offers_received=0, filtered_out={}),
                                 [status.message], cached=False)

        key = provider_request_hash(req, f"{self.provider.name}:{status.mode}")
        cached = self._load_cached(key)
        if cached:
            return self._from_row(cached, req, cached=True)

        # Schutz vor doppelten, gleichzeitigen Anfragen: identische Suchen teilen sich eine Provider-Abfrage.
        if key in self._inflight:
            row_id = await asyncio.shield(self._inflight[key])
            return self._from_row(self._get_row(row_id), req, cached=True)
        fut: asyncio.Future = asyncio.get_running_loop().create_future()
        self._inflight[key] = fut
        try:
            row_id = await self._run_search(req, key)
            fut.set_result(row_id)
        except BaseException as exc:
            fut.set_exception(exc)
            fut.exception()  # als abgerufen markieren
            raise
        finally:
            self._inflight.pop(key, None)
        return self._from_row(self._get_row(row_id), req, cached=False)

    def get(self, search_id: str, prefs: UserPreferences | None = None) -> SearchResponse:
        row = self._get_row(search_id)
        req = SearchRequest.model_validate(row.request_json)
        if prefs is not None:
            req = req.model_copy(update={"preferences": prefs})
        return self._from_row(row, req, cached=True)

    def compare(self, search_id: str, ids: list[str]) -> CompareResponse:
        resp = self.get(search_id)
        by_id = {r.itinerary.id: r for r in resp.results}
        missing = [i for i in ids if i not in by_id]
        if missing:
            raise SearchNotFound(f"Unbekannte Reise-ID(s): {', '.join(missing)}")
        items = [by_id[i] for i in ids]

        def best(values: list[float], higher: bool) -> int:
            target = max(values) if higher else min(values)
            return values.index(target)

        cur = items[0].itinerary.price.currency
        rows = [
            CompareRow(label="Gesamtscore", values=[f"{s.total_score:.0f}/100" for s in items],
                       best_index=best([s.total_score for s in items], True)),
            CompareRow(label="Gesamtpreis", values=[fmt_money(s.itinerary.price.total, cur) for s in items],
                       best_index=best([s.itinerary.price.total for s in items], False)),
            CompareRow(label="Reisedaten",
                       values=[f"{s.itinerary.outbound_date:%d.%m.}–{s.itinerary.return_date:%d.%m.%Y}"
                               for s in items]),
            CompareRow(label="Volle Aufenthaltstage", values=[str(s.itinerary.full_stay_days) for s in items],
                       best_index=best([s.itinerary.full_stay_days for s in items], True)),
            CompareRow(label="Flugreisezeit", values=[fmt_minutes(s.itinerary.total_travel_minutes) for s in items],
                       best_index=best([s.itinerary.total_travel_minutes for s in items], False)),
            CompareRow(label="Umstiege", values=[str(s.itinerary.stops) for s in items],
                       best_index=best([s.itinerary.stops for s in items], False)),
            CompareRow(label="Flugzeugtypen",
                       values=[", ".join(self.ref.aircraft_name(t) for t in s.itinerary.aircraft_type_ids) or "–"
                               for s in items]),
            CompareRow(label="Wunschflugzeuge", values=[str(len(s.wishlist_hits)) for s in items],
                       best_index=best([len(s.wishlist_hits) for s in items], True)),
            CompareRow(label="Airlines", values=[", ".join(s.itinerary.airlines) for s in items]),
        ]
        for comp_index, comp in enumerate(items[0].components):
            values = [s.components[comp_index].score for s in items]
            rows.append(CompareRow(label=f"Teilscore {comp.label}", values=[f"{v:.0%}" for v in values],
                                   best_index=best(values, True)))
        return CompareResponse(search_id=search_id, itinerary_ids=ids, rows=rows)

    # ------------------------------------------------------------------ intern
    def _routes(self, req: SearchRequest) -> list[tuple[str, str]]:
        destinations = req.destinations or [d for d in OPEN_SEARCH_CANDIDATES if d not in req.origins]
        return [(o, d) for o in req.origins for d in destinations if o != d]

    async def _run_search(self, req: SearchRequest, key: str) -> str:
        warnings: list[str] = []
        routes = self._routes(req)
        budget = self.settings.max_provider_calls_per_search
        if len(routes) > budget:
            warnings.append(f"Zu viele Routen ({len(routes)}); nur die ersten {budget} werden abgefragt.")
            routes = routes[:budget]
        per_route = max(1, min(req.max_date_combinations, budget // max(1, len(routes))))
        combos, total = plan_date_combinations(req.earliest_departure, req.latest_return, req.min_stay_days,
                                               req.max_stay_days, per_route)
        if total > len(combos):
            warnings.append(f"Flexible Daten: {len(combos)} von {total} möglichen Datumskombinationen je Route "
                            f"geprüft (gleichmäßig verteilt).")
        if not req.destinations:
            warnings.append("Offene Zielsuche: geprüft werden die Drehkreuze " + ", ".join(d for _, d in routes[:10])
                            + ".")

        if self.provider.supports_window:
            return await self._run_window_search(req, key, routes, total, warnings)

        queries = [RoundTripQuery(origin=o, destination=d, departure_date=dep, return_date=ret, adults=req.adults,
                                  cabin_class=req.cabin_class, max_connections=req.max_stops,
                                  currency=req.currency)
                   for o, d in routes for dep, ret in combos]
        sem = asyncio.Semaphore(self.settings.provider_concurrency)
        errors: list[str] = []

        async def run(q: RoundTripQuery) -> list[Itinerary]:
            async with sem:
                try:
                    return await self.provider.search_round_trip(q)
                except ProviderError as exc:
                    errors.append(str(exc))
                    log.warning("Provider-Fehler %s→%s %s: %s", q.origin, q.destination, q.departure_date, exc)
                    return []

        batches = await asyncio.gather(*(run(q) for q in queries))
        itineraries = [it for batch in batches for it in batch]

        if errors and len(errors) == len(queries):
            result_status = "provider_error"
        elif errors:
            result_status = "partial"
        else:
            result_status = "ok"
        for msg in dict.fromkeys(errors):
            warnings.append(f"Provider-Fehler: {msg}")

        currencies = {it.price.currency for it in itineraries}
        if len(currencies) > 1:
            warnings.append("Angebote in unterschiedlichen Währungen (" + ", ".join(sorted(currencies)) +
                            "). Es erfolgt keine Umrechnung – Preisvergleich eingeschränkt.")
        elif currencies and req.currency not in currencies:
            warnings.append(f"Der Provider liefert Preise in {currencies.pop()}, nicht in {req.currency}. "
                            "Es erfolgt keine Umrechnung.")

        stats = {"combinations_total": total * len(routes), "combinations_searched": len(queries),
                 "provider_calls": len(queries), "provider_errors": len(errors),
                 "offers_received": len(itineraries)}
        return self._store(req, key, result_status, stats, warnings, itineraries)

    async def _run_window_search(self, req: SearchRequest, key: str, routes: list[tuple[str, str]], total: int,
                                 warnings: list[str]) -> str:
        """Provider mit Zeitfenster-Suche (z. B. Travelpayouts): wenige Abfragen decken alle Datumspaare ab."""
        warnings = [w for w in warnings if not w.startswith("Flexible Daten")]
        sem = asyncio.Semaphore(self.settings.provider_concurrency)
        errors: list[str] = []
        calls = 0

        async def run(origin: str, dest: str) -> list[Itinerary]:
            nonlocal calls
            q = WindowQuery(origin, dest, req.earliest_departure, req.latest_return, req.min_stay_days,
                            req.max_stay_days, req.adults, req.max_stops, req.currency)
            async with sem:
                try:
                    items, n = await self.provider.search_window(q)
                    calls += n
                    return items
                except ProviderError as exc:
                    calls += 1
                    errors.append(str(exc))
                    return []

        batches = await asyncio.gather(*(run(o, d) for o, d in routes))
        itineraries = [it for b in batches for it in b]
        status = "provider_error" if errors and len(errors) == len(routes) else "partial" if errors else "ok"
        for msg in dict.fromkeys(errors):
            warnings.append(f"Provider-Fehler: {msg}")
        if itineraries:
            warnings.append("Preise sind zwischengespeicherte Richtwerte (bis ca. 7 Tage alt) – vor Buchung prüfen.")
        stats = {"combinations_total": total * len(routes), "combinations_searched": total * len(routes),
                 "provider_calls": calls, "provider_errors": len(errors), "offers_received": len(itineraries)}
        return self._store(req, key, status, stats, warnings, itineraries)

    def _store(self, req: SearchRequest, key: str, status: str, stats: dict, warnings: list[str],
               itineraries: list[Itinerary]) -> str:
        row_id = uuid.uuid4().hex
        with self.session_factory() as session:
            row = SearchRequestRow(id=row_id, request_hash=key, request_json=req.model_dump(mode="json"),
                                   provider=self.provider.name, provider_mode=self.provider.status().mode,
                                   status=status, stats_json=stats, warnings_json=warnings,
                                   created_at=datetime.now(UTC))
            for it in itineraries:
                row.results.append(SearchResultRow(itinerary_id=it.id, price_total=it.price.total,
                                                   currency=it.price.currency,
                                                   itinerary_json=it.model_dump(mode="json")))
            session.add(row)
            session.commit()
        log.info("Suche %s: %d Angebote, Status %s", row_id, len(itineraries), status)
        return row_id

    def _load_cached(self, key: str) -> SearchRequestRow | None:
        cutoff = datetime.now(UTC) - timedelta(seconds=self.settings.search_cache_ttl_seconds)
        with self.session_factory() as session:
            stmt = (select(SearchRequestRow).where(SearchRequestRow.request_hash == key)
                    .where(SearchRequestRow.status.in_(["ok", "partial"]))
                    .order_by(SearchRequestRow.created_at.desc()).limit(1))
            row = session.scalars(stmt).first()
            if row is None:
                return None
            created = row.created_at if row.created_at.tzinfo else row.created_at.replace(tzinfo=UTC)
            if created < cutoff:
                return None
            _ = row.results  # laden, bevor die Session schließt
            return row

    def _get_row(self, search_id: str) -> SearchRequestRow:
        with self.session_factory() as session:
            row = session.get(SearchRequestRow, search_id)
            if row is None:
                raise SearchNotFound("Suche nicht gefunden oder abgelaufen.")
            _ = row.results
            return row

    def _from_row(self, row: SearchRequestRow, req: SearchRequest, *, cached: bool) -> SearchResponse:
        itins = [Itinerary.model_validate(r.itinerary_json) for r in row.results]
        kept, removed = apply_filters(itins, req)
        warnings = list(row.warnings_json)
        if req.require_checked_bag and any(
                (it.price.baggage_note or "").startswith(("Aufgabegepäck nicht", "Gepäckregeln nicht"))
                for it in kept):
            warnings.append("Nicht alle Angebote enthalten Aufgabegepäck – mögliche Zusatzkosten sind im "
                            "Gesamtpreis nicht enthalten.")
        status = row.status
        if status in ("ok", "partial") and not kept:
            status = "no_results"
        stats = SearchStats(**row.stats_json, filtered_out=dict(removed))
        resp = self._respond(row.id, req, kept, status, stats, warnings, cached=cached,
                             generated_at=row.created_at)
        resp.calendar = build_calendar(kept)
        return resp

    def _respond(self, search_id: str, req: SearchRequest, itins: list[Itinerary], status: str,
                 stats: SearchStats, warnings: list[str], *, cached: bool,
                 generated_at: datetime | None = None) -> SearchResponse:
        prefs = merged_preferences(req)
        scored = score_itineraries(itins, prefs, self.ref, max_stay_days=req.max_stay_days)
        provider_status = self.provider.status()
        return SearchResponse(
            search_id=search_id, status=status, provider=provider_status,
            is_demo=provider_status.mode == "demo", generated_at=generated_at or datetime.now(UTC),
            cached=cached, mode=prefs.mode, weights=effective_weights(prefs), results=scored[:50],
            cheapest_total=min((i.price.total for i in itins), default=None), stats=stats, warnings=warnings)
