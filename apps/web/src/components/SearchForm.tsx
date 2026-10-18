"use client";

import { useState, type FormEvent } from "react";
import { AIRCRAFT } from "@/lib/aircraft";
import { validateSearch, type FieldErrors, type SearchFormValues } from "@/lib/validation";

const WISH_CANDIDATES = AIRCRAFT.filter((a) => a.status !== "historical" && a.default_coolness >= 5.5);

function isoDate(offsetDays: number): string {
  const d = new Date();
  d.setDate(d.getDate() + offsetDays);
  return d.toISOString().slice(0, 10);
}

export function defaultFormValues(homeAirports: string[], wishlist: string[]): SearchFormValues {
  return {
    origins: homeAirports.join(", "),
    destinations: "",
    earliestDeparture: isoDate(60),
    latestReturn: isoDate(79),
    minStay: 5,
    maxStay: 14,
    adults: 1,
    maxBudget: "",
    maxStops: 1,
    maxTravelHours: "",
    desiredAircraft: wishlist,
    cabinClass: "economy",
    requireCheckedBag: false,
    outboundEarliestHour: "",
    outboundLatestHour: "",
    maxDateCombinations: 6,
  };
}

interface Props {
  initial: SearchFormValues;
  busy: boolean;
  disabled?: boolean;
  serverErrors?: FieldErrors;
  onSubmit: (values: SearchFormValues) => void;
}

export function SearchForm({ initial, busy, disabled, serverErrors, onSubmit }: Props) {
  const [v, setV] = useState<SearchFormValues>(initial);
  const [errors, setErrors] = useState<FieldErrors>({});
  const [expanded, setExpanded] = useState(false);
  const shown = { ...serverErrors, ...errors };

  const set = <K extends keyof SearchFormValues>(key: K, value: SearchFormValues[K]) => setV((p) => ({ ...p, [key]: value }));

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const found = validateSearch(v);
    setErrors(found);
    if (Object.keys(found).length === 0) onSubmit(v);
  };

  const err = (k: keyof SearchFormValues) =>
    shown[k] ? (
      <span className="error" id={`err-${k}`} role="alert">
        {shown[k]}
      </span>
    ) : null;
  const a11y = (k: keyof SearchFormValues) => ({
    "aria-invalid": shown[k] ? true : undefined,
    "aria-describedby": shown[k] ? `err-${k}` : undefined,
  });

  return (
    <form className="panel stack" onSubmit={submit} noValidate aria-label="Reise suchen">
      <div className="form-grid">
        <div className="field">
          <label htmlFor="origins">Abflughäfen</label>
          <input id="origins" value={v.origins} onChange={(e) => set("origins", e.target.value)} placeholder="ZRH, BSL" autoComplete="off" {...a11y("origins")} />
          {err("origins") ?? <span className="hint">IATA-Codes, durch Komma getrennt</span>}
        </div>
        <div className="field">
          <label htmlFor="destinations">Ziele (optional)</label>
          <input id="destinations" value={v.destinations} onChange={(e) => set("destinations", e.target.value)} placeholder="leer = offene Zielsuche" autoComplete="off" {...a11y("destinations")} />
          {err("destinations") ?? <span className="hint">Mehrere Ziele möglich</span>}
        </div>
        <div className="field">
          <label htmlFor="earliest">Frühester Abflug</label>
          <input id="earliest" type="date" value={v.earliestDeparture} onChange={(e) => set("earliestDeparture", e.target.value)} {...a11y("earliestDeparture")} />
          {err("earliestDeparture")}
        </div>
        <div className="field">
          <label htmlFor="latest">Spätester Rückflug</label>
          <input id="latest" type="date" value={v.latestReturn} onChange={(e) => set("latestReturn", e.target.value)} {...a11y("latestReturn")} />
          {err("latestReturn")}
        </div>
        <div className="field">
          <label htmlFor="minStay">Reisedauer min. (Nächte)</label>
          <input id="minStay" type="number" min={1} max={60} value={v.minStay} onChange={(e) => set("minStay", Number(e.target.value))} {...a11y("minStay")} />
          {err("minStay")}
        </div>
        <div className="field">
          <label htmlFor="maxStay">Reisedauer max. (Nächte)</label>
          <input id="maxStay" type="number" min={1} max={60} value={v.maxStay} onChange={(e) => set("maxStay", Number(e.target.value))} {...a11y("maxStay")} />
          {err("maxStay")}
        </div>
        <div className="field">
          <label htmlFor="adults">Reisende</label>
          <input id="adults" type="number" min={1} max={9} value={v.adults} onChange={(e) => set("adults", Number(e.target.value))} {...a11y("adults")} />
          {err("adults")}
        </div>
        <div className="field">
          <label htmlFor="budget">Max. Gesamtbudget (EUR)</label>
          <input id="budget" inputMode="decimal" value={v.maxBudget} onChange={(e) => set("maxBudget", e.target.value)} placeholder="optional" {...a11y("maxBudget")} />
          {err("maxBudget")}
        </div>
      </div>

      <fieldset className="field" style={{ border: 0, padding: 0, margin: 0 }}>
        <legend className="label">Wunschflugzeuge</legend>
        <div className="row" style={{ gap: 6 }}>
          {WISH_CANDIDATES.map((a) => {
            const on = v.desiredAircraft.includes(a.id);
            return (
              <button
                type="button"
                key={a.id}
                className="chip"
                aria-pressed={on}
                onClick={() => set("desiredAircraft", on ? v.desiredAircraft.filter((x) => x !== a.id) : [...v.desiredAircraft, a.id])}
              >
                {a.model.startsWith(a.manufacturer) ? a.model : `${a.manufacturer.split(" ")[0]} ${a.model}`}
              </button>
            );
          })}
        </div>
      </fieldset>

      <button type="button" className="btn ghost" style={{ alignSelf: "flex-start" }} aria-expanded={expanded} onClick={() => setExpanded((x) => !x)}>
        {expanded ? "Weniger Optionen" : "Weitere Optionen"}
      </button>
      {expanded && (
        <div className="form-grid">
          <div className="field">
            <label htmlFor="stops">Max. Umstiege je Richtung</label>
            <select id="stops" value={v.maxStops} onChange={(e) => set("maxStops", Number(e.target.value))}>
              <option value={0}>Nur Nonstop</option>
              <option value={1}>1</option>
              <option value={2}>2</option>
              <option value={3}>3</option>
            </select>
          </div>
          <div className="field">
            <label htmlFor="maxHours">Max. Reisezeit je Richtung (h)</label>
            <input id="maxHours" inputMode="decimal" value={v.maxTravelHours} onChange={(e) => set("maxTravelHours", e.target.value)} placeholder="optional" {...a11y("maxTravelHours")} />
            {err("maxTravelHours")}
          </div>
          <div className="field">
            <label htmlFor="cabin">Kabine</label>
            <select id="cabin" value={v.cabinClass} onChange={(e) => set("cabinClass", e.target.value as SearchFormValues["cabinClass"])}>
              <option value="economy">Economy</option>
              <option value="premium_economy">Premium Economy</option>
              <option value="business">Business</option>
              <option value="first">First</option>
            </select>
          </div>
          <div className="field">
            <label htmlFor="depFrom">Hinflug-Abflug ab (Uhr)</label>
            <input id="depFrom" type="number" min={0} max={23} value={v.outboundEarliestHour} onChange={(e) => set("outboundEarliestHour", e.target.value)} placeholder="0" />
          </div>
          <div className="field">
            <label htmlFor="depTo">Hinflug-Abflug bis (Uhr)</label>
            <input id="depTo" type="number" min={0} max={23} value={v.outboundLatestHour} onChange={(e) => set("outboundLatestHour", e.target.value)} placeholder="23" />
          </div>
          <div className="field">
            <label htmlFor="combos">Datumskombinationen je Route</label>
            <input id="combos" type="number" min={1} max={20} value={v.maxDateCombinations} onChange={(e) => set("maxDateCombinations", Number(e.target.value))} />
            <span className="hint">Mehr = gründlicher, aber langsamer</span>
          </div>
          <label className="check">
            <input type="checkbox" checked={v.requireCheckedBag} onChange={(e) => set("requireCheckedBag", e.target.checked)} />
            Aufgabegepäck benötigt
          </label>
        </div>
      )}

      <div className="row">
        <button className="btn" type="submit" disabled={busy || disabled}>
          {busy ? <span className="spinner" aria-hidden="true" /> : null}
          {busy ? "Suche läuft …" : "Reisen suchen"}
        </button>
        {disabled && <span className="small muted">Suche erst möglich, wenn ein Backend verbunden ist.</span>}
      </div>
    </form>
  );
}
