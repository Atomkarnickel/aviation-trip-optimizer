// Release-Metadaten direkt aus der GitHub-Releases-API (gemeinsame Quelle für README, Android, Web, Backend).
// Regeln: siehe shared/RELEASE_METADATA.md.

import { APK_PREFIX, RELEASE_CACHE_SECONDS, REPOSITORY } from "./config";
import { compareVersions, parseVersion } from "./semver";
import { readJson, writeJson } from "./storage";

export interface ApkRelease {
  version: string;
  tag: string;
  publishedAt: string;
  htmlUrl: string;
  summary: string;
  apkName: string;
  apkUrl: string;
  apkSize: number | null;
  checksumUrl: string | null;
}

export type ReleaseCheck =
  | { status: "ok"; latest: ApkRelease; releases: ApkRelease[]; checkedAt: string; stale?: boolean }
  | { status: "no_release" | "unavailable" | "not_configured"; message: string; checkedAt: string };

interface RawAsset {
  name?: unknown;
  browser_download_url?: unknown;
  size?: unknown;
}

interface RawRelease {
  tag_name?: unknown;
  draft?: unknown;
  prerelease?: unknown;
  published_at?: unknown;
  html_url?: unknown;
  body?: unknown;
  assets?: unknown;
}

export function summarizeNotes(body: string, limit = 160): string {
  for (const line of body.split(/\r?\n/)) {
    const text = line.trim().replace(/^[#*\->\s]+/, "").trim();
    const lower = text.toLowerCase();
    if (text && !lower.startsWith("what's changed") && !lower.startsWith("full changelog") && !lower.startsWith("sha")) {
      return text.length <= limit ? text : `${text.slice(0, limit - 1).trimEnd()}…`;
    }
  }
  return "";
}

/** Prüft einen Rohdatensatz der GitHub-API; liefert nur stabile Releases mit gültigem APK-Asset. */
export function parseStableRelease(raw: RawRelease, repository = REPOSITORY, prefix = APK_PREFIX): ApkRelease | null {
  if (!raw || typeof raw !== "object" || raw.draft === true || raw.prerelease === true) return null;
  const tag = typeof raw.tag_name === "string" ? raw.tag_name : "";
  const parsed = parseVersion(tag);
  if (!tag.startsWith("v") || !parsed || parsed.pre !== null) return null;
  if (typeof raw.published_at !== "string") return null;
  const version = tag.slice(1);
  const allowed = `https://github.com/${repository}/releases/download/`;
  const assets = Array.isArray(raw.assets) ? (raw.assets as RawAsset[]) : [];
  const apk = assets.find(
    (a) => a?.name === `${prefix}${version}.apk` && typeof a.browser_download_url === "string" &&
      a.browser_download_url.startsWith(allowed),
  );
  if (!apk) return null;
  const checksum = assets.find(
    (a) => a?.name === `${prefix}${version}.apk.sha256` && typeof a.browser_download_url === "string" &&
      a.browser_download_url.startsWith(allowed),
  );
  const htmlUrl = typeof raw.html_url === "string" && raw.html_url.startsWith("https://github.com/")
    ? raw.html_url
    : `https://github.com/${repository}/releases/tag/${tag}`;
  return {
    version,
    tag,
    publishedAt: raw.published_at,
    htmlUrl,
    summary: summarizeNotes(typeof raw.body === "string" ? raw.body : ""),
    apkName: String(apk.name),
    apkUrl: String(apk.browser_download_url),
    apkSize: typeof apk.size === "number" ? apk.size : null,
    checksumUrl: checksum ? String(checksum.browser_download_url) : null,
  };
}

const CACHE_KEY = "ato.releaseCheck.v1";

export async function checkReleases(options: { force?: boolean; fetcher?: typeof fetch } = {}): Promise<ReleaseCheck> {
  const now = new Date();
  const checkedAt = now.toISOString();
  if (!REPOSITORY.includes("/")) {
    return { status: "not_configured", message: "Kein Repository konfiguriert.", checkedAt };
  }
  const cached = readJson<{ at: number; value: ReleaseCheck }>(CACHE_KEY);
  if (!options.force && cached && now.getTime() - cached.at < RELEASE_CACHE_SECONDS * 1000) {
    return cached.value;
  }
  const fetcher = options.fetcher ?? fetch;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 10000);
  try {
    const resp = await fetcher(`https://api.github.com/repos/${REPOSITORY}/releases?per_page=30`, {
      headers: { Accept: "application/vnd.github+json" },
      signal: controller.signal,
    });
    if (resp.status === 403 || resp.status === 429) {
      return staleOr(cached?.value, { status: "unavailable", message: "GitHub-API-Limit erreicht – später erneut prüfen.", checkedAt });
    }
    if (resp.status === 404) {
      return { status: "no_release", message: "Repository oder Releases nicht gefunden.", checkedAt };
    }
    if (!resp.ok) {
      return staleOr(cached?.value, { status: "unavailable", message: `GitHub antwortete mit HTTP ${resp.status}.`, checkedAt });
    }
    const data: unknown = await resp.json();
    if (!Array.isArray(data)) {
      return staleOr(cached?.value, { status: "unavailable", message: "Unerwartete Antwort der GitHub-API.", checkedAt });
    }
    const releases = data
      .map((r) => parseStableRelease(r as RawRelease))
      .filter((r): r is ApkRelease => r !== null)
      .sort((a, b) => compareVersions(b.version, a.version));
    const value: ReleaseCheck = releases.length
      ? { status: "ok", latest: releases[0], releases, checkedAt }
      : { status: "no_release", message: "Noch kein stabiles Release mit APK veröffentlicht.", checkedAt };
    writeJson(CACHE_KEY, { at: now.getTime(), value });
    return value;
  } catch {
    return staleOr(cached?.value, { status: "unavailable", message: "Versionsprüfung nicht möglich (Netzwerkfehler).", checkedAt });
  } finally {
    clearTimeout(timer);
  }
}

function staleOr(cached: ReleaseCheck | undefined, fallback: ReleaseCheck): ReleaseCheck {
  if (cached && cached.status === "ok") return { ...cached, stale: true };
  return fallback;
}

export type UpdateState = "current" | "update_available" | "newer_than_release" | "unknown";

export function updateState(installed: string, check: ReleaseCheck): UpdateState {
  if (check.status !== "ok") return "unknown";
  try {
    const c = compareVersions(installed, check.latest.version);
    return c === 0 ? "current" : c < 0 ? "update_available" : "newer_than_release";
  } catch {
    return "unknown";
  }
}
