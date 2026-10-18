"""Auswahl des aktiven Flugdaten-Providers anhand der Konfiguration."""

from __future__ import annotations

from app.core.config import Settings
from app.providers.base import FlightProvider, UnconfiguredProvider
from app.providers.demo import DemoProvider
from app.providers.duffel import DuffelProvider
from app.providers.travelpayouts import TravelpayoutsProvider
from app.services.reference import ReferenceData


def create_provider(settings: Settings, ref: ReferenceData) -> FlightProvider:
    choice = settings.flight_provider.lower()
    if choice in ("auto", "duffel") and settings.duffel_access_token:
        return DuffelProvider(settings.duffel_access_token, ref, base_url=settings.duffel_base_url,
                              timeout=settings.provider_timeout_seconds,
                              max_retries=settings.provider_max_retries,
                              max_offers=settings.max_offers_per_call)
    if choice in ("auto", "travelpayouts") and settings.travelpayouts_token:
        return TravelpayoutsProvider(settings.travelpayouts_token, ref, marker=settings.travelpayouts_marker,
                                     base_url=settings.travelpayouts_base_url,
                                     timeout=settings.provider_timeout_seconds,
                                     max_retries=settings.provider_max_retries)
    # Demo nur bei ausdrücklicher Aktivierung – nie als stiller Fallback für fehlende Live-Daten.
    if settings.demo_mode and choice in ("auto", "demo"):
        return DemoProvider(ref)
    return UnconfiguredProvider()
