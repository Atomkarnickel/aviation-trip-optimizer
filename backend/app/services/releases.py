"""GitHub-Releases als gemeinsame Release-Datenquelle für Backend, README-Generator, Android und Web.

Regeln (identisch in Kotlin/TypeScript umgesetzt, siehe shared/RELEASE_METADATA.md):
* Nur veröffentlichte, nicht-Draft-Releases mit gültigem SemVer-Tag `vX.Y.Z`.
* "Stabil" = `prerelease == false` und keine Vorabkennung im Tag.
* APK-Asset heißt exakt `aviation-optimizer-<version>.apk`, Prüfsumme `<apk>.sha256`.
* Download-URLs müssen mit `https://github.com/<repo>/releases/download/` beginnen.
"""

from __future__ import annotations

import re
import time
from dataclasses import dataclass, field
from datetime import UTC, datetime

import httpx

from app.models.schemas import ReleaseAsset, ReleaseInfo, ReleaseStatus

SEMVER = re.compile(r"^v?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z.-]+))?$")
SHA256 = re.compile(r"\b([a-fA-F0-9]{64})\b")


def parse_version(value: str) -> tuple[int, int, int, str | None] | None:
    m = SEMVER.match((value or "").strip())
    if not m:
        return None
    return int(m[1]), int(m[2]), int(m[3]), m[4]


def _cmp_pre(a: str | None, b: str | None) -> int:
    if a == b:
        return 0
    if a is None:
        return 1  # Release > Vorabversion
    if b is None:
        return -1
    for x, y in zip(a.split("."), b.split("."), strict=False):
        if x == y:
            continue
        if x.isdigit() and y.isdigit():
            return 1 if int(x) > int(y) else -1
        if x.isdigit() != y.isdigit():
            return -1 if x.isdigit() else 1
        return 1 if x > y else -1
    return (len(a.split(".")) > len(b.split("."))) - (len(a.split(".")) < len(b.split(".")))


def compare_versions(a: str, b: str) -> int:
    pa, pb = parse_version(a), parse_version(b)
    if pa is None or pb is None:
        raise ValueError(f"Ungültige Version: {a if pa is None else b!r}")
    if pa[:3] != pb[:3]:
        return 1 if pa[:3] > pb[:3] else -1
    return _cmp_pre(pa[3], pb[3])


def version_code(version: str) -> int:
    """Android versionCode: MAJOR*10000 + MINOR*100 + PATCH (MINOR/PATCH < 100)."""
    p = parse_version(version)
    if p is None or p[3] is not None:
        raise ValueError(f"Nur stabile SemVer-Versionen erlaubt: {version!r}")
    major, minor, patch, _ = p
    if minor > 99 or patch > 99:
        raise ValueError("MINOR und PATCH müssen < 100 sein")
    return major * 10000 + minor * 100 + patch


def summarize_notes(body: str, limit: int = 160) -> str:
    for line in (body or "").splitlines():
        text = line.strip().lstrip("#*->").strip()
        if text and not text.lower().startswith(("what's changed", "full changelog", "sha-256", "sha256")):
            return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"
    return ""


def parse_release(raw: dict, repository: str, apk_prefix: str = "aviation-optimizer-") -> ReleaseInfo | None:
    if not isinstance(raw, dict) or raw.get("draft"):
        return None
    tag = raw.get("tag_name") or ""
    parsed = parse_version(tag)
    if not parsed or not tag.startswith("v"):
        return None
    version = tag[1:]
    published = raw.get("published_at")
    if not published:
        return None
    allowed = f"https://github.com/{repository}/releases/download/"
    apk = checksum = None
    for asset in raw.get("assets") or []:
        if not isinstance(asset, dict):
            continue
        url = asset.get("browser_download_url") or ""
        if not url.startswith(allowed):
            continue
        name = asset.get("name")
        if name == f"{apk_prefix}{version}.apk":
            apk = ReleaseAsset(name=name, url=url, size=asset.get("size"))
        elif name == f"{apk_prefix}{version}.apk.sha256":
            checksum = ReleaseAsset(name=name, url=url, size=asset.get("size"))
    html_url = raw.get("html_url") or f"https://github.com/{repository}/releases/tag/{tag}"
    if not html_url.startswith("https://github.com/"):
        return None
    body = raw.get("body") or ""
    return ReleaseInfo(
        version=version, tag=tag, name=raw.get("name"),
        published_at=datetime.fromisoformat(published.replace("Z", "+00:00")),
        prerelease=bool(raw.get("prerelease")) or parsed[3] is not None,
        html_url=html_url, notes=body[:4000], summary=summarize_notes(body), apk=apk, checksum=checksum)


def stable_releases_with_apk(releases: list[ReleaseInfo]) -> list[ReleaseInfo]:
    stable = [r for r in releases if not r.prerelease and r.apk is not None]
    return sorted(stable, key=lambda r: parse_version(r.version)[:3], reverse=True)  # type: ignore[index]


@dataclass
class ReleaseService:
    repository: str
    token: str | None = None
    cache_seconds: int = 900
    transport: httpx.BaseTransport | None = None
    _cache: tuple[float, ReleaseStatus] | None = field(default=None, init=False)

    def _client(self) -> httpx.Client:
        headers = {"Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28",
                   "User-Agent": "aviation-trip-optimizer"}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        return httpx.Client(timeout=10.0, headers=headers, transport=self.transport, follow_redirects=True)

    def status(self, limit: int = 5, force: bool = False) -> ReleaseStatus:
        now = time.monotonic()
        if not force and self._cache and now - self._cache[0] < self.cache_seconds:
            cached = self._cache[1]
            return cached.model_copy(update={"releases": cached.releases[:limit]})
        result = self._fetch()
        if result.status == "unavailable" and self._cache:
            # Veralteten Stand weiter ausliefern, aber kennzeichnen.
            stale = self._cache[1]
            return stale.model_copy(update={"message": "GitHub derzeit nicht erreichbar – zwischengespeicherter "
                                                       "Stand.", "releases": stale.releases[:limit]})
        if result.status != "unavailable":
            self._cache = (now, result)
        return result.model_copy(update={"releases": result.releases[:limit]})

    def _fetch(self) -> ReleaseStatus:
        checked = datetime.now(UTC)
        base = ReleaseStatus(status="unavailable", repository=self.repository, checked_at=checked,
                             releases_url=f"https://github.com/{self.repository}/releases" if self.repository
                             else None)
        if not self.repository or "/" not in self.repository:
            return base.model_copy(update={"status": "not_configured",
                                           "message": "GITHUB_REPOSITORY ist nicht gesetzt."})
        try:
            with self._client() as client:
                resp = client.get(f"https://api.github.com/repos/{self.repository}/releases",
                                  params={"per_page": 30})
                if resp.status_code == 404:
                    return base.model_copy(update={"status": "no_release",
                                                   "message": "Repository oder Releases nicht gefunden."})
                if resp.status_code in (403, 429):
                    return base.model_copy(update={"message": "GitHub-API-Limit erreicht. Später erneut prüfen."})
                if resp.status_code != 200:
                    return base.model_copy(update={"message": f"GitHub-API antwortete mit HTTP {resp.status_code}."})
                data = resp.json()
                if not isinstance(data, list):
                    return base.model_copy(update={"message": "Unerwartete Antwort der GitHub-API."})
                releases = [r for r in (parse_release(x, self.repository) for x in data) if r]
                stable = stable_releases_with_apk(releases)
                if not stable:
                    return base.model_copy(update={"status": "no_release",
                                                   "message": "Noch kein stabiles Release mit APK veröffentlicht."})
                latest = stable[0]
                if latest.checksum:
                    latest.sha256 = self._fetch_checksum(client, latest.checksum.url)
                return base.model_copy(update={"status": "ok", "latest": latest, "releases": stable})
        except (httpx.HTTPError, ValueError):
            return base.model_copy(update={"message": "Versionsprüfung nicht möglich (Netzwerkfehler)."})

    @staticmethod
    def _fetch_checksum(client: httpx.Client, url: str) -> str | None:
        try:
            resp = client.get(url)
            if resp.status_code != 200 or len(resp.content) > 4096:
                return None
            m = SHA256.search(resp.text)
            return m[1].lower() if m else None
        except httpx.HTTPError:
            return None
