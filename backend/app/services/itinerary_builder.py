"""Baut aus Provider-Segmenten ein vollständiges Itinerary inkl. Zeitzonen, Umstiegsprüfung und Aufenthaltstagen."""

from __future__ import annotations

import hashlib
from datetime import datetime

from app.models.schemas import (
    BookingOption,
    Connection,
    Itinerary,
    Leg,
    PriceBreakdown,
    PriceSource,
    Segment,
    Ticketing,
)
from app.services.reference import ReferenceData

LONG_CONNECTION_MIN = 6 * 60
SEPARATE_TICKET_BUFFER_MIN = 120
AIRPORT_CHANGE_MCT_MIN = 180


class InvalidItinerary(ValueError):
    pass


def _minutes(a: datetime, b: datetime) -> int:
    return int(round((b - a).total_seconds() / 60))


def build_connection(ref: ReferenceData, prev: Segment, nxt: Segment, ticketing: Ticketing) -> Connection:
    if prev.arrival_utc and nxt.departure_utc:
        minutes = _minutes(prev.arrival_utc, nxt.departure_utc)
    else:  # gleiche Stadt/Zeitzone angenommen, nur wenn UTC unbekannt
        minutes = _minutes(prev.arrival_local, nxt.departure_local)

    airport_change = prev.destination != nxt.origin
    mct = ref.mct(prev.destination)
    note = None
    if airport_change:
        mct = max(mct, AIRPORT_CHANGE_MCT_MIN)
        note = f"Flughafenwechsel {prev.destination} → {nxt.origin} (eigener Transfer nötig)"

    required = mct + (SEPARATE_TICKET_BUFFER_MIN if ticketing == Ticketing.separate_tickets else 0)
    overnight = minutes >= 5 * 60 and (
        nxt.departure_local.date() > prev.arrival_local.date() or prev.arrival_local.hour >= 22)

    if minutes < 0:
        risk, note = "invalid", "Anschlussflug startet vor der Ankunft"
    elif ticketing == Ticketing.separate_tickets and minutes < required:
        risk = "invalid"
        note = f"Getrennte Tickets: {minutes} min Umstieg < empfohlene {required} min"
    elif minutes < int(mct * 0.6):
        risk = "invalid"
        note = f"Umstieg {minutes} min deutlich unter geschätzter Mindestumsteigezeit {mct} min"
    elif minutes < mct:
        risk = "tight"
        note = note or f"Knapper Umstieg: {minutes} min (geschätzte MCT {mct} min)"
    elif minutes > LONG_CONNECTION_MIN:
        risk = "long"
        note = note or f"Langer Aufenthalt am Umsteigeflughafen ({minutes // 60} h {minutes % 60} min)"
    else:
        risk = "ok"

    return Connection(airport=prev.destination, next_airport=nxt.origin, minutes=minutes,
                      min_connection_minutes=mct, airport_change=airport_change, overnight=overnight,
                      risk=risk, note=note)


def build_leg(ref: ReferenceData, direction: str, segments: list[Segment], ticketing: Ticketing) -> Leg:
    if not segments:
        raise InvalidItinerary("Leere Teilstrecke")
    connections = [build_connection(ref, a, b, ticketing) for a, b in zip(segments, segments[1:], strict=False)]
    first, last = segments[0], segments[-1]
    if first.departure_utc and last.arrival_utc:
        duration = _minutes(first.departure_utc, last.arrival_utc)
    else:
        duration = sum(s.duration_minutes for s in segments) + sum(max(0, c.minutes) for c in connections)
    if duration <= 0:
        raise InvalidItinerary("Unplausible Reisedauer (≤ 0)")

    direct = ref.distance_km(first.origin, last.destination)
    flown = [ref.distance_km(s.origin, s.destination) for s in segments]
    detour = None
    if direct and all(d is not None for d in flown) and direct > 0:
        detour = round(sum(flown) / direct, 2)  # type: ignore[arg-type]
    return Leg(direction=direction, segments=segments, connections=connections,
               duration_minutes=duration, detour_ratio=detour)


def itinerary_id(provider: str, legs: list[list[Segment]], total: float) -> str:
    key = provider + "|" + "|".join(
        f"{s.marketing_carrier}{s.flight_number}@{s.departure_local.isoformat()}" for leg in legs for s in leg)
    return hashlib.sha1(f"{key}|{total:.2f}".encode()).hexdigest()[:16]


def booking_options_for(ref: ReferenceData, carriers: list[str]) -> list[BookingOption]:
    """Nur verifizierte, offizielle Airline-Startseiten. Keine erfundenen Deep-Links."""
    options: list[BookingOption] = []
    for code in carriers:
        airline = ref.airlines.get(code)
        if airline and airline.website and airline.website.startswith("https://"):
            options.append(BookingOption(kind="airline_website",
                                         label=f"{airline.name} – Website (Flug dort manuell suchen)",
                                         url=airline.website, is_deep_link=False))
    return options


def build_itinerary(
    ref: ReferenceData,
    *,
    provider: str,
    price_source: PriceSource,
    outbound: list[Segment],
    inbound: list[Segment],
    price: PriceBreakdown,
    fetched_at: datetime,
    ticketing: Ticketing = Ticketing.single_ticket,
    offer_expires_at: datetime | None = None,
    notes: list[str] | None = None,
    booking_options: list[BookingOption] | None = None,
) -> Itinerary:
    out_leg = build_leg(ref, "outbound", outbound, ticketing)
    in_leg = build_leg(ref, "inbound", inbound, ticketing)

    arrival = outbound[-1]
    departure_back = inbound[0]
    if arrival.arrival_utc and departure_back.departure_utc:
        if departure_back.departure_utc <= arrival.arrival_utc:
            raise InvalidItinerary("Rückflug startet vor der Ankunft am Ziel")
    nights = (departure_back.departure_local.date() - arrival.arrival_local.date()).days
    if nights < 0:
        raise InvalidItinerary("Rückflug liegt vor der Ankunft")
    full_days = max(0, nights - 1)

    carriers: list[str] = []
    aircraft: list[str] = []
    for seg in outbound + inbound:
        if seg.marketing_carrier not in carriers:
            carriers.append(seg.marketing_carrier)
        if seg.aircraft_type_id and seg.aircraft_type_id not in aircraft:
            aircraft.append(seg.aircraft_type_id)

    data_notes = list(notes or [])
    if any(s.aircraft_type_id is None for s in outbound + inbound):
        data_notes.append("Für mindestens ein Segment ist kein Flugzeugtyp bekannt.")
    if any(s.departure_utc is None for s in outbound + inbound):
        data_notes.append("Zeitzone mindestens eines Flughafens unbekannt – Dauern sind Näherungen.")

    return Itinerary(
        id=itinerary_id(provider, [outbound, inbound], price.total),
        provider=provider,
        price_source=price_source,
        is_demo=price_source == PriceSource.demo,
        ticketing=ticketing,
        origin=outbound[0].origin,
        destination=outbound[-1].destination,
        legs=[out_leg, in_leg],
        price=price,
        booking_options=booking_options if booking_options is not None else booking_options_for(ref, carriers),
        outbound_date=outbound[0].departure_local.date(),
        return_date=inbound[0].departure_local.date(),
        nights=nights,
        full_stay_days=full_days,
        total_travel_minutes=out_leg.duration_minutes + in_leg.duration_minutes,
        stops=out_leg.stops + in_leg.stops,
        airlines=carriers,
        aircraft_type_ids=aircraft,
        fetched_at=fetched_at,
        offer_expires_at=offer_expires_at,
        data_notes=data_notes,
    )
