"""Travelpayouts / Aviasales Data API – kostenlose, zwischengespeicherte Preise.

Endpunkt: GET https://api.travelpayouts.com/aviasales/v3/prices_for_dates
Die Daten stammen aus Suchen anderer Nutzer der letzten Tage (bis ca. 7 Tage alt). Sie sind ein Preissignal,
kein garantiert buchbares Angebot – daher `price_source = estimated`.

Geliefert werden je Treffer: Preis (pro Person), Airline, Flugnummer (Hinflug), Abflug/Rückflug mit Zeitzone,
Anzahl Umstiege, Dauern und ein Aviasales-Suchlink. NICHT geliefert: Flugzeugtyp, Umsteigeflughäfen,
Rückflugnummer. Fehlende Angaben werden als unbekannt markiert, nie ergänzt.
"""

from __future__ import annotations

import asyncio
import logging
from datetime import UTC, date, datetime, timedelta

import httpx

from app.models.schemas import (
    AircraftAssignmentStatus,
    BookingOption,
    Itinerary,
    PriceBreakdown,
    PriceSource,
    ProviderStatus,
    Segment,
)
from app.providers.base import FlightProvider, ProviderError, RoundTripQuery, WindowQuery
from app.services.itinerary_builder import InvalidItinerary, booking_options_for, build_itinerary
from app.services.reference import ReferenceData

log = logging.getLogger(__name__)


def month_pairs(earliest: date, latest: date, min_stay: int) -> list[tuple[str, str]]:
    """(Abflugmonat, Rückflugmonat)-Paare, die das Fenster abdecken, z. B. 18.12.–06.01. → 3 Paare."""

    def months(a: date, b: date) -> list[date]:
        out, cur = [], date(a.year, a.month, 1)
        while cur <= b:
            out.append(cur)
            cur = date(cur.year + (cur.month == 12), cur.month % 12 + 1, 1)
        return out

    last_departure = latest - timedelta(days=min_stay)
    first_return = earliest + timedelta(days=min_stay)
    pairs = []
    for dm in months(earliest, max(earliest, last_departure)):
        for rm in months(max(first_return, dm), latest):
            pairs.append((dm.strftime("%Y-%m"), rm.strftime("%Y-%m")))
    return pairs


def _parse(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


class TravelpayoutsProvider(FlightProvider):
    name = "travelpayouts"
    supports_window = True

    def __init__(self, token: str, ref: ReferenceData, *, marker: str | None = None,
                 base_url: str = "https://api.travelpayouts.com", timeout: float = 20.0, max_retries: int = 2,
                 transport: httpx.AsyncBaseTransport | None = None) -> None:
        self._ref = ref
        self._marker = marker
        self._max_retries = max_retries
        self._client = httpx.AsyncClient(
            base_url=base_url, timeout=httpx.Timeout(timeout, connect=10.0), transport=transport,
            headers={"X-Access-Token": token, "Accept": "application/json", "Accept-Encoding": "gzip, deflate"})

    def status(self) -> ProviderStatus:
        return ProviderStatus(
            name="travelpayouts", configured=True, mode="estimated",
            message="Kostenlose Preisdaten (Aviasales): zwischengespeicherte Preise aus Suchen der letzten Tage – "
                    "Richtwerte, nicht garantiert buchbar. Flugzeugtypen werden nicht übermittelt.")

    async def aclose(self) -> None:
        await self._client.aclose()

    async def _get(self, params: dict) -> list[dict]:
        attempt = 0
        while True:
            try:
                resp = await self._client.get("/aviasales/v3/prices_for_dates", params=params)
            except httpx.HTTPError as exc:
                err = ProviderError("Netzwerkfehler bei Travelpayouts.", retryable=True)
                err.__cause__ = exc
            else:
                if resp.status_code == 200:
                    try:
                        body = resp.json()
                    except ValueError as exc:
                        raise ProviderError("Ungültige Antwort von Travelpayouts.") from exc
                    if not isinstance(body, dict) or not body.get("success", False):
                        raise ProviderError(f"Travelpayouts meldet einen Fehler: {body.get('error') or 'unbekannt'}"
                                            if isinstance(body, dict) else "Unerwartete Antwort von Travelpayouts.")
                    data = body.get("data") or []
                    return data if isinstance(data, list) else []
                if resp.status_code in (401, 403):
                    raise ProviderError("Travelpayouts-Zugang abgelehnt – TRAVELPAYOUTS_TOKEN prüfen.")
                err = ProviderError(f"Travelpayouts antwortete mit HTTP {resp.status_code}.",
                                    retryable=resp.status_code == 429 or resp.status_code >= 500)
            if not err.retryable or attempt >= self._max_retries:
                raise err
            attempt += 1
            await asyncio.sleep(0.5 * (3 ** (attempt - 1)))

    async def search_window(self, query: WindowQuery) -> tuple[list[Itinerary], int]:
        pairs = month_pairs(query.earliest_departure, query.latest_return, query.min_stay)
        fetched = datetime.now(UTC)
        results: list[Itinerary] = []
        for dep_month, ret_month in pairs:
            params = {"origin": query.origin, "destination": query.destination, "departure_at": dep_month,
                      "return_at": ret_month, "one_way": "false", "currency": query.currency.lower(),
                      "sorting": "price", "limit": 200, "unique": "false"}
            if query.max_connections == 0:
                params["direct"] = "true"
            for raw in await self._get(params):
                try:
                    itin = self.parse_ticket(raw, query, fetched)
                except (InvalidItinerary, KeyError, TypeError, ValueError) as exc:
                    log.info("Travelpayouts-Eintrag verworfen: %s", exc)
                    continue
                if itin:
                    results.append(itin)
        return results, len(pairs)

    async def search_round_trip(self, query: RoundTripQuery) -> list[Itinerary]:
        window = WindowQuery(query.origin, query.destination, query.departure_date, query.return_date,
                             (query.return_date - query.departure_date).days,
                             (query.return_date - query.departure_date).days, query.adults, query.max_connections,
                             query.currency)
        items, _ = await self.search_window(window)
        return [i for i in items if i.outbound_date == query.departure_date and i.return_date == query.return_date]

    def _segment(self, carrier: str, number: str, origin: str, dest: str, departure: datetime,
                 minutes: int) -> Segment:
        dep_utc = departure.astimezone(UTC) if departure.tzinfo else self._ref.to_utc(departure, origin)
        dep_local = departure.replace(tzinfo=None)
        arr_utc = dep_utc + timedelta(minutes=minutes) if dep_utc else None
        zone = self._ref.timezone_for(dest)
        if arr_utc and zone:
            arr_local = arr_utc.astimezone(zone).replace(tzinfo=None)
        else:
            raise InvalidItinerary(f"Zeitzone für {dest} unbekannt")
        airline = self._ref.airlines.get(carrier)
        return Segment(marketing_carrier=carrier, marketing_carrier_name=airline.name if airline else None,
                       flight_number=number, origin=origin, destination=dest, departure_local=dep_local,
                       arrival_local=arr_local, departure_utc=dep_utc, arrival_utc=arr_utc, duration_minutes=minutes,
                       aircraft_status=AircraftAssignmentStatus.unknown)

    def parse_ticket(self, raw: dict, query: WindowQuery, fetched: datetime) -> Itinerary | None:
        price = float(raw["price"])
        if price <= 0 or not raw.get("return_at"):
            return None
        origin = (raw.get("origin_airport") or raw.get("origin") or query.origin).upper()
        dest = (raw.get("destination_airport") or raw.get("destination") or query.destination).upper()
        departure = _parse(raw["departure_at"])
        returning = _parse(raw["return_at"])
        if not (query.earliest_departure <= departure.date() and returning.date() <= query.latest_return):
            return None
        out_stops = int(raw.get("transfers") or 0)
        back_stops = int(raw.get("return_transfers") or 0)
        total = int(raw.get("duration") or 0)
        to_min = int(raw.get("duration_to") or 0) or (total - int(raw.get("duration_back") or 0))
        back_min = int(raw.get("duration_back") or 0) or (total - to_min)
        if to_min <= 0 or back_min <= 0:
            raise InvalidItinerary("Reisedauer fehlt")
        carrier = (raw.get("airline") or "??").upper()
        # Flugnummer nur übernehmen, wenn sie eindeutig den (Nonstop-)Hinflug beschreibt.
        number = str(raw.get("flight_number") or "") if out_stops == 0 else ""
        outbound = [self._segment(carrier, number, origin, dest, departure, to_min)]
        inbound = [self._segment(carrier, "", dest, origin, returning, back_min)]

        total_price = round(price * query.adults, 2)
        breakdown = PriceBreakdown(total=total_price, currency=query.currency.upper(), per_traveler=price,
                                   travelers=query.adults,
                                   baggage_note="Gepäckregeln nicht übermittelt – beim Anbieter prüfen.")
        options = []
        link = raw.get("link")
        if isinstance(link, str) and link.startswith("/"):
            url = f"https://www.aviasales.com{link}"
            if self._marker:
                url += ("&" if "?" in url else "?") + f"marker={self._marker}"
            options.append(BookingOption(kind="meta_search", label="Bei Aviasales prüfen (Preis kann abweichen)",
                                         url=url, is_deep_link=False))
        options += booking_options_for(self._ref, [carrier])
        notes = ["Geschätzter Preis aus Aviasales-Suchen der letzten Tage (bis ca. 7 Tage alt) – nicht garantiert.",
                 "Flugzeugtyp wird von dieser Quelle nicht übermittelt."]
        if query.adults > 1:
            notes.append("Gesamtpreis = Preis pro Person × Anzahl Reisende.")
        return build_itinerary(self._ref, provider=self.name, price_source=PriceSource.estimated, outbound=outbound,
                               inbound=inbound, price=breakdown, fetched_at=fetched, notes=notes,
                               booking_options=options, outbound_stops=out_stops, inbound_stops=back_stops)
