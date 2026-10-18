"use client";

import { useState, type FormEvent } from "react";
import { AIRCRAFT, aircraftName } from "@/lib/aircraft";
import { dateShort } from "@/lib/format";
import { useLogbook, useSettings, type LogbookEntry } from "@/lib/settings";

const EMPTY: Omit<LogbookEntry, "id"> = { date: "", flightNumber: "", origin: "", destination: "", aircraftTypeId: "A380", airline: "", status: "flown", notes: "" };

export default function LogbookPage() {
  const [entries, setEntries, loaded] = useLogbook();
  const [settings, setSettings] = useSettings();
  const [form, setForm] = useState(EMPTY);
  const [error, setError] = useState<string | null>(null);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!form.date) return setError("Datum angeben.");
    if (form.origin && !/^[A-Za-z]{3}$/.test(form.origin)) return setError("Abflughafen als IATA-Code (3 Buchstaben).");
    if (form.destination && !/^[A-Za-z]{3}$/.test(form.destination)) return setError("Ziel als IATA-Code (3 Buchstaben).");
    setError(null);
    const entry: LogbookEntry = {
      ...form,
      id: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      origin: form.origin.toUpperCase(),
      destination: form.destination.toUpperCase(),
      flightNumber: form.flightNumber.toUpperCase().trim(),
      airline: form.airline.toUpperCase().trim(),
    };
    setEntries((prev) => [entry, ...prev].sort((a, b) => b.date.localeCompare(a.date)));
    if (entry.status === "flown" && (settings.aircraftStatus[entry.aircraftTypeId] ?? "neutral") === "neutral") {
      setSettings({ ...settings, aircraftStatus: { ...settings.aircraftStatus, [entry.aircraftTypeId]: "flown" } });
    }
    setForm({ ...EMPTY, aircraftTypeId: form.aircraftTypeId });
  };

  const flownTypes = new Set(entries.filter((e) => e.status === "flown").map((e) => e.aircraftTypeId));

  return (
    <div className="stack">
      <div className="page-head">
        <div>
          <h1>Flugzeuglogbuch</h1>
          <p className="muted">Vergangene und geplante Flüge – lokal in diesem Browser gespeichert.</p>
        </div>
        <div className="row">
          <span className="chip cool">{entries.filter((e) => e.status === "flown").length} Flüge</span>
          <span className="chip cool">{flownTypes.size} Flugzeugtypen</span>
        </div>
      </div>

      <form className="panel stack" onSubmit={submit} aria-label="Logbucheintrag hinzufügen" noValidate>
        <h2>Eintrag hinzufügen</h2>
        <div className="form-grid">
          <div className="field"><label htmlFor="lb-date">Datum</label><input id="lb-date" type="date" value={form.date} onChange={(e) => setForm({ ...form, date: e.target.value })} /></div>
          <div className="field"><label htmlFor="lb-status">Status</label>
            <select id="lb-status" value={form.status} onChange={(e) => setForm({ ...form, status: e.target.value as LogbookEntry["status"] })}>
              <option value="flown">Geflogen</option><option value="planned">Geplant</option>
            </select>
          </div>
          <div className="field"><label htmlFor="lb-type">Flugzeugtyp</label>
            <select id="lb-type" value={form.aircraftTypeId} onChange={(e) => setForm({ ...form, aircraftTypeId: e.target.value })}>
              {AIRCRAFT.map((a) => <option key={a.id} value={a.id}>{aircraftName(a.id)}</option>)}
            </select>
          </div>
          <div className="field"><label htmlFor="lb-airline">Airline (IATA)</label><input id="lb-airline" maxLength={2} value={form.airline} onChange={(e) => setForm({ ...form, airline: e.target.value })} /></div>
          <div className="field"><label htmlFor="lb-flight">Flugnummer</label><input id="lb-flight" maxLength={8} value={form.flightNumber} onChange={(e) => setForm({ ...form, flightNumber: e.target.value })} /></div>
          <div className="field"><label htmlFor="lb-from">Von</label><input id="lb-from" maxLength={3} value={form.origin} onChange={(e) => setForm({ ...form, origin: e.target.value })} /></div>
          <div className="field"><label htmlFor="lb-to">Nach</label><input id="lb-to" maxLength={3} value={form.destination} onChange={(e) => setForm({ ...form, destination: e.target.value })} /></div>
          <div className="field"><label htmlFor="lb-notes">Notiz</label><input id="lb-notes" maxLength={200} value={form.notes} onChange={(e) => setForm({ ...form, notes: e.target.value })} /></div>
        </div>
        {error && <p className="error" role="alert" style={{ color: "var(--danger)" }}>{error}</p>}
        <div><button className="btn" type="submit">Speichern</button></div>
        <p className="small muted">Geflogene Typen werden automatisch als „Schon geflogen“ markiert (Bewertung × {settings.flownFactor}), sofern du sie nicht anders markiert hast.</p>
      </form>

      <section className="panel" aria-labelledby="lb-list">
        <h2 id="lb-list">Einträge</h2>
        {loaded && entries.length === 0 ? (
          <p className="muted">Noch keine Einträge.</p>
        ) : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>Datum</th><th>Status</th><th>Flug</th><th>Strecke</th><th>Flugzeug</th><th>Notiz</th><th><span className="sr-only">Aktionen</span></th></tr></thead>
              <tbody>
                {entries.map((e) => (
                  <tr key={e.id}>
                    <td className="mono">{dateShort(e.date)}</td>
                    <td>{e.status === "flown" ? "Geflogen" : "Geplant"}</td>
                    <td>{[e.airline, e.flightNumber].filter(Boolean).join(" ") || "–"}</td>
                    <td>{e.origin && e.destination ? `${e.origin} → ${e.destination}` : "–"}</td>
                    <td>{aircraftName(e.aircraftTypeId)}</td>
                    <td className="small">{e.notes}</td>
                    <td><button className="btn danger small" type="button" onClick={() => setEntries((prev) => prev.filter((x) => x.id !== e.id))}>Löschen</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}
