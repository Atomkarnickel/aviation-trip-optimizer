"use client";

import { useState } from "react";
import { AircraftPreferences } from "@/components/AircraftPreferences";
import { MODE_DEFAULTS, WeightsEditor } from "@/components/WeightsEditor";
import { ApiClient, isAllowedBaseUrl, normalizeBaseUrl } from "@/lib/api";
import { MODE_LABEL } from "@/lib/format";
import { DEFAULT_SETTINGS, useSettings } from "@/lib/settings";
import { removeKey } from "@/lib/storage";
import { parseCodes } from "@/lib/validation";

export default function ProfilePage() {
  const [settings, setSettings, loaded] = useSettings();
  const [url, setUrl] = useState<string | null>(null);
  const [test, setTest] = useState<{ ok: boolean; text: string } | null>(null);
  const [confirmReset, setConfirmReset] = useState(false);
  if (!loaded) return null;
  const urlValue = url ?? settings.apiBaseUrl;
  const weights = settings.customWeights ?? MODE_DEFAULTS[settings.mode];

  async function testConnection() {
    const value = normalizeBaseUrl(urlValue);
    if (!isAllowedBaseUrl(value)) {
      setTest({ ok: false, text: "Nur HTTPS-Adressen (oder http://localhost für Entwicklung) sind erlaubt." });
      return;
    }
    try {
      const meta = await new ApiClient(value, undefined, 15000).meta();
      setSettings({ ...settings, apiBaseUrl: value });
      setTest({ ok: true, text: `Verbunden: Backend v${meta.backend_version}, Provider ${meta.provider.name} (${meta.provider.mode}).` });
    } catch (e) {
      setTest({ ok: false, text: (e as Error).message });
    }
  }

  return (
    <div className="stack">
      <div className="page-head">
        <div>
          <h1>Profil</h1>
          <p className="muted">Präferenzen, Gewichtung und Verbindung. Alles bleibt lokal in diesem Browser.</p>
        </div>
      </div>

      <section className="panel stack" aria-labelledby="p-conn">
        <h2 id="p-conn">Verbindung zum Backend</h2>
        <div className="field">
          <label htmlFor="api-url">Backend-URL</label>
          <input id="api-url" value={urlValue} placeholder="https://dein-backend.example" onChange={(e) => setUrl(e.target.value)} />
          <span className="hint">Dieselbe API wie die Android-App. Ohne Backend ist keine Flugsuche möglich.</span>
        </div>
        <div className="row">
          <button className="btn" type="button" onClick={testConnection}>Verbindung testen & speichern</button>
          {settings.apiBaseUrl && (
            <button className="btn secondary" type="button" onClick={() => { setSettings({ ...settings, apiBaseUrl: "" }); setUrl(""); setTest(null); }}>Entfernen</button>
          )}
        </div>
        {test && <div className={`banner ${test.ok ? "ok" : "error"}`} role="status">{test.text}</div>}
      </section>

      <section className="panel stack" aria-labelledby="p-weights">
        <h2 id="p-weights">Optimierung & Gewichtung</h2>
        <div className="segmented" role="group" aria-label="Modus">
          {(["best_value", "aircraft_hunter", "cheapest"] as const).map((m) => (
            <button key={m} type="button" aria-pressed={settings.mode === m && !settings.customWeights} onClick={() => setSettings({ ...settings, mode: m, customWeights: null })}>
              {MODE_LABEL[m]}
            </button>
          ))}
        </div>
        <WeightsEditor value={weights} onChange={(w) => setSettings({ ...settings, customWeights: w })} />
        <p className="small muted">
          Der Gesamtscore ist der gewichtete Mittelwert der Teilscores (0–100). Prozentwerte zeigen den Anteil jedes Kriteriums.
          {settings.customWeights ? " Eigene Gewichtung aktiv." : ` Voreinstellung „${MODE_LABEL[settings.mode]}“.`}
        </p>
        <div className="field" style={{ maxWidth: 360 }}>
          <label htmlFor="flown">Bereits geflogene Typen bewerten mit</label>
          <input id="flown" type="range" min={0} max={1} step={0.1} value={settings.flownFactor} onChange={(e) => setSettings({ ...settings, flownFactor: Number(e.target.value) })} />
          <span className="hint">{Math.round(settings.flownFactor * 100)} % der normalen Coolness</span>
        </div>
      </section>

      <section className="panel stack" aria-labelledby="p-travel">
        <h2 id="p-travel">Reisepräferenzen</h2>
        <div className="form-grid">
          <div className="field">
            <label htmlFor="home">Heimatflughäfen</label>
            <input id="home" defaultValue={settings.homeAirports.join(", ")} onBlur={(e) => setSettings({ ...settings, homeAirports: parseCodes(e.target.value).filter((c) => /^[A-Z]{3}$/.test(c)) })} />
          </div>
          <div className="field">
            <label htmlFor="pref-air">Bevorzugte Airlines (IATA)</label>
            <input id="pref-air" defaultValue={settings.preferredAirlines.join(", ")} onBlur={(e) => setSettings({ ...settings, preferredAirlines: parseCodes(e.target.value).filter((c) => /^[A-Z0-9]{2}$/.test(c)) })} />
          </div>
          <div className="field">
            <label htmlFor="ex-air">Ausgeschlossene Airlines (IATA)</label>
            <input id="ex-air" defaultValue={settings.excludedAirlines.join(", ")} onBlur={(e) => setSettings({ ...settings, excludedAirlines: parseCodes(e.target.value).filter((c) => /^[A-Z0-9]{2}$/.test(c)) })} />
          </div>
        </div>
      </section>

      <section className="panel stack" aria-labelledby="p-aircraft">
        <h2 id="p-aircraft">Flugzeuge & persönlicher Coolness-Score</h2>
        <p className="small muted">„Unbedingt erleben“ = Wunschliste (+2 Bonus, mind. 9/10). „Uninteressant“ = 0. Eigene Bewertung ersetzt den Standardwert.</p>
        <AircraftPreferences settings={settings} onChange={setSettings} />
      </section>

      <section className="panel stack" aria-labelledby="p-data">
        <h2 id="p-data">Daten & Datenschutz</h2>
        <p className="small">
          Einstellungen, Favoriten und Logbuch werden ausschließlich im lokalen Speicher dieses Browsers abgelegt. Es gibt kein Konto. An das Backend
          gehen nur Suchparameter und Präferenzen – keine Namen oder Kontaktdaten.
        </p>
        {confirmReset ? (
          <div className="row">
            <span>Alle lokalen Daten wirklich löschen?</span>
            <button className="btn danger" type="button" onClick={() => { ["ato.settings.v1", "ato.favorites.v1", "ato.logbook.v1", "ato.releaseCheck.v1"].forEach(removeKey); setSettings(DEFAULT_SETTINGS); setConfirmReset(false); }}>Ja, löschen</button>
            <button className="btn secondary" type="button" onClick={() => setConfirmReset(false)}>Abbrechen</button>
          </div>
        ) : (
          <div><button className="btn danger" type="button" onClick={() => setConfirmReset(true)}>Lokale Daten zurücksetzen</button></div>
        )}
      </section>
    </div>
  );
}
