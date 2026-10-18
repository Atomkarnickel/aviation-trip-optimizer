"use client";

import { AIRCRAFT, AIRCRAFT_SOURCE, STATUS_LABEL } from "@/lib/aircraft";
import type { AppSettings } from "@/lib/settings";
import type { AircraftPreferenceStatus } from "@/lib/types";

const OPTIONS: { value: AircraftPreferenceStatus; label: string }[] = [
  { value: "neutral", label: "Neutral" },
  { value: "wishlist", label: "Unbedingt erleben" },
  { value: "flown", label: "Schon geflogen" },
  { value: "not_interested", label: "Uninteressant" },
];

export function AircraftPreferences({
  settings,
  onChange,
  onlyMarked = false,
}: {
  settings: AppSettings;
  onChange: (s: AppSettings) => void;
  onlyMarked?: boolean;
}) {
  const list = onlyMarked ? AIRCRAFT.filter((a) => (settings.aircraftStatus[a.id] ?? "neutral") !== "neutral") : AIRCRAFT;
  return (
    <div className="stack" style={{ gap: 10 }}>
      {list.length === 0 && <p className="muted">Noch keine Flugzeuge markiert. Markiere sie im Profil.</p>}
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th scope="col">Flugzeug</th>
              <th scope="col">Status</th>
              <th scope="col">Standard-Coolness</th>
              <th scope="col">Meine Bewertung</th>
              <th scope="col">Meine Markierung</th>
            </tr>
          </thead>
          <tbody>
            {list.map((a) => {
              const status = settings.aircraftStatus[a.id] ?? "neutral";
              const rating = settings.aircraftRating[a.id];
              return (
                <tr key={a.id}>
                  <td>
                    <strong>{a.model.startsWith(a.manufacturer) ? a.model : `${a.manufacturer} ${a.model}`}</strong>
                    <div className="small muted">{a.typical_use} · {a.typical_seats !== "-" ? `${a.typical_seats} Sitze` : "–"}</div>
                    {a.notes && <div className="small muted">{a.notes}</div>}
                  </td>
                  <td><span className={`chip ${a.status === "historical" ? "warn" : ""}`}>{STATUS_LABEL[a.status] ?? a.status}</span></td>
                  <td className="mono">{a.default_coolness}/10</td>
                  <td>
                    <select
                      aria-label={`Eigene Bewertung ${a.model}`}
                      value={rating ?? ""}
                      onChange={(e) => {
                        const next = { ...settings.aircraftRating };
                        if (e.target.value === "") delete next[a.id];
                        else next[a.id] = Number(e.target.value);
                        onChange({ ...settings, aircraftRating: next });
                      }}
                    >
                      <option value="">Standard</option>
                      {Array.from({ length: 11 }, (_, i) => <option key={i} value={i}>{i}</option>)}
                    </select>
                  </td>
                  <td>
                    <select
                      aria-label={`Markierung ${a.model}`}
                      value={status}
                      onChange={(e) => onChange({ ...settings, aircraftStatus: { ...settings.aircraftStatus, [a.id]: e.target.value as AircraftPreferenceStatus } })}
                    >
                      {OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
                    </select>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      <p className="small muted">Quelle: {AIRCRAFT_SOURCE.source} (Stand {AIRCRAFT_SOURCE.updated})</p>
    </div>
  );
}
