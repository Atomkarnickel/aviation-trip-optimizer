"use client";

import { useState } from "react";
import { aircraftName } from "@/lib/aircraft";
import type { ApiClient, ApiError } from "@/lib/api";
import { dateShort } from "@/lib/format";
import type { AircraftHistory } from "@/lib/types";

interface Props {
  api: ApiClient;
  carrier: string;
  flightNumber: string;
  origin: string;
  wishlist: string[];
}

/** Lädt auf Knopfdruck, welche Flugzeugtypen diese Flugnummer in den letzten Tagen tatsächlich geflogen sind. */
export function AircraftHistoryPanel({ api, carrier, flightNumber, origin, wishlist }: Props) {
  const [state, setState] = useState<{ loading: boolean; data: AircraftHistory | null; error: string | null }>({
    loading: false,
    data: null,
    error: null,
  });

  async function load() {
    setState({ loading: true, data: null, error: null });
    try {
      setState({ loading: false, data: await api.aircraftHistory(carrier, flightNumber, origin, 5), error: null });
    } catch (e) {
      setState({ loading: false, data: null, error: (e as ApiError).message });
    }
  }

  const d = state.data;
  return (
    <div className="history">
      {!d && (
        <button className="btn ghost small" type="button" onClick={load} disabled={state.loading}>
          {state.loading ? "Prüfe beobachtete Flüge …" : `Typverlauf ${carrier} ${flightNumber} prüfen`}
        </button>
      )}
      {state.error && <p className="small" role="alert" style={{ color: "var(--danger)" }}>{state.error}</p>}
      {d && (
        <div className="small" role="status">
          {d.status === "ok" ? (
            <>
              <strong>
                {d.callsign} ab {d.origin}, letzte {d.days_checked} Tage:
              </strong>{" "}
              {d.distribution.map((t, i) => (
                <span key={i} className={`chip ${t.aircraft_type_id && wishlist.includes(t.aircraft_type_id) ? "wish" : ""}`} style={{ marginRight: 4 }}>
                  {t.aircraft_type_id ? aircraftName(t.aircraft_type_id) : t.name} · {t.count}× ({Math.round(t.share * 100)} %)
                </span>
              ))}
              <details>
                <summary>Einzelbeobachtungen</summary>
                <ul className="plain">
                  {d.observations.map((o, i) => (
                    <li key={i} className="mono">
                      {dateShort(o.date)} · {o.registration ?? o.icao24} · {o.type_code ?? "Typ unbekannt"}
                    </li>
                  ))}
                </ul>
              </details>
              <span className="muted">Historisch beobachtet – keine Garantie für deinen Flugtag. Quelle: {d.source}.</span>
            </>
          ) : (
            <span className="muted">{d.message ?? "Keine Daten."}</span>
          )}
        </div>
      )}
    </div>
  );
}
