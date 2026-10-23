#!/usr/bin/env python3
"""Setzt die zentrale Version (Datei VERSION). versionName und versionCode der App leiten sich daraus ab.

Aufruf: python scripts/bump_version.py 1.2.0   |   python scripts/bump_version.py patch|minor|major
Danach in CHANGELOG.md einen Abschnitt „## [X.Y.Z] – Datum“ ergänzen.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STABLE = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$")


def version_code(v: str) -> int:
    major, minor, patch = map(int, v.split("."))
    if minor > 99 or patch > 99:
        raise SystemExit("MINOR und PATCH müssen < 100 sein (versionCode-Schema).")
    return major * 10000 + minor * 100 + patch


def bump(current: str, arg: str) -> str:
    major, minor, patch = map(int, current.split("."))
    new = {"patch": f"{major}.{minor}.{patch + 1}", "minor": f"{major}.{minor + 1}.0", "major": f"{major + 1}.0.0"}.get(arg, arg)
    if not STABLE.match(new):
        raise SystemExit(f"Ungültige Version: {new}")
    if version_code(new) <= version_code(current):
        raise SystemExit(f"Neue Version {new} muss größer als {current} sein (versionCode streng monoton).")
    return new


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    current = (ROOT / "VERSION").read_text(encoding="utf-8").strip()
    new = bump(current, sys.argv[1])
    (ROOT / "VERSION").write_text(new + "\n", encoding="utf-8", newline="\n")
    print(f"VERSION: {current} -> {new} (versionCode {version_code(new)}). Tag: v{new}")


if __name__ == "__main__":
    main()
