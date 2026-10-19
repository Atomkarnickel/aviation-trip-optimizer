#!/usr/bin/env python3
"""Aktualisiert den Download-Abschnitt der README anhand real veröffentlichter GitHub-Releases.

* Nutzt dieselben Regeln wie Backend/Android/Web (backend/app/services/releases.py).
* Listet die fünf neuesten stabilen Releases mit APK-Asset.
* Prüft jeden Download-Link per HTTP, bevor die README als aktualisiert gilt (Exit-Code 1 bei Fehler).

Aufruf:  python scripts/update_readme.py [--check]
Umgebung: GITHUB_TOKEN (optional, erhöht API-Limits)
"""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))

from app.core.config import RELEASE_CONFIG  # noqa: E402
from app.services.releases import ReleaseInfo, parse_release, stable_releases_with_apk  # noqa: E402

START = "<!-- APK-DOWNLOADS:START -->"
END = "<!-- APK-DOWNLOADS:END -->"


def fetch_releases(repo: str, token: str | None) -> list[ReleaseInfo]:
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "aviation-trip-optimizer-readme"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    resp = httpx.get(f"https://api.github.com/repos/{repo}/releases", params={"per_page": 50}, headers=headers,
                     timeout=20)
    resp.raise_for_status()
    releases = [r for r in (parse_release(x, repo) for x in resp.json()) if r]
    return stable_releases_with_apk(releases)[:5]


def verify_link(url: str) -> bool:
    try:
        r = httpx.head(url, follow_redirects=True, timeout=30)
        if r.status_code == 405:
            r = httpx.get(url, follow_redirects=True, timeout=60, headers={"Range": "bytes=0-0"})
        return r.status_code in (200, 206)
    except httpx.HTTPError:
        return False


def render(repo: str, releases: list[ReleaseInfo]) -> str:
    releases_url = f"https://github.com/{repo}/releases"
    if not releases:
        return (f"{START}\nNoch kein stabiles Release mit APK veröffentlicht. Sobald ein Release existiert, steht hier "
                f"automatisch der direkte Downloadlink.\n\nAlle Releases: <{releases_url}>\n{END}")
    latest = releases[0]
    lines = [
        START,
        f"**Neueste stabile Version: {latest.version}** ({latest.published_at:%d.%m.%Y})",
        "",
        f"➡️ **[{latest.apk.name} herunterladen]({latest.apk.url})**"  # type: ignore[union-attr]
        + (f" · [SHA-256]({latest.checksum.url})" if latest.checksum else ""),
        "",
        f"Immer aktuelle Release-Seite: <https://github.com/{repo}/releases/latest>",
        "",
        "### Die fünf neuesten stabilen Releases",
        "",
        "| Version | Veröffentlicht | Download | Änderungen |",
        "|---|---|---|---|",
    ]
    for r in releases:
        summary = (r.summary or "–").replace("|", "\\|")
        lines.append(f"| [{r.version}]({r.html_url}) | {r.published_at:%d.%m.%Y} | "
                     f"[{r.apk.name}]({r.apk.url}) | {summary} |")  # type: ignore[union-attr]
    lines += ["", "_Dieser Abschnitt wird nach jedem Release automatisch erzeugt (scripts/update_readme.py)._", END]
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="Nur prüfen, nicht schreiben")
    args = parser.parse_args()

    repo = os.environ.get("GITHUB_REPOSITORY") or RELEASE_CONFIG["repository"]
    releases = fetch_releases(repo, os.environ.get("GITHUB_TOKEN"))
    broken = [r.apk.url for r in releases if not verify_link(r.apk.url)]  # type: ignore[union-attr]
    if broken:
        print("Fehlerhafte Downloadlinks:\n  " + "\n  ".join(broken), file=sys.stderr)
        return 1

    readme = ROOT / "README.md"
    text = readme.read_text(encoding="utf-8")
    if START not in text or END not in text:
        print("README enthält die Marker nicht.", file=sys.stderr)
        return 1
    new_section = render(repo, releases)
    updated = text[: text.index(START)] + new_section + text[text.index(END) + len(END):]
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
