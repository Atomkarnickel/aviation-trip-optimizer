"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { ComparePanel } from "@/components/ComparePanel";
import { ProviderBanner } from "@/components/ProviderBanner";
import { ResultCard } from "@/components/ResultCard";
import { SearchForm, defaultFormValues } from "@/components/SearchForm";
import { ApiClient, ApiError } from "@/lib/api";
import { MODE_LABEL, money } from "@/lib/format";
import { DEFAULT_FILTER, filterAndSort, type ResultFilter, type SortKey } from "@/lib/results";
import { toPreferences, useFavorites, useSettings } from "@/lib/settings";
import type { CompareResponse, OptimizationMode, ProviderStatus, SearchResponse } from "@/lib/types";
import { toSearchRequest, type FieldErrors, type SearchFormValues } from "@/lib/validation";

const FIELD_MAP: Record<string, keyof SearchFormValues> = {
  origins: "origins",
  destinations: "destinations",
  earliest_departure: "earliestDeparture",
  latest_return: "latestReturn",
  min_stay_days: "minStay",
  max_stay_days: "maxStay",
  adults: "adults",
  max_budget: "maxBudget",
  max_travel_hours: "maxTravelHours",
};

const STATUS_TEXT: Record<string, string> = {
  no_results: "Keine passenden Reisen gefunden. Zeitraum, Budget oder Filter lockern.",
  provider_not_configured: "Keine Flugdatenquelle konfiguriert – es werden bewusst keine Ergebnisse angezeigt.",
  provider_error: "Der Flugdaten-Provider konnte nicht abgefragt werden.",
  partial: "Einige Abfragen sind fehlgeschlagen – Ergebnisse sind unvollständig.",
};

export default function DiscoverPage() {
  const [settings, setSettings, loaded] = useSettings();
  const { isFavorite, toggle } = useFavorites();
  const api = useMemo(() => new ApiClient(settings.apiBaseUrl), [settings.apiBaseUrl]);
  const [provider, setProvider] = useState<ProviderStatus | null>(null);
  const [backendError, setBackendError] = useState<string | null>(null);
  const [response, setResponse] = useState<SearchResponse | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [sort, setSort] = useState<SortKey>("score");
  const [filter, setFilter] = useState<ResultFilter>(DEFAULT_FILTER);
  const [compareIds, setCompareIds] = useState<string[]>([]);
  const [compare, setCompare] = useState<CompareResponse | null>(null);
  const inFlight = useRef(false);

  useEffect(() => {
    if (!loaded) return;
    if (!api.configured) {
      setBackendError("Es ist noch keine Backend-URL hinterlegt (Profil → Verbindung).");
      return;
    }
    setBackendError(null);
    api.meta().then((m) => setProvider(m.provider)).catch((e: ApiError) => setBackendError(e.message));
  }, [api, loaded]);

  const preferences = toPreferences(settings);

  async function runSearch(values: SearchFormValues) {
    if (inFlight.current) return; // Doppelte Anfragen verhindern
    inFlight.current = true;
    setBusy(true);
    setError(null);
    setFieldErrors({});
    setCompare(null);
    setCompareIds([]);
    try {
      const req = toSearchRequest(values, {
        preferences,
        preferredAirlines: settings.preferredAirlines,
        excludedAirlines: settings.excludedAirlines,
      });
      setResponse(await api.search(req as never));
    } catch (e) {
      const err = e as ApiError;
      setError(err.message);
      if (err.kind === "validation") {
        const mapped: FieldErrors = {};
        for (const fe of err.fieldErrors) {
          const key = FIELD_MAP[fe.field.split(".")[0]];
          if (key) mapped[key] = fe.message;
        }
        setFieldErrors(mapped);
        if (err.fieldErrors.length) setError(err.fieldErrors.map((f) => f.message).join(" "));
      }
    } finally {
      setBusy(false);
      inFlight.current = false;
    }
  }

  async function changeMode(mode: OptimizationMode) {
    const next = { ...settings, mode, customWeights: null };
    setSettings(next);
    if (!response || response.results.length === 0) return;
    setBusy(true);
    try {
      setResponse(await api.rescore(response.search_id, toPreferences(next) as never));
    } catch (e) {
      setError((e as ApiError).message);
    } finally {
      setBusy(false);
    }
  }

  async function openCompare() {
    if (!response) return;
    try {
      setCompare(await api.compare(response.search_id, compareIds));
    } catch (e) {
      setError((e as ApiError).message);
    }
  }

  const items = response ? filterAndSort(response.results, filter, sort) : [];
  const airlines = response ? Array.from(new Set(response.results.flatMap((r) => r.itinerary.airlines))).sort() : [];
  const rankById = new Map(response?.results.map((r) => [r.itinerary.id, r.rank]) ?? []);
  const wishlist = Object.entries(settings.aircraftStatus).filter(([, s]) => s === "wishlist").map(([id]) => id);

  return (
    <div className="stack">
      <div className="page-head">
        <div>
          <h1>Entdecken</h1>
          <p className="muted">Finde die beste Kombination aus Preis, Reisezeit, Aufenthalt und Flugzeugerlebnis.</p>
        </div>
        <div className="segmented" role="group" aria-label="Optimierungsmodus">
          {(["best_value", "aircraft_hunter", "cheapest"] as const).map((m) => (
            <button key={m} type="button" aria-pressed={settings.mode === m && !settings.customWeights} onClick={() => changeMode(m)}>
              {MODE_LABEL[m]}
            </button>
          ))}
        </div>
      </div>
      {settings.customWeights && <p className="small muted">Eigene Gewichtung aktiv (Profil). Ein Klick auf einen Modus setzt sie zurück.</p>}

      <ProviderBanner provider={provider} backendError={backendError} />

      {loaded && (
        <SearchForm
          initial={defaultFormValues(settings.homeAirports, wishlist)}
          busy={busy}
          disabled={Boolean(backendError)}
          serverErrors={fieldErrors}
          onSubmit={runSearch}
        />
      )}

      {error && (
        <div className="banner error" role="alert">
          <div>
            <strong>Suche fehlgeschlagen</strong>
            {error}
          </div>
        </div>
      )}

      {busy && !response && (
        <div className="stack" aria-busy="true" aria-label="Ergebnisse werden geladen">
          <div className="skeleton" />
          <div className="skeleton" />
        </div>
      )}

      {response && (
        <section className="stack" aria-labelledby="results-title" aria-busy={busy}>
          <div className="page-head" style={{ marginBottom: 0 }}>
            <div>
              <h2 id="results-title" style={{ marginBottom: 2 }}>
                {response.results.length} Reisevarianten · {MODE_LABEL[response.mode]}
              </h2>
              <p className="small muted">
                {response.stats.combinations_searched} von {response.stats.combinations_total} Datums-/Routenkombinationen geprüft ·{" "}
                {response.stats.offers_received} Angebote erhalten
                {response.cheapest_total != null && ` · günstigste ${money(response.cheapest_total, response.results[0]?.itinerary.price.currency ?? "EUR")}`}
                {response.cached && " · aus Zwischenspeicher"}
              </p>
            </div>
            <div className="row">
              <label className="field" style={{ minWidth: 150 }}>
                <span className="label">Sortieren</span>
                <select value={sort} onChange={(e) => setSort(e.target.value as SortKey)}>
                  <option value="score">Gesamtscore</option>
                  <option value="price">Preis</option>
                  <option value="duration">Reisezeit</option>
                  <option value="stay">Aufenthaltstage</option>
                  <option value="aircraft">Flugzeugerlebnis</option>
                </select>
              </label>
            </div>
          </div>

          <div className="row panel" style={{ padding: 12 }} role="group" aria-label="Ergebnisfilter">
            <label className="check"><input type="checkbox" checked={filter.nonstopOnly} onChange={(e) => setFilter({ ...filter, nonstopOnly: e.target.checked })} /> Nur Nonstop</label>
            <label className="check"><input type="checkbox" checked={filter.wishlistOnly} onChange={(e) => setFilter({ ...filter, wishlistOnly: e.target.checked })} /> Nur mit Wunschflugzeug</label>
            <label className="check">
              Max. Preis
              <input style={{ width: 110 }} inputMode="decimal" aria-label="Maximaler Preis" value={filter.maxPrice ?? ""} onChange={(e) => setFilter({ ...filter, maxPrice: e.target.value ? Number(e.target.value) : null })} />
            </label>
            <label className="check">
              Airline
              <select style={{ width: 120 }} value={filter.airline} onChange={(e) => setFilter({ ...filter, airline: e.target.value })}>
                <option value="">alle</option>
                {airlines.map((a) => <option key={a} value={a}>{a}</option>)}
              </select>
            </label>
            <span style={{ flex: 1 }} />
            <button className="btn small" type="button" disabled={compareIds.length < 2} onClick={openCompare}>
              Vergleichen ({compareIds.length}/4)
            </button>
          </div>

          {response.status !== "ok" && (
            <div className={`banner ${response.status === "partial" ? "warn" : response.status === "no_results" ? "info" : "error"}`} role="status">
              <div>{STATUS_TEXT[response.status] ?? response.status}</div>
            </div>
          )}
          {response.warnings.length > 0 && (
            <details className="panel small">
              <summary>Hinweise zur Suche ({response.warnings.length})</summary>
              <ul className="plain">{response.warnings.map((w, i) => <li key={i}>{w}</li>)}</ul>
            </details>
          )}

          {compare && <ComparePanel data={compare} items={response.results} onClose={() => setCompare(null)} />}

          {items.length === 0 && response.results.length > 0 && <p className="muted">Keine Ergebnisse für die gewählten Filter.</p>}
          {items.map((item) => (
            <ResultCard
              key={item.itinerary.id}
              item={item}
              favorite={isFavorite(item.itinerary.id)}
              onToggleFavorite={() => toggle(response.search_id, item)}
              compareSelected={compareIds.includes(item.itinerary.id)}
              compareDisabled={compareIds.length >= 4}
              onToggleCompare={() =>
                setCompareIds((ids) => (ids.includes(item.itinerary.id) ? ids.filter((x) => x !== item.itinerary.id) : [...ids, item.itinerary.id]))
              }
              betterAlternativeRank={item.explanation.better_value_alternative_id ? rankById.get(item.explanation.better_value_alternative_id) : undefined}
            />
          ))}
        </section>
      )}
    </div>
  );
}
