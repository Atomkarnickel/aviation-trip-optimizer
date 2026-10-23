"""Tests für die Release-Skripte (nur Standardbibliothek): python -m unittest discover -s scripts/tests"""

import importlib.util
import sys
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]
ROOT = SCRIPTS.parent


def load(name: str):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module  # dataclasses brauchen das Modul in sys.modules
    spec.loader.exec_module(module)
    return module


readme = load("update_readme")
changelog = load("changelog")
bump = load("bump_version")
REPO = "Atomkarnickel/aviation-trip-optimizer"


def release(tag, draft=False, pre=False, apk=True, host=f"https://github.com/{REPO}", published="2026-10-24T10:00:00Z", body="Kurz.\n\n## APK"):
    v = tag[1:]
    assets = []
    if apk:
        assets = [
            {"name": f"overhead-{v}.apk", "browser_download_url": f"{host}/releases/download/{tag}/overhead-{v}.apk"},
            {"name": f"overhead-{v}.apk.sha256", "browser_download_url": f"{host}/releases/download/{tag}/overhead-{v}.apk.sha256"},
        ]
    return {"tag_name": tag, "draft": draft, "prerelease": pre, "published_at": published,
            "html_url": f"https://github.com/{REPO}/releases/tag/{tag}", "body": body, "assets": assets}


class ReadmeTest(unittest.TestCase):
    def test_lists_five_newest_stable_releases_with_own_apk(self):
        raw = [release(f"v1.{i}.0") for i in range(8)]
        raw += [release("v2.0.0-rc.1", pre=True), release("v1.9.0", draft=True), release("v1.8.0", apk=False),
                release("v1.10.0", host="https://evil.example.com")]
        stable = readme.newest_stable(raw, REPO)
        self.assertEqual([r.version for r in stable], ["1.7.0", "1.6.0", "1.5.0", "1.4.0", "1.3.0"])
        text = readme.render(REPO, stable)
        self.assertTrue(text.startswith(readme.START) and text.endswith(readme.END))
        self.assertIn("Neueste stabile Version: 1.7.0", text)
        self.assertIn("[SHA-256](https://github.com/Atomkarnickel/aviation-trip-optimizer/releases/download/v1.7.0/overhead-1.7.0.apk.sha256)", text)
        self.assertEqual(len([line for line in text.splitlines() if line.startswith("| [")]), 5)

    def test_numeric_version_sort_and_summary(self):
        stable = readme.newest_stable([release("v1.9.0"), release("v1.10.0", body="## Neu\nWidget | Radar")], REPO)
        self.assertEqual(stable[0].version, "1.10.0")
        self.assertIn("Neu", stable[0].summary)
        self.assertIn("Widget \\| Radar", readme.render(REPO, [readme.parse_release(release("v1.0.0", body="Widget | Radar"), REPO)]))

    def test_empty_and_marker_replacement(self):
        self.assertIn("Noch kein stabiles Release", readme.render(REPO, []))
        doc = f"Kopf\n{readme.START}\nalt\n{readme.END}\nFuß"
        self.assertEqual(readme.replace_section(doc, readme.render(REPO, [])).split("\n")[0], "Kopf")
        self.assertTrue(readme.replace_section(doc, "X").endswith("X\nFuß"))
        with self.assertRaises(ValueError):
            readme.replace_section("ohne Marker", "X")


class ChangelogTest(unittest.TestCase):
    def test_section(self):
        text = "# Log\n\n## [1.1.0] – x\n\nKurz.\n\n### Neu\n- a\n\n## [1.0.0] – y\n\nAlt.\n"
        self.assertEqual(changelog.section(text, "1.1.0"), "Kurz.\n\n### Neu\n- a")
        self.assertEqual(changelog.section(text, "1.0.0"), "Alt.")
        self.assertIsNone(changelog.section(text, "2.0.0"))

    def test_current_version_is_documented(self):
        version = (ROOT / "VERSION").read_text(encoding="utf-8").strip()
        self.assertTrue(changelog.section((ROOT / "CHANGELOG.md").read_text(encoding="utf-8"), version),
                        f"CHANGELOG.md: Abschnitt für {version} fehlt")


class BumpTest(unittest.TestCase):
    def test_bump_rules(self):
        self.assertEqual(bump.bump("1.2.3", "patch"), "1.2.4")
        self.assertEqual(bump.bump("1.2.3", "minor"), "1.3.0")
        self.assertEqual(bump.bump("1.2.3", "major"), "2.0.0")
        self.assertEqual(bump.version_code("1.2.3"), 10203)
        with self.assertRaises(SystemExit):
            bump.bump("1.2.3", "1.2.3")
        with self.assertRaises(SystemExit):
            bump.bump("1.2.3", "1.2")


if __name__ == "__main__":
    unittest.main()
