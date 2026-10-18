from __future__ import annotations

from pathlib import Path

from sqlalchemy import create_engine
from sqlalchemy.engine import Engine
from sqlalchemy.orm import Session, sessionmaker

from app.db.models import AircraftTypeRow, AirlineRow, AirportRow
from app.services.reference import ReferenceData

BACKEND_DIR = Path(__file__).resolve().parents[2]


def make_engine(url: str) -> Engine:
    if url.startswith("postgres://"):  # Heroku/Render-Stil normalisieren
        url = "postgresql+psycopg://" + url[len("postgres://"):]
    elif url.startswith("postgresql://"):
        url = "postgresql+psycopg://" + url[len("postgresql://"):]
    kwargs = {"pool_pre_ping": True}
    if url.startswith("sqlite"):
        kwargs["connect_args"] = {"check_same_thread": False}
    return create_engine(url, **kwargs)


def make_session_factory(engine: Engine) -> sessionmaker[Session]:
    return sessionmaker(bind=engine, expire_on_commit=False)


def run_migrations(engine: Engine) -> None:
    from alembic import command
    from alembic.config import Config

    cfg = Config(str(BACKEND_DIR / "alembic.ini"))
    cfg.set_main_option("script_location", str(BACKEND_DIR / "alembic"))
    with engine.begin() as conn:
        cfg.attributes["connection"] = conn
        command.upgrade(cfg, "head")


def seed_reference(session: Session, ref: ReferenceData) -> None:
    """Idempotentes Upsert der kuratierten Referenzdaten."""
    for ap in ref.airports.values():
        session.merge(AirportRow(**ap.model_dump()))
    for al in ref.airlines.values():
        session.merge(AirlineRow(**al.model_dump()))
    for at in ref.aircraft.values():
        session.merge(AircraftTypeRow(id=at.id, manufacturer=at.manufacturer, model=at.model, family=at.family,
                                      status=at.status, default_coolness=at.default_coolness,
                                      data=at.model_dump()))
    session.commit()
