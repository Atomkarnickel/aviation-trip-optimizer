#!/usr/bin/env python3
"""Gibt den CHANGELOG.md-Abschnitt einer Version aus (für die Release-Notes).

Aufruf: python scripts/changelog.py 1.1.0   → Text des Abschnitts „## [1.1.0] – …“ auf stdout
Fehlt der Abschnitt oder ist er leer, endet das Skript mit Fehler – ein Release ohne Änderungsbeschreibung bricht ab.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
HEADING = re.compile(r"^## \[(?P<version>[^\]]+)\]")


def section(text: str, version: str) -> str | None:
    lines: list[str] | None = None
    for line in text.splitlines():
        m = HEADING.match(line)
        if m:
            if lines is not None:
                break
            if m.group("version") == version:
                lines = []
            continue
        if lines is not None:
            lines.append(line)
    if lines is None:
        return None
    body = "\n".join(lines).strip()
    return body or None


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    version = sys.argv[1].removeprefix("v")
    body = section((ROOT / "CHANGELOG.md").read_text(encoding="utf-8"), version)
    if body is None:
        raise SystemExit(f"CHANGELOG.md enthält keinen (nicht leeren) Abschnitt für {version}.")
    sys.stdout.reconfigure(encoding="utf-8")
    print(body)


if __name__ == "__main__":
    main()
