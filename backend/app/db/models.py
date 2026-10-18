"""SQLAlchemy-Datenmodell. Schemaänderungen immer über Alembic-Migrationen (backend/alembic/versions)."""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import JSON, DateTime, Float, ForeignKey, Integer, String, Text
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship


class Base(DeclarativeBase):
    pass


class AirportRow(Base):
    __tablename__ = "airports"
    iata: Mapped[str] = mapped_column(String(3), primary_key=True)
    icao: Mapped[str | None] = mapped_column(String(4), nullable=True)
    name: Mapped[str] = mapped_column(String(120))
    city: Mapped[str] = mapped_column(String(120))
    country: Mapped[str] = mapped_column(String(2))
    tz: Mapped[str] = mapped_column(String(64))
    lat: Mapped[float] = mapped_column(Float)
    lon: Mapped[float] = mapped_column(Float)
    mct: Mapped[int] = mapped_column(Integer)


class AirlineRow(Base):
    __tablename__ = "airlines"
    iata: Mapped[str] = mapped_column(String(2), primary_key=True)
    icao: Mapped[str | None] = mapped_column(String(3), nullable=True)
    name: Mapped[str] = mapped_column(String(120))
    country: Mapped[str] = mapped_column(String(2))
    website: Mapped[str | None] = mapped_column(String(255), nullable=True)
    alliance: Mapped[str | None] = mapped_column(String(40), nullable=True)


class AircraftTypeRow(Base):
    __tablename__ = "aircraft_types"
    id: Mapped[str] = mapped_column(String(8), primary_key=True)
    manufacturer: Mapped[str] = mapped_column(String(80))
    model: Mapped[str] = mapped_column(String(80))
    family: Mapped[str] = mapped_column(String(40))
    status: Mapped[str] = mapped_column(String(16))
    default_coolness: Mapped[float] = mapped_column(Float)
    data: Mapped[dict] = mapped_column(JSON)


class SearchRequestRow(Base):
    __tablename__ = "search_requests"
    id: Mapped[str] = mapped_column(String(32), primary_key=True)
    request_hash: Mapped[str] = mapped_column(String(64), index=True)
    request_json: Mapped[dict] = mapped_column(JSON)
    provider: Mapped[str] = mapped_column(String(32))
    provider_mode: Mapped[str] = mapped_column(String(16))
    status: Mapped[str] = mapped_column(String(32))
    stats_json: Mapped[dict] = mapped_column(JSON)
    warnings_json: Mapped[list] = mapped_column(JSON)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), index=True)
    results: Mapped[list[SearchResultRow]] = relationship(back_populates="search", cascade="all, delete-orphan")


class SearchResultRow(Base):
    """Rohes, unbewertetes Itinerary (Flüge + Segmente als JSON). Bewertung erfolgt bei jeder Abfrage neu."""

    __tablename__ = "search_results"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    search_id: Mapped[str] = mapped_column(ForeignKey("search_requests.id", ondelete="CASCADE"), index=True)
    itinerary_id: Mapped[str] = mapped_column(String(32))
    price_total: Mapped[float] = mapped_column(Float)
    currency: Mapped[str] = mapped_column(String(3))
    itinerary_json: Mapped[dict] = mapped_column(JSON)
    search: Mapped[SearchRequestRow] = relationship(back_populates="results")


class ReleaseInformationRow(Base):
    __tablename__ = "release_information"
    tag: Mapped[str] = mapped_column(String(40), primary_key=True)
    version: Mapped[str] = mapped_column(String(40))
    published_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
    prerelease: Mapped[int] = mapped_column(Integer, default=0)
    apk_url: Mapped[str | None] = mapped_column(Text, nullable=True)
    sha256: Mapped[str | None] = mapped_column(String(64), nullable=True)
    fetched_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))


class OpenSkyDepartureDayRow(Base):
    """Cache: alle von OpenSky beobachteten Abflüge eines Flughafens an einem UTC-Tag ({callsign: [icao24]})."""

    __tablename__ = "opensky_departure_days"
    airport_icao: Mapped[str] = mapped_column(String(4), primary_key=True)
    day: Mapped[str] = mapped_column(String(10), primary_key=True)
    callsigns: Mapped[dict] = mapped_column(JSON)
    fetched_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))


class AircraftRegistryRow(Base):
    """Cache: Flugzeugtyp je Transponder-Adresse (hexdb.io). type_code NULL = unbekannt."""

    __tablename__ = "aircraft_registry"
    icao24: Mapped[str] = mapped_column(String(6), primary_key=True)
    type_code: Mapped[str | None] = mapped_column(String(8), nullable=True)
    registration: Mapped[str | None] = mapped_column(String(16), nullable=True)
    description: Mapped[str | None] = mapped_column(String(120), nullable=True)
    fetched_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))
