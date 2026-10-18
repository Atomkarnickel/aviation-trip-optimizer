"""Zentrale Konfiguration. Alle Geheimnisse kommen ausschließlich aus Umgebungsvariablen."""

from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict

BACKEND_DIR = Path(__file__).resolve().parents[2]
REPO_ROOT = BACKEND_DIR.parent


def _read_version() -> str:
    for candidate in (REPO_ROOT / "VERSION", BACKEND_DIR / "VERSION"):
        if candidate.is_file():
            return candidate.read_text(encoding="utf-8").strip()
    return "0.0.0"


def _read_release_config() -> dict:
    for candidate in (REPO_ROOT / "shared" / "release-config.json", BACKEND_DIR / "release-config.json"):
        if candidate.is_file():
            return json.loads(candidate.read_text(encoding="utf-8"))
    return {}


RELEASE_CONFIG = _read_release_config()


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    app_name: str = RELEASE_CONFIG.get("appName", "Aviation Trip Optimizer")
    app_version: str = Field(default_factory=_read_version)
    environment: str = "development"

    database_url: str = f"sqlite:///{(BACKEND_DIR / 'dev.db').as_posix()}"

    # Flugdaten-Provider. "auto" wählt Duffel, wenn ein Token gesetzt ist, sonst Demo (nur wenn DEMO_MODE=true).
    flight_provider: str = "auto"
    duffel_access_token: str | None = None
    duffel_base_url: str = "https://api.duffel.com"
    demo_mode: bool = False

    provider_timeout_seconds: float = 25.0
    provider_max_retries: int = 2
    provider_concurrency: int = 4
    max_provider_calls_per_search: int = 24
    max_offers_per_call: int = 40
    search_cache_ttl_seconds: int = 900

    github_repository: str = RELEASE_CONFIG.get("repository", "")
    github_token: str | None = None
    release_cache_seconds: int = int(RELEASE_CONFIG.get("releaseCacheSeconds", 900))

    cors_origins: str = "http://localhost:3000,https://atomkarnickel.github.io"
    search_rate_limit_per_minute: int = 20

    @property
    def cors_origin_list(self) -> list[str]:
        return [o.strip() for o in self.cors_origins.split(",") if o.strip()]


@lru_cache
def get_settings() -> Settings:
    return Settings()
