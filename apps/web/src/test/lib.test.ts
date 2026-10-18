import vectors from "@/generated/semver-vectors.json";
import { ApiClient, ApiError, isAllowedBaseUrl } from "@/lib/api";
import { checkReleases, parseStableRelease, summarizeNotes, updateState } from "@/lib/releases";
import { DEFAULT_FILTER, filterAndSort } from "@/lib/results";
import { compareVersions, parseVersion, versionCode } from "@/lib/semver";
import type { SearchResponse } from "@/lib/types";
import { parseCodes, validateSearch, type SearchFormValues } from "@/lib/validation";
import fixture from "./fixtures/search-response.json";

const REPO = "Atomkarnickel/aviation-trip-optimizer";
const DL = `https://github.com/${REPO}/releases/download`;
const response = fixture as unknown as SearchResponse;

function release(tag: string, extra: Record<string, unknown> = {}) {
  const v = tag.replace(/^v/, "");
  return {
    tag_name: tag,
    draft: false,
    prerelease: false,
    published_at: "2026-10-01T10:00:00Z",
    html_url: `https://github.com/${REPO}/releases/tag/${tag}`,
    body: "## What's Changed\n- Neue Suche",
    assets: [
      { name: `aviation-optimizer-${v}.apk`, browser_download_url: `${DL}/${tag}/aviation-optimizer-${v}.apk`, size: 2048 },
      { name: `aviation-optimizer-${v}.apk.sha256`, browser_download_url: `${DL}/${tag}/aviation-optimizer-${v}.apk.sha256`, size: 90 },
    ],
    ...extra,
  };
}

const respond = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status }));
const fetcherOf = (fn: () => Promise<Response>) => fn as unknown as typeof fetch;

describe("semver (gemeinsame Testvektoren)", () => {
  it("vergleicht wie Backend und Android", () => {
    for (const c of vectors.compare) {
      expect(compareVersions(c.a, c.b)).toBe(c.result);
      expect(compareVersions(c.b, c.a)).toBe(c.result === 0 ? 0 : -c.result);
    }
  });
  it("lehnt ungültige Versionen ab", () => {
    for (const v of vectors.invalid) {
      expect(parseVersion(v)).toBeNull();
      expect(() => compareVersions(v, "1.0.0")).toThrow();
    }
  });
  it("berechnet versionCode", () => {
    for (const c of vectors.versionCode) expect(versionCode(c.version)).toBe(c.code);
    expect(() => versionCode("1.0.0-beta.1")).toThrow();
  });
});

describe("Release-Metadaten", () => {
  it("akzeptiert nur stabile Releases mit APK vom eigenen Repo", () => {
    expect(parseStableRelease(release("v1.2.0"))?.apkUrl).toBe(`${DL}/v1.2.0/aviation-optimizer-1.2.0.apk`);
    expect(parseStableRelease(release("v1.2.0", { draft: true }))).toBeNull();
    expect(parseStableRelease(release("v1.2.0", { prerelease: true }))).toBeNull();
    expect(parseStableRelease(release("v1.3.0-rc.1"))).toBeNull();
    expect(parseStableRelease(release("v1.2.0", { assets: [] }))).toBeNull();
    const foreign = release("v1.2.0");
    (foreign.assets as { browser_download_url: string }[])[0].browser_download_url = "https://evil.example/aviation-optimizer-1.2.0.apk";
    expect(parseStableRelease(foreign)).toBeNull();
  });

  it("ermittelt neueste stabile Version und Update-Status", async () => {
    const check = await checkReleases({
      force: true,
      fetcher: fetcherOf(() => respond([release("v1.2.0"), release("v1.10.0"), release("v2.0.0", { prerelease: true })])),
    });
    expect(check.status).toBe("ok");
    if (check.status !== "ok") return;
    expect(check.latest.version).toBe("1.10.0");
    expect(check.releases.map((r) => r.version)).toEqual(["1.10.0", "1.2.0"]);
    expect(updateState("1.0.0", check)).toBe("update_available");
    expect(updateState("1.10.0", check)).toBe("current");
    expect(updateState("kaputt", check)).toBe("unknown");
  });

  it("meldet Rate-Limit und Netzwerkfehler, nutzt aber den Cache", async () => {
    const limited = await checkReleases({ force: true, fetcher: fetcherOf(() => respond({}, 403)) });
    expect(limited.status).toBe("unavailable");
    expect(updateState("1.0.0", limited)).toBe("unknown");
    await checkReleases({ force: true, fetcher: fetcherOf(() => respond([release("v1.0.0")])) });
    const offline = await checkReleases({ force: true, fetcher: fetcherOf(() => Promise.reject(new TypeError("offline"))) });
    expect(offline.status).toBe("ok");
    expect(offline.status === "ok" && offline.stale).toBe(true);
  });

  it("liefert no_release ohne APK und erkennt unerwartete Antworten", async () => {
    expect((await checkReleases({ force: true, fetcher: fetcherOf(() => respond([])) })).status).toBe("no_release");
    expect((await checkReleases({ force: true, fetcher: fetcherOf(() => respond({ message: "x" })) })).status).toBe("unavailable");
  });

  it("fasst Release Notes zusammen", () => {
    expect(summarizeNotes("## What's Changed\n\n* Neue Vergleichsansicht\n")).toBe("Neue Vergleichsansicht");
  });
});

describe("Suchvalidierung", () => {
  const base: SearchFormValues = {
    origins: "zrh, BSL",
    destinations: "",
    earliestDeparture: "2026-12-18",
    latestReturn: "2027-01-06",
    minStay: 5,
    maxStay: 14,
    adults: 2,
    maxBudget: "",
    maxStops: 1,
    maxTravelHours: "",
    desiredAircraft: [],
    cabinClass: "economy",
    requireCheckedBag: false,
    outboundEarliestHour: "",
    outboundLatestHour: "",
    maxDateCombinations: 6,
  };
  it("akzeptiert gültige Eingaben", () => {
    expect(validateSearch(base)).toEqual({});
    expect(parseCodes("zrh, BSL;zrh")).toEqual(["ZRH", "BSL"]);
  });
  it("meldet typische Fehler", () => {
    expect(validateSearch({ ...base, origins: "" }).origins).toBeTruthy();
    expect(validateSearch({ ...base, origins: "ZURICH" }).origins).toBeTruthy();
    expect(validateSearch({ ...base, destinations: "ZRH" }).destinations).toBeTruthy();
    expect(validateSearch({ ...base, latestReturn: "2026-12-01" }).latestReturn).toBeTruthy();
    expect(validateSearch({ ...base, minStay: 10, maxStay: 5 }).maxStay).toBeTruthy();
    expect(validateSearch({ ...base, minStay: 30, maxStay: 40 }).minStay).toBeTruthy();
    expect(validateSearch({ ...base, maxBudget: "-5" }).maxBudget).toBeTruthy();
    expect(validateSearch({ ...base, adults: 0 }).adults).toBeTruthy();
  });
});

describe("Ergebnisfilter", () => {
  it("filtert und sortiert", () => {
    const all = filterAndSort(response.results, DEFAULT_FILTER, "score");
    expect(all.map((r) => r.rank)).toEqual([...all.map((r) => r.rank)].sort((a, b) => a - b));
    const byPrice = filterAndSort(response.results, DEFAULT_FILTER, "price").map((r) => r.itinerary.price.total);
    expect(byPrice).toEqual([...byPrice].sort((a, b) => a - b));
    const wish = filterAndSort(response.results, { ...DEFAULT_FILTER, wishlistOnly: true }, "score");
    expect(wish.length).toBeGreaterThan(0);
    expect(wish.every((r) => r.wishlist_hits.length > 0)).toBe(true);
    expect(filterAndSort(response.results, { ...DEFAULT_FILTER, maxPrice: 1 }, "score")).toHaveLength(0);
    const nonstop = filterAndSort(response.results, { ...DEFAULT_FILTER, nonstopOnly: true }, "score");
    expect(nonstop.every((r) => r.itinerary.stops === 0)).toBe(true);
  });
});

describe("API-Client", () => {
  it("verlangt HTTPS (außer localhost)", () => {
    expect(isAllowedBaseUrl("https://api.example.org")).toBe(true);
    expect(isAllowedBaseUrl("http://localhost:8000")).toBe(true);
    expect(isAllowedBaseUrl("http://api.example.org")).toBe(false);
    expect(isAllowedBaseUrl("kein-url")).toBe(false);
  });
  it("ohne Backend: klare Fehlermeldung statt Fake-Daten", async () => {
    await expect(new ApiClient("").meta()).rejects.toMatchObject({ kind: "not_configured" });
  });
  it("übersetzt HTTP-Fehler", async () => {
    const make = (status: number, body: unknown) => new ApiClient("https://x.org", fetcherOf(() => respond(body, status)));
    await expect(
      make(422, { detail: "Ungültige Eingabe", errors: [{ field: "origins.0", message: "Ungültiger Flughafen-Code" }] }).meta(),
    ).rejects.toMatchObject({ kind: "validation", fieldErrors: [{ field: "origins.0", message: "Ungültiger Flughafen-Code" }] });
    await expect(make(429, { detail: "Zu viele" }).meta()).rejects.toMatchObject({ kind: "rate_limited" });
    await expect(make(500, {}).meta()).rejects.toMatchObject({ kind: "server" });
    const offline = new ApiClient("https://x.org", fetcherOf(() => Promise.reject(new TypeError("fail"))));
    await expect(offline.meta()).rejects.toBeInstanceOf(ApiError);
    await expect(offline.meta()).rejects.toMatchObject({ kind: "network" });
  });
});
