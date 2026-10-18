import type {
  AircraftType,
  Airport,
  CompareResponse,
  MetaResponse,
  SearchRequest,
  SearchResponse,
  UserPreferences,
  Weights,
} from "./types";

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly kind: "not_configured" | "network" | "timeout" | "validation" | "rate_limited" | "server" | "not_found",
    public readonly fieldErrors: { field: string; message: string }[] = [],
  ) {
    super(message);
  }
}

export function normalizeBaseUrl(url: string): string {
  return url.trim().replace(/\/+$/, "");
}

export function isAllowedBaseUrl(url: string): boolean {
  try {
    const u = new URL(url);
    return u.protocol === "https:" || (u.protocol === "http:" && ["localhost", "127.0.0.1"].includes(u.hostname));
  } catch {
    return false;
  }
}

export class ApiClient {
  constructor(
    private readonly baseUrl: string,
    private readonly fetcher: typeof fetch = (...args) => fetch(...args),
    private readonly timeoutMs = 60000,
  ) {}

  get configured(): boolean {
    return Boolean(this.baseUrl) && isAllowedBaseUrl(this.baseUrl);
  }

  private async request<T>(path: string, init?: RequestInit): Promise<T> {
    if (!this.configured) {
      throw new ApiError("Kein Backend verbunden. Hinterlege die Backend-URL im Profil.", "not_configured");
    }
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    let resp: Response;
    try {
      resp = await this.fetcher(`${normalizeBaseUrl(this.baseUrl)}/api/v1${path}`, {
        ...init,
        headers: { "Content-Type": "application/json", ...(init?.headers ?? {}) },
        signal: controller.signal,
      });
    } catch (err) {
      if ((err as Error)?.name === "AbortError") {
        throw new ApiError("Zeitüberschreitung – das Backend antwortet nicht.", "timeout");
      }
      throw new ApiError("Backend nicht erreichbar. Netzwerk oder Backend-URL prüfen.", "network");
    } finally {
      clearTimeout(timer);
    }
    if (resp.ok) return (await resp.json()) as T;
    let body: { detail?: unknown; errors?: { field: string; message: string }[] } = {};
    try {
      body = await resp.json();
    } catch {
      /* keine JSON-Antwort */
    }
    const detail = typeof body.detail === "string" ? body.detail : "";
    if (resp.status === 422) throw new ApiError(detail || "Ungültige Eingabe", "validation", body.errors ?? []);
    if (resp.status === 429) throw new ApiError(detail || "Zu viele Anfragen – bitte kurz warten.", "rate_limited");
    if (resp.status === 404) throw new ApiError(detail || "Nicht gefunden.", "not_found");
    throw new ApiError(`Serverfehler (HTTP ${resp.status}). Bitte später erneut versuchen.`, "server");
  }

  meta() {
    return this.request<MetaResponse>("/meta");
  }
  modes() {
    return this.request<Record<string, Weights>>("/modes");
  }
  aircraft() {
    return this.request<AircraftType[]>("/aircraft");
  }
  airports(q: string) {
    return this.request<Airport[]>(`/airports?q=${encodeURIComponent(q)}&limit=10`);
  }
  search(body: SearchRequest) {
    return this.request<SearchResponse>("/search", { method: "POST", body: JSON.stringify(body) });
  }
  rescore(searchId: string, preferences: UserPreferences) {
    return this.request<SearchResponse>(`/search/${encodeURIComponent(searchId)}/rescore`, {
      method: "POST",
      body: JSON.stringify({ preferences }),
    });
  }
  compare(searchId: string, ids: string[]) {
    return this.request<CompareResponse>(`/search/${encodeURIComponent(searchId)}/compare`, {
      method: "POST",
      body: JSON.stringify({ itinerary_ids: ids }),
    });
  }
}
