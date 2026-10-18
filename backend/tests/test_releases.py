import httpx
import pytest

from app.services.releases import (
    ReleaseService,
    compare_versions,
    parse_release,
    parse_version,
    stable_releases_with_apk,
    summarize_notes,
    version_code,
)

REPO = "Atomkarnickel/aviation-trip-optimizer"
DL = f"https://github.com/{REPO}/releases/download"


def release(tag, prerelease=False, draft=False, assets=True, published="2026-10-01T10:00:00Z", body="- Neu: X"):
    v = tag.lstrip("v")
    items = []
    if assets:
        items = [
            {"name": f"aviation-optimizer-{v}.apk", "browser_download_url": f"{DL}/{tag}/aviation-optimizer-{v}.apk",
             "size": 12345},
            {"name": f"aviation-optimizer-{v}.apk.sha256",
             "browser_download_url": f"{DL}/{tag}/aviation-optimizer-{v}.apk.sha256", "size": 100},
        ]
    return {"tag_name": tag, "name": tag, "draft": draft, "prerelease": prerelease, "published_at": published,
            "html_url": f"https://github.com/{REPO}/releases/tag/{tag}", "body": body, "assets": items}


def test_shared_compare_vectors(semver_vectors):
    for case in semver_vectors["compare"]:
        assert compare_versions(case["a"], case["b"]) == case["result"], case
        assert compare_versions(case["b"], case["a"]) == -case["result"], case


def test_shared_invalid_vectors(semver_vectors):
    for value in semver_vectors["invalid"]:
        assert parse_version(value) is None, value
        with pytest.raises(ValueError):
            compare_versions(value, "1.0.0")


def test_shared_version_codes(semver_vectors):
    for case in semver_vectors["versionCode"]:
        assert version_code(case["version"]) == case["code"]
    with pytest.raises(ValueError):
        version_code("1.100.0")
    with pytest.raises(ValueError):
        version_code("1.0.0-beta.1")


def test_parse_release_filters():
    assert parse_release(release("v1.0.0", draft=True), REPO) is None
    assert parse_release(release("1.0.0"), REPO) is None  # Tag ohne v
    assert parse_release(release("vfoo"), REPO) is None
    r = parse_release(release("v1.2.0"), REPO)
    assert r.apk.url.endswith("/v1.2.0/aviation-optimizer-1.2.0.apk") and r.checksum
    assert parse_release(release("v1.3.0-beta.1"), REPO).prerelease


def test_rejects_foreign_asset_hosts():
    raw = release("v1.2.0")
    raw["assets"][0]["browser_download_url"] = "https://evil.example.com/aviation-optimizer-1.2.0.apk"
    assert parse_release(raw, REPO).apk is None
    raw = release("v1.2.0")
    raw["assets"][0]["browser_download_url"] = raw["assets"][0]["browser_download_url"].replace("https", "http")
    assert parse_release(raw, REPO).apk is None


def test_stable_sorting_excludes_prerelease_and_missing_apk():
    rels = [parse_release(x, REPO) for x in [release("v1.2.0"), release("v1.10.0"), release("v2.0.0-rc.1"),
                                              release("v1.11.0", assets=False), release("v1.9.3")]]
    assert [r.version for r in stable_releases_with_apk(rels)] == ["1.10.0", "1.9.3", "1.2.0"]


def test_summary():
    assert summarize_notes("## What's Changed\n\n- Neue Suche für Wunschflugzeuge\n") == \
        "Neue Suche für Wunschflugzeuge"


def _service(handler):
    return ReleaseService(REPO, cache_seconds=60, transport=httpx.MockTransport(handler))


def test_service_ok_with_checksum():
    sha = "a" * 64

    def handler(req: httpx.Request):
        if req.url.host == "api.github.com":
            return httpx.Response(200, json=[release("v1.0.0"), release("v1.1.0"), release("v1.2.0-beta.1",
                                                                                            prerelease=True)])
        return httpx.Response(200, text=f"{sha}  aviation-optimizer-1.1.0.apk\n")

    st = _service(handler).status()
    assert st.status == "ok"
    assert st.latest.version == "1.1.0"
    assert st.latest.sha256 == sha
    assert [r.version for r in st.releases] == ["1.1.0", "1.0.0"]


def test_service_no_release():
    st = _service(lambda r: httpx.Response(200, json=[])).status()
    assert st.status == "no_release" and st.latest is None


def test_service_rate_limited_and_unexpected():
    assert _service(lambda r: httpx.Response(403, json={})).status().status == "unavailable"
    assert _service(lambda r: httpx.Response(200, json={"x": 1})).status().status == "unavailable"

    def boom(r):
        raise httpx.ConnectError("offline")

    assert _service(boom).status().status == "unavailable"


def test_service_serves_stale_cache_when_github_fails():
    state = {"fail": False}

    def handler(req):
        if state["fail"]:
            return httpx.Response(503)
        if req.url.host == "api.github.com":
            return httpx.Response(200, json=[release("v1.0.0")])
        return httpx.Response(404)

    svc = _service(handler)
    assert svc.status().status == "ok"
    state["fail"] = True
    st = svc.status(force=True)
    assert st.status == "ok" and "zwischengespeichert" in st.message


def test_not_configured():
    assert ReleaseService("").status().status == "not_configured"
