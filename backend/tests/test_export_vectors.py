import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def load(name: str):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


gen = load("gen_export_vectors")
changelog = load("changelog")


def test_vectors_are_up_to_date():
    # Web und Android testen gegen diese Datei – sie muss exakt der Referenzimplementierung entsprechen.
    current = (ROOT / "shared" / "test-vectors" / "exports.json").read_text(encoding="utf-8")
    assert current == gen.render(), "shared/test-vectors/exports.json veraltet: python scripts/gen_export_vectors.py"


def test_reference_distance_matches_backend():
    from app.services.reference import get_reference

    expected = get_reference().distance_km("ZRH", "SFO")
    assert abs(gen.distance_km([47.46, 8.55], [37.62, -122.38]) - expected) < 1e-6


def test_ics_lines_are_folded_and_crlf():
    ics = gen.build_vectors()["ics"]["expected"]
    assert ics.endswith("\r\n") and "\n" not in ics.replace("\r\n", "")
    assert all(len(line.encode()) <= 75 for line in ics.split("\r\n"))


def test_changelog_section():
    text = "# Log\n\n## [1.1.0] – x\n\nKurz.\n\n### Neu\n- a\n\n## [1.0.0] – y\n\nAlt.\n"
    assert changelog.section(text, "1.1.0") == "Kurz.\n\n### Neu\n- a"
    assert changelog.section(text, "1.0.0") == "Alt."
    assert changelog.section(text, "2.0.0") is None
    assert changelog.section("## [1.0.0]\n\n", "1.0.0") is None


def test_current_version_has_changelog_entry():
    version = (ROOT / "VERSION").read_text(encoding="utf-8").strip()
    text = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
    assert changelog.section(text, version), f"CHANGELOG.md: Abschnitt für {version} fehlt"
