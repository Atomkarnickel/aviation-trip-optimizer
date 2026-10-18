from __future__ import annotations

from fastapi import APIRouter, HTTPException, Query, Request
from fastapi.concurrency import run_in_threadpool

from app.models.schemas import (
    AircraftType,
    Airline,
    Airport,
    CompareRequest,
    CompareResponse,
    MetaResponse,
    ReleaseStatus,
    RescoreRequest,
    SearchRequest,
    SearchResponse,
    Weights,
)
from app.services.scoring import MODE_WEIGHTS
from app.services.search import SearchNotFound

router = APIRouter(prefix="/api/v1")


def _state(request: Request):
    return request.app.state


@router.get("/health", tags=["system"])
def health() -> dict:
    return {"status": "ok"}


@router.get("/meta", response_model=MetaResponse, tags=["system"])
def meta(request: Request) -> MetaResponse:
    st = _state(request)
    return MetaResponse(app_name=st.settings.app_name, backend_version=st.settings.app_version,
                        environment=st.settings.environment, provider=st.provider.status(),
                        repository=st.settings.github_repository, data_sources=st.ref.sources)


@router.get("/modes", response_model=dict[str, Weights], tags=["scoring"])
def modes() -> dict[str, Weights]:
    """Voreingestellte Gewichtungen der Optimierungsmodi."""
    return {m.value: w for m, w in MODE_WEIGHTS.items()}


@router.get("/aircraft", response_model=list[AircraftType], tags=["reference"])
def aircraft(request: Request) -> list[AircraftType]:
    return sorted(_state(request).ref.aircraft.values(), key=lambda a: (-a.default_coolness, a.id))


@router.get("/aircraft/{type_id}", response_model=AircraftType, tags=["reference"])
def aircraft_detail(type_id: str, request: Request) -> AircraftType:
    at = _state(request).ref.aircraft.get(type_id.upper())
    if not at:
        raise HTTPException(404, "Flugzeugtyp nicht gefunden")
    return at


@router.get("/airports", response_model=list[Airport], tags=["reference"])
def airports(request: Request, q: str = Query("", max_length=60), limit: int = Query(20, ge=1, le=200)):
    return _state(request).ref.search_airports(q, limit)


@router.get("/airlines", response_model=list[Airline], tags=["reference"])
def airlines(request: Request) -> list[Airline]:
    return sorted(_state(request).ref.airlines.values(), key=lambda a: a.name)


@router.post("/search", response_model=SearchResponse, tags=["search"])
async def search(body: SearchRequest, request: Request) -> SearchResponse:
    st = _state(request)
    client = request.client.host if request.client else "unknown"
    allowed, retry = st.limiter.allow(client)
    if not allowed:
        raise HTTPException(429, "Zu viele Suchanfragen. Bitte kurz warten.", headers={"Retry-After": str(retry)})
    return await st.search_service.search(body)


@router.get("/search/{search_id}", response_model=SearchResponse, tags=["search"])
async def get_search(search_id: str, request: Request) -> SearchResponse:
    try:
        return await run_in_threadpool(_state(request).search_service.get, search_id)
    except SearchNotFound as exc:
        raise HTTPException(404, str(exc)) from exc


@router.post("/search/{search_id}/rescore", response_model=SearchResponse, tags=["search"])
async def rescore(search_id: str, body: RescoreRequest, request: Request) -> SearchResponse:
    """Bewertet gespeicherte Ergebnisse mit neuen Präferenzen neu – ohne erneute Provider-Abfrage."""
    try:
        return await run_in_threadpool(_state(request).search_service.get, search_id, body.preferences)
    except SearchNotFound as exc:
        raise HTTPException(404, str(exc)) from exc


@router.post("/search/{search_id}/compare", response_model=CompareResponse, tags=["search"])
async def compare(search_id: str, body: CompareRequest, request: Request) -> CompareResponse:
    try:
        return await run_in_threadpool(_state(request).search_service.compare, search_id, body.itinerary_ids)
    except SearchNotFound as exc:
        raise HTTPException(404, str(exc)) from exc


@router.get("/releases", response_model=ReleaseStatus, tags=["releases"])
async def releases(request: Request, limit: int = Query(5, ge=1, le=20)) -> ReleaseStatus:
    return await run_in_threadpool(_state(request).release_service.status, limit)


@router.get("/releases/latest", response_model=ReleaseStatus, tags=["releases"])
async def latest_release(request: Request) -> ReleaseStatus:
    return await run_in_threadpool(_state(request).release_service.status, 1)
