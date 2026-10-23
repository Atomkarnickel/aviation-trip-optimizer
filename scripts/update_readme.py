#!/usr/bin/env python3
"""Aktualisiert den Download-Abschnitt der README anhand real veröffentlichter GitHub-Releases.

* Nur stabile Releases (kein Entwurf, keine Vorabversion) mit APK-Asset aus diesem Repository.
* Listet die fünf neuesten und prüft jeden Download-Link per HTTP (Exit-Code 1 bei Fehler).
* Nur Python-Standardbibliothek – läuft ohne Installation.

Aufruf:  python scripts/update_readme.py [--check]
Umgebung: GITHUB_REPOSITORY (Standard: Atomkarnickel/aviation-trip-optimizer), GITHUB_TOKEN (optional, höhere API-Limits)
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_REPO = "Atomkarnickel/aviation-trip-optimizer"
APK_PREFIX = "overhead-"
START = "<!-- APK-DOWNLOADS:START -->"
END = "<!-- APK-DOWNLOADS:END -->"
STABLE = re.compile(r"^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$")


@dataclass(frozen=True)
class Release:
    version: str
    published: datetime
    html_url: str
    apk_name: str
    apk_url: str
    checksum_url: str | None
    summary: str

    @property
    def key(self) -> tuple[int, int, int]:
        return tuple(int(x) for x in self.version.split("."))  # type: ignore[return-value]


def summarize(body: str, limit: int = 160) -> str:
    for line in (body or "").splitlines():
        text = line.strip().lstrip("#*->").strip()
        if text and not text.lower().startswith(("what's changed", "full changelog", "sha-256", "sha256")):
            return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"
    return ""


def parse_release(raw: dict, repo: str) -> Release | None:
    if not isinstance(raw, dict) or raw.get("draft") or raw.get("prerelease"):
        return None
    tag = raw.get("tag_name") or ""
    if not STABLE.match(tag) or not raw.get("published_at"):
        return None
    version = tag[1:]
    allowed = f"https://github.com/{repo}/releases/download/"
    assets = {a.get("name"): a.get("browser_download_url") or "" for a in raw.get("assets") or []}
    apk_name = f"{APK_PREFIX}{version}.apk"
    apk_url = assets.get(apk_name, "")
    if not apk_url.startswith(allowed):
        return None
    checksum = assets.get(f"{apk_name}.sha256", "")
    html_url = raw.get("html_url") or ""
    return Release(
        version=version,
        published=datetime.fromisoformat(raw["published_at"].replace("Z", "+00:00")),
        html_url=html_url if html_url.startswith("https://github.com/") else f"https://github.com/{repo}/releases/tag/{tag}",
        apk_name=apk_name,
        apk_url=apk_url,
        checksum_url=checksum if checksum.startswith(allowed) else None,
        summary=summarize(raw.get("body") or ""),
    )


def newest_stable(raw: list, repo: str, limit: int = 5) -> list[Release]:
    parsed = [r for r in (parse_release(x, repo) for x in raw) if r]
    return sorted(parsed, key=lambda r: r.key, reverse=True)[:limit]


def _request(url: str, token: str | None = None, method: str = "GET") -> urllib.request.Request:
    headers = {"User-Agent": "overhead-readme", "Accept": "application/vnd.github+json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    return urllib.request.Request(url, headers=headers, method=method)


def fetch_releases(repo: str, token: str | None) -> list:
    with urllib.request.urlopen(_request(f"https://api.github.com/repos/{repo}/releases?per_page=50", token), timeout=30) as resp:
        return json.load(resp)


def verify_link(url: str) -> bool:
    for method in ("HEAD", "GET"):
        try:
            req = _request(url, method=method)
            if method == "GET":
                req.add_header("Range", "bytes=0-0")
            with urllib.request.urlopen(req, timeout=60) as resp:
                if resp.status in (200, 206):
                    return True
        except urllib.error.HTTPError as e:
            if e.code != 405:
                return False
        except OSError:
            return False
    return False


def render(repo: str, releases: list[Release]) -> str:
    releases_url = f"https://github.com/{repo}/releases"
    if not releases:
        return (f"{START}\nNoch kein stabiles Release mit APK veröffentlicht. Sobald ein Release existiert, steht hier "
                f"automatisch der direkte Downloadlink.\n\nAlle Releases: <{releases_url}>\n{END}")
    latest = releases[0]
    lines = [
        START,
        f"**Neueste stabile Version: {latest.version}** ({latest.published:%d.%m.%Y})",
        "",
        f"➡️ **[{latest.apk_name} herunterladen]({latest.apk_url})**"
        + (f" · [SHA-256]({latest.checksum_url})" if latest.checksum_url else ""),
        "",
        f"Immer aktuelle Release-Seite: <{releases_url}/latest>",
        "",
        "### Die fünf neuesten stabilen Releases",
        "",
        "| Version | Veröffentlicht | Download | Änderungen |",
        "|---|---|---|---|",
    ]
    for r in releases:
        summary = (r.summary or "–").replace("|", "\\|")
        lines.append(f"| [{r.version}]({r.html_url}) | {r.published:%d.%m.%Y} | [{r.apk_name}]({r.apk_url}) | {summary} |")
    lines += ["", "_Dieser Abschnitt wird nach jedem Release automatisch erzeugt (scripts/update_readme.py)._", END]
    return "\n".join(lines)


def replace_section(text: str, section: str) -> str:
    if START not in text or END not in text:
        raise ValueError("README enthält die Marker nicht.")
    return text[: text.index(START)] + section + text[text.index(END) + len(END):]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="Nur prüfen, nicht schreiben")
    args = parser.parse_args()

    repo = os.environ.get("GITHUB_REPOSITORY") or DEFAULT_REPO
    releases = newest_stable(fetch_releases(repo, os.environ.get("GITHUB_TOKEN")), repo)
    broken = [r.apk_url for r in releases if not verify_link(r.apk_url)]
    if broken:
        print("Fehlerhafte Downloadlinks:\n  " + "\n  ".join(broken), file=sys.stderr)
        return 1

    readme = ROOT / "README.md"
    text = readme.read_text(encoding="utf-8")
    updated = replace_section(text, render(repo, releases))
    if args.check:
        print("README aktuell." if updated == text else "README veraltet.")
        return 0 if updated == text else 2
    if updated != text:
        readme.write_text(updated, encoding="utf-8", newline="\n")
        print(f"README aktualisiert ({len(releases)} Release(s), alle Links geprüft).")
    else:
        print("README bereits aktuell.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
