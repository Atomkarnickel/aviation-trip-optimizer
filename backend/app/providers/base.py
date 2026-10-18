"""Provider-Abstraktion. Neue Datenquellen implementieren `FlightProvider` und werden in registry.py eingetragen."""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from datetime import date

from app.models.schemas import CabinClass, Itinerary, ProviderStatus


@dataclass(frozen=True)
class RoundTripQuery:
    origin: str
    destination: str
    departure_date: date
    return_date: date
    adults: int
    cabin_class: CabinClass
    max_connections: int
    currency: str


@dataclass(frozen=True)
class WindowQuery:
    """Suche über ein ganzes Reisefenster (für Provider, die Monatsdaten liefern)."""

    origin: str
    destination: str
    earliest_departure: date
    latest_return: date
    min_stay: int
    max_stay: int
    adults: int
    max_connections: int
    currency: str


class ProviderError(Exception):
    """Fehler eines Providers (Netzwerk, Limits, ungültige Antwort). Die Nachricht ist nutzertauglich."""

    def __init__(self, message: str, *, retryable: bool = False) -> None:
        super().__init__(message)
        self.retryable = retryable


class FlightProvider(ABC):
    name: str
    #: True, wenn der Provider ein ganzes Zeitfenster pro Route in wenigen Abfragen liefern kann.
    supports_window: bool = False

    @abstractmethod
    def status(self) -> ProviderStatus: ...

    @abstractmethod
    async def search_round_trip(self, query: RoundTripQuery) -> list[Itinerary]:
        """Liefert vollständige Hin-/Rückflug-Itineraries. Darf niemals Daten erfinden."""

    async def search_window(self, query: WindowQuery) -> tuple[list[Itinerary], int]:
        """Liefert (Itineraries, Anzahl Provider-Abfragen). Nur bei supports_window=True."""
        raise NotImplementedError

    async def aclose(self) -> None:  # pragma: no cover - optional
        return None


class UnconfiguredProvider(FlightProvider):
    """Platzhalter, wenn keine Datenquelle eingerichtet ist. Liefert bewusst keine Ergebnisse."""

    name = "none"

    def status(self) -> ProviderStatus:
        return ProviderStatus(
            name="none", configured=False, mode="none",
            message=("Kein Flugdaten-Provider konfiguriert. Setze TRAVELPAYOUTS_TOKEN (kostenlos) oder "
                     "DUFFEL_ACCESS_TOKEN im Backend (siehe docs/PROVIDERS.md). Es werden keine Ergebnisse "
                     "vorgetäuscht."),
        )

    async def search_round_trip(self, query: RoundTripQuery) -> list[Itinerary]:
        return []
