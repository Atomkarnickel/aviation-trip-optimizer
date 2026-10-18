from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.api.routes import router
from app.core.config import Settings, get_settings
from app.core.ratelimit import SlidingWindowLimiter
from app.db.session import make_engine, make_session_factory, run_migrations, seed_reference
from app.providers.registry import create_provider
from app.services.aircraft_history import AircraftHistoryService
from app.services.reference import get_reference
from app.services.releases import ReleaseService
from app.services.search import SearchService

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("app")


def create_app(settings: Settings | None = None, provider=None, history_transport=None) -> FastAPI:
    settings = settings or get_settings()
    ref = get_reference()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        engine = make_engine(settings.database_url)
        run_migrations(engine)
        session_factory = make_session_factory(engine)
        with session_factory() as session:
            seed_reference(session, ref)
        active_provider = provider or create_provider(settings, ref)
        app.state.settings = settings
        app.state.ref = ref
        app.state.provider = active_provider
        app.state.search_service = SearchService(settings, ref, active_provider, session_factory)
        app.state.release_service = ReleaseService(settings.github_repository, settings.github_token,
                                                   settings.release_cache_seconds)
        app.state.limiter = SlidingWindowLimiter(settings.search_rate_limit_per_minute)
        app.state.history_limiter = SlidingWindowLimiter(settings.aircraft_history_rate_limit_per_minute)
        app.state.history_service = AircraftHistoryService(settings, ref, session_factory, history_transport)
        status = active_provider.status()
        log.info("Backend %s gestartet – Provider %s (%s)", settings.app_version, status.name, status.mode)
        yield
        await active_provider.aclose()
        engine.dispose()

    app = FastAPI(title=settings.app_name, version=settings.app_version, lifespan=lifespan,
                  description="Backend des Aviation Trip Optimizer: Flugsuche, Bewertung, Flugzeugkatalog, "
                              "Release-Metadaten.")
    app.add_middleware(CORSMiddleware, allow_origins=settings.cors_origin_list, allow_methods=["GET", "POST"],
                       allow_headers=["Content-Type"], max_age=600)

    @app.exception_handler(RequestValidationError)
    async def validation_handler(_: Request, exc: RequestValidationError):
        errors = [{"field": ".".join(str(p) for p in e["loc"][1:]), "message": e["msg"].removeprefix("Value error, ")}
                  for e in exc.errors()]
        return JSONResponse(status_code=422, content={"detail": "Ungültige Eingabe", "errors": errors})

    app.include_router(router)
    return app


app = create_app()
