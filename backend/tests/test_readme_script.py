import importlib.util
from pathlib import Path

from app.services.releases import parse_release, stable_releases_with_apk
from tests.test_releases import REPO, release

SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "update_readme.py"
spec = importlib.util.spec_from_file_location("update_readme", SCRIPT)
update_readme = importlib.util.module_from_spec(spec)
spec.loader.exec_module(update_readme)


def test_lists_five_newest_stable_releases_only():
    raw = [release(f"v1.{i}.0", published=f"2026-0{min(i, 9) or 1}-01T10:00:00Z") for i in range(8)]
    raw += [release("v2.0.0-rc.1", prerelease=True), release("v1.9.0", draft=True), release("v1.8.0", assets=False)]
    stable = stable_releases_with_apk([r for r in (parse_release(x, REPO) for x in raw) if r])[:5]
    text = update_readme.render(REPO, stable)
    assert text.startswith(update_readme.START) and text.endswith(update_readme.END)
    assert "Neueste stabile Version: 1.7.0" in text
    rows = [line for line in text.splitlines() if line.startswith("| [")]
    assert [r.split("]")[0][3:] for r in rows] == ["1.7.0", "1.6.0", "1.5.0", "1.4.0", "1.3.0"]
    assert "2.0.0-rc.1" not in text and "1.8.0" not in text and "1.9.0" not in text
    assert f"https://github.com/{REPO}/releases/download/v1.7.0/aviation-optimizer-1.7.0.apk" in text


def test_no_release_text_has_no_fake_links():
    text = update_readme.render(REPO, [])
    assert "Noch kein stabiles Release" in text
    assert "/releases/download/" not in text
