"""Pydantic-Schemas der REST-API. Sie bilden die Grundlage der OpenAPI-Spezifikation (shared/openapi.json)."""

from __future__ import annotations

import re
from datetime import date, datetime
from enum import Enum

from pydantic import BaseModel, Field, field_validator, model_validator

IATA_AIRPORT = re.compile(r"^[A-Z]{3}$")
IATA_AIRLINE = re.compile(r"^[A-Z0-9]{2}$")


# --------------------------------------------------------------------------- Enums


class OptimizationMode(str, Enum):
    best_value = "best_value"
    aircraft_hunter = "aircraft_hunter"
    cheapest = "cheapest"


class PriceSource(str, Enum):
    live_offer = "live_offer"  # tatsächlich angebotener Preis eines Live-Providers
    test_offer = "test_offer"  # Sandbox/Testmodus eines echten Providers – kein echter Preis
    estimated = "estimated"  # Schätzung/historisch – nicht buchbar
    demo = "demo"  # synthetische Demodaten


class AircraftAssignmentStatus(str, Enum):
    confirmed = "confirmed"  # für den konkreten Flug kurzfristig bestätigt (z. B. Flugstatus-Provider)
    scheduled = "scheduled"  # laut Flugplan/Angebot geplant – kann sich ändern
    historical = "historical"  # nur historisch auf dieser Route beobachtet
    unknown = "unknown"


class AircraftPreferenceStatus(str, Enum):
    wishlist = "wishlist"
    flown = "flown"
    not_interested = "not_interested"
    neutral = "neutral"


class Ticketing(str, Enum):
    single_ticket = "single_ticket"
    separate_tickets = "separate_tickets"


class CabinClass(str, Enum):
    economy = "economy"
    premium_economy = "premium_economy"
    business = "business"
    first = "first"


# --------------------------------------------------------------------------- Referenzdaten


class AircraftType(BaseModel):
    id: str
    manufacturer: str
    model: str
    family: str
    iata_codes: list[str]
    icao_codes: list[str] = []
    body: str
    engines: int
    typical_seats: str
    typical_use: str
    status: str = Field(description="active | rare | historical")
    default_coolness: float = Field(ge=0, le=10)
    notes: str = ""
    bookable: bool = Field(default=True, description="False für historische Typen ohne regulären Linienbetrieb")


class Airport(BaseModel):
    iata: str
    icao: str | None = None
    name: str
    city: str
    country: str
    tz: str
    lat: float
    lon: float
    mct: int = Field(description="Geschätzte Mindestumsteigezeit in Minuten")


class Airline(BaseModel):
    iata: str
    icao: str | None = None
    name: str
    country: str
    website: str | None = None
    alliance: str | None = None


class ReferenceSource(BaseModel):
    source: str
    updated: str


# --------------------------------------------------------------------------- Präferenzen


class Weights(BaseModel):
    price: float = Field(5, ge=0, le=10)
    aircraft: float = Field(4, ge=0, le=10)
    travel_time: float = Field(3, ge=0, le=10)
    stops: float = Field(3, ge=0, le=10)
    stay: float = Field(3, ge=0, le=10)
    airline: float = Field(1, ge=0, le=10)
    comfort: float = Field(2, ge=0, le=10)

    def total(self) -> float:
        return sum(self.model_dump().values())


class AircraftPreference(BaseModel):
    aircraft_type_id: str = Field(min_length=2, max_length=8)
    status: AircraftPreferenceStatus = AircraftPreferenceStatus.neutral
    personal_rating: float | None = Field(default=None, ge=0, le=10)


class UserPreferences(BaseModel):
    mode: OptimizationMode = OptimizationMode.best_value
    weights: Weights | None = Field(default=None, description="Überschreibt die Gewichtung des Modus")
    aircraft_preferences: list[AircraftPreference] = Field(default_factory=list, max_length=100)
    preferred_airlines: list[str] = Field(default_factory=list, max_length=30)
    flown_factor: float = Field(0.5, ge=0, le=1, description="Multiplikator für bereits geflogene Typen")

    @field_validator("preferred_airlines")
    @classmethod
    def _airlines(cls, v: list[str]) -> list[str]:
        return _validate_codes(v, IATA_AIRLINE, "Airline")


class TimeWindow(BaseModel):
    earliest_hour: int = Field(0, ge=0, le=23)
    latest_hour: int = Field(23, ge=0, le=23)

    def contains(self, hour: int) -> bool:
        if self.earliest_hour <= self.latest_hour:
            return self.earliest_hour <= hour <= self.latest_hour
        return hour >= self.earliest_hour or hour <= self.latest_hour  # über Mitternacht


def _validate_codes(values: list[str], pattern: re.Pattern, label: str) -> list[str]:
    out: list[str] = []
    for raw in values:
        code = raw.strip().upper()
        if not pattern.match(code):
            raise ValueError(f"Ungültiger {label}-Code: {raw!r}")
        if code not in out:
            out.append(code)
    return out


# --------------------------------------------------------------------------- Suche


class SearchRequest(BaseModel):
    origins: list[str] = Field(min_length=1, max_length=5)
    destinations: list[str] = Field(default_factory=list, max_length=10,
                                    description="Leer = offene Zielsuche über Kandidatenliste")
    earliest_departure: date
    latest_return: date
    min_stay_days: int = Field(3, ge=1, le=60)
    max_stay_days: int = Field(14, ge=1, le=60)
    adults: int = Field(1, ge=1, le=9)
    cabin_class: CabinClass = CabinClass.economy
    currency: str = Field("EUR", pattern=r"^[A-Z]{3}$")
    max_budget: float | None = Field(default=None, gt=0, description="Gesamtbudget für alle Reisenden")
    desired_aircraft: list[str] = Field(default_factory=list, max_length=20)
    preferred_airlines: list[str] = Field(default_factory=list, max_length=30)
    excluded_airlines: list[str] = Field(default_factory=list, max_length=30)
    max_stops: int = Field(1, ge=0, le=3)
    max_travel_hours: float | None = Field(default=None, gt=0, le=60, description="Pro Richtung")
    outbound_departure_window: TimeWindow | None = None
    return_departure_window: TimeWindow | None = None
    require_checked_bag: bool = False
    max_date_combinations: int = Field(6, ge=1, le=20)
    preferences: UserPreferences = Field(default_factory=UserPreferences)

    @field_validator("origins", "destinations")
    @classmethod
    def _airports(cls, v: list[str]) -> list[str]:
        return _validate_codes(v, IATA_AIRPORT, "Flughafen")

    @field_validator("preferred_airlines", "excluded_airlines")
    @classmethod
    def _airlines(cls, v: list[str]) -> list[str]:
        return _validate_codes(v, IATA_AIRLINE, "Airline")

    @model_validator(mode="after")
    def _check(self) -> SearchRequest:
        if self.latest_return <= self.earliest_departure:
            raise ValueError("Der späteste Rückflug muss nach dem frühesten Abflug liegen.")
        if self.min_stay_days > self.max_stay_days:
            raise ValueError("Die minimale Reisedauer darf die maximale nicht übersteigen.")
        span = (self.latest_return - self.earliest_departure).days
        if span > 120:
            raise ValueError("Der Reisezeitraum darf höchstens 120 Tage umfassen.")
        if self.min_stay_days > span:
            raise ValueError("Die minimale Reisedauer passt nicht in den gewählten Zeitraum.")
        if set(self.origins) & set(self.destinations):
            raise ValueError("Abflughafen und Ziel dürfen nicht identisch sein.")
        return self


class Segment(BaseModel):
    marketing_carrier: str
    marketing_carrier_name: str | None = None
    operating_carrier: str | None = None
    operating_carrier_name: str | None = None
    flight_number: str
    origin: str
    destination: str
    departure_local: datetime
    arrival_local: datetime
    departure_utc: datetime | None = None
    arrival_utc: datetime | None = None
    duration_minutes: int
    aircraft_code: str | None = None
    aircraft_name: str | None = None
    aircraft_type_id: str | None = None
    aircraft_status: AircraftAssignmentStatus = AircraftAssignmentStatus.unknown
    checked_bags_included: int | None = None


class Connection(BaseModel):
    airport: str
    next_airport: str
    minutes: int
    min_connection_minutes: int
    airport_change: bool = False
    overnight: bool = False
    risk: str = Field(description="ok | tight | long | invalid")
    note: str | None = None


class Leg(BaseModel):
    direction: str = Field(description="outbound | inbound | onward")
    segments: list[Segment]
    connections: list[Connection] = []
    duration_minutes: int
    detour_ratio: float | None = None
    details_complete: bool = Field(
        default=True, description="False: Provider liefert nur Start/Ziel/Dauer – Umsteigeflughäfen unbekannt")
    reported_stops: int | None = Field(default=None, description="Umstiege laut Provider, falls Segmente fehlen")

    @property
    def stops(self) -> int:
        if self.reported_stops is not None:
            return self.reported_stops
        return max(0, len(self.segments) - 1)


class PriceBreakdown(BaseModel):
    total: float
    currency: str
    base: float | None = None
    taxes: float | None = None
    per_traveler: float
    travelers: int
    baggage_note: str | None = None


class BookingOption(BaseModel):
    kind: str = Field(description="airline_website | meta_search | provider_checkout")
    label: str
    url: str
    is_deep_link: bool = False


class Itinerary(BaseModel):
    id: str
    provider: str
    price_source: PriceSource
    is_demo: bool = False
    ticketing: Ticketing = Ticketing.single_ticket
    origin: str
    destination: str
    legs: list[Leg]
    price: PriceBreakdown
    booking_options: list[BookingOption] = []
    outbound_date: date
    return_date: date
    nights: int
    full_stay_days: int
    total_travel_minutes: int
    stops: int
    airlines: list[str]
    aircraft_type_ids: list[str]
    fetched_at: datetime
    offer_expires_at: datetime | None = None
    data_notes: list[str] = []


class ScoreComponent(BaseModel):
    key: str
    label: str
    score: float = Field(ge=0, le=1)
    weight: float
    contribution: float
    detail: str


class AircraftHighlight(BaseModel):
    aircraft_type_id: str
    name: str
    status: AircraftAssignmentStatus
    wishlist: bool
    segment: str


class Explanation(BaseModel):
    summary: str
    reasons: list[str]
    tradeoffs: list[str]
    uncertainties: list[str]
    better_value_alternative_id: str | None = None
    better_value_note: str | None = None


class ScoredItinerary(BaseModel):
    rank: int
    itinerary: Itinerary
    total_score: float
    components: list[ScoreComponent]
    aircraft_highlights: list[AircraftHighlight]
    wishlist_hits: list[str]
    extra_cost_vs_cheapest: float
    aircraft_premium: float | None = Field(
        default=None, description="Mehrpreis ggü. günstigster Option ohne Wunschflugzeug")
    explanation: Explanation


class ProviderStatus(BaseModel):
    name: str
    configured: bool
    mode: str = Field(description="live | test | demo | none")
    message: str


class SearchStats(BaseModel):
    combinations_total: int
    combinations_searched: int
    provider_calls: int
    provider_errors: int
    offers_received: int
    filtered_out: dict[str, int]


class CalendarCell(BaseModel):
    """Günstigster erhaltener Preis je Abflugdatum und Aufenthaltsdauer (nur aus tatsächlich erhaltenen Angeboten)."""

    departure_date: date
    nights: int
    min_price: float
    currency: str
    offers: int
    itinerary_id: str


class SearchResponse(BaseModel):
    search_id: str
    status: str = Field(description="ok | no_results | provider_not_configured | provider_error | partial")
    provider: ProviderStatus
    is_demo: bool
    generated_at: datetime
    cached: bool = False
    mode: OptimizationMode
    weights: Weights
    results: list[ScoredItinerary]
    cheapest_total: float | None = None
    stats: SearchStats
    calendar: list[CalendarCell] = []
    warnings: list[str] = []


class RescoreRequest(BaseModel):
    preferences: UserPreferences


class CompareRequest(BaseModel):
    itinerary_ids: list[str] = Field(min_length=2, max_length=4)


class CompareRow(BaseModel):
    label: str
    values: list[str]
    best_index: int | None = None


class CompareResponse(BaseModel):
    search_id: str
    itinerary_ids: list[str]
    rows: list[CompareRow]


# --------------------------------------------------------------------------- Flugzeugtyp-Verlauf


class AircraftObservation(BaseModel):
    date: date
    callsign: str
    icao24: str
    registration: str | None = None
    type_code: str | None = None
    aircraft_type_id: str | None = None
    aircraft_name: str | None = None


class AircraftTypeShare(BaseModel):
    aircraft_type_id: str | None
    name: str
    count: int
    share: float


class AircraftHistory(BaseModel):
    status: str = Field(description="ok | no_observations | not_supported | not_configured | unavailable")
    flight: str
    callsign: str | None = None
    origin: str
    days_checked: int
    observations: list[AircraftObservation] = []
    distribution: list[AircraftTypeShare] = []
    source: str = "OpenSky Network (beobachtete Flüge) + hexdb.io (Flugzeugtyp je Transponder)"
    message: str | None = None
    checked_at: datetime


# --------------------------------------------------------------------------- Releases


class ReleaseAsset(BaseModel):
    name: str
    url: str
    size: int | None = None


class ReleaseInfo(BaseModel):
    version: str
    tag: str
    name: str | None = None
    published_at: datetime
    prerelease: bool = False
    html_url: str
    notes: str = ""
    summary: str = ""
    apk: ReleaseAsset | None = None
    checksum: ReleaseAsset | None = None
    sha256: str | None = None


class ReleaseStatus(BaseModel):
    status: str = Field(description="ok | no_release | unavailable | not_configured")
    repository: str
    releases_url: str | None = None
    latest: ReleaseInfo | None = None
    releases: list[ReleaseInfo] = []
    checked_at: datetime
    message: str | None = None


class MetaResponse(BaseModel):
    app_name: str
    backend_version: str
    environment: str
    provider: ProviderStatus
    repository: str
    data_sources: list[ReferenceSource]
