"use client";

import type { Weights } from "@/lib/types";

export const WEIGHT_LABELS: Record<keyof Weights, string> = {
  price: "Preis",
  aircraft: "Flugzeugtyp / Coolness",
  travel_time: "Reisezeit",
  stops: "Anzahl Umstiege",
  stay: "Aufenthaltsdauer",
  airline: "Airline-Präferenzen",
  comfort: "Komfort & Umsteigequalität",
};

export const MODE_DEFAULTS: Record<string, Weights> = {
  best_value: { price: 5, aircraft: 4, travel_time: 3, stops: 3, stay: 3, airline: 1, comfort: 2 },
  aircraft_hunter: { price: 2, aircraft: 9, travel_time: 2, stops: 1, stay: 2, airline: 1, comfort: 1 },
  cheapest: { price: 10, aircraft: 0, travel_time: 1, stops: 1, stay: 1, airline: 0, comfort: 1 },
};

export function WeightsEditor({ value, onChange }: { value: Weights; onChange: (w: Weights) => void }) {
  const total = Object.values(value).reduce((a, b) => a + (b ?? 0), 0) || 1;
  return (
    <div className="stack" style={{ gap: 8 }}>
      {(Object.keys(WEIGHT_LABELS) as (keyof Weights)[]).map((k) => (
        <div key={k} className="bar" style={{ gridTemplateColumns: "190px 1fr 70px" }}>
          <label htmlFor={`w-${k}`}>{WEIGHT_LABELS[k]}</label>
          <input id={`w-${k}`} type="range" min={0} max={10} step={1} value={value[k] ?? 0} onChange={(e) => onChange({ ...value, [k]: Number(e.target.value) })} />
          <span className="mono small">{value[k]} · {Math.round(((value[k] ?? 0) / total) * 100)}%</span>
        </div>
      ))}
    </div>
  );
}
