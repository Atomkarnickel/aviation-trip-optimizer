"use client";

import { money } from "@/lib/format";
import type { CalendarCell } from "@/lib/types";

interface Props {
  cells: CalendarCell[];
  selected: { date: string | null; nights: number | null };
  onSelect: (date: string | null, nights: number | null) => void;
}

const WEEKDAY = ["So", "Mo", "Di", "Mi", "Do", "Fr", "Sa"];

function label(iso: string) {
  const d = new Date(`${iso}T12:00:00Z`);
  return { wd: WEEKDAY[d.getUTCDay()], day: `${iso.slice(8, 10)}.${iso.slice(5, 7)}.` };
}

/** Heatmap: günstigster erhaltener Preis je Abflugtag (Spalten) und Aufenthaltsdauer (Zeilen). */
export function PriceCalendar({ cells, selected, onSelect }: Props) {
  if (cells.length === 0) return null;
  const currency = cells[0].currency;
  const dates = Array.from(new Set(cells.map((c) => c.departure_date))).sort();
  const nights = Array.from(new Set(cells.map((c) => c.nights))).sort((a, b) => a - b);
  const byKey = new Map(cells.map((c) => [`${c.departure_date}|${c.nights}`, c]));
  const prices = cells.map((c) => c.min_price);
  const min = Math.min(...prices);
  const max = Math.max(...prices);
  const cheapest = cells.find((c) => c.min_price === min)!;
  const shade = (p: number) => {
    const t = max === min ? 0 : (p - min) / (max - min);
    return `color-mix(in srgb, var(--accent-2) ${Math.round((1 - t) * 65 + 8)}%, var(--surface))`;
  };
  const anySelected = selected.date !== null || selected.nights !== null;

  return (
    <section className="panel stack" aria-labelledby="cal-title" style={{ gap: 10 }}>
      <div className="row" style={{ justifyContent: "space-between" }}>
        <div>
          <h2 id="cal-title" style={{ margin: 0 }}>Preiskalender</h2>
          <p className="small muted" style={{ margin: 0 }}>
            Günstigster erhaltener Preis je Abflugtag und Aufenthaltsdauer. Günstigste Kombination:{" "}
            <strong>{label(cheapest.departure_date).day}, {cheapest.nights} Nächte – {money(cheapest.min_price, currency)}</strong>.
            Leere Felder = keine Angebote, nicht „ausgebucht“.
          </p>
        </div>
        {anySelected && (
          <button className="btn secondary small" type="button" onClick={() => onSelect(null, null)}>Auswahl aufheben</button>
        )}
      </div>
      <div className="table-wrap">
        <table className="heatmap">
          <thead>
            <tr>
              <th scope="col">Nächte</th>
              {dates.map((d) => {
                const l = label(d);
                return (
                  <th key={d} scope="col" className="mono small">
                    {l.wd}
                    <br />
                    {l.day}
                  </th>
                );
              })}
            </tr>
          </thead>
          <tbody>
            {nights.map((n) => (
              <tr key={n}>
                <th scope="row" className="mono">{n}</th>
                {dates.map((d) => {
                  const c = byKey.get(`${d}|${n}`);
                  if (!c) return <td key={d} aria-label="keine Angebote" />;
                  const on = selected.date === d && selected.nights === n;
                  return (
                    <td key={d} style={{ padding: 2 }}>
                      <button
                        type="button"
                        className="heat-cell mono"
                        aria-pressed={on}
                        aria-label={`Abflug ${label(d).day}, ${n} Nächte: ab ${money(c.min_price, currency)}, ${c.offers} Angebot(e)`}
                        style={{ background: shade(c.min_price) }}
                        onClick={() => (on ? onSelect(null, null) : onSelect(d, n))}
                      >
                        {Math.round(c.min_price)}
                      </button>
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="small muted" style={{ margin: 0 }}>Klick auf ein Feld filtert die Ergebnisliste. Preise in {currency}, gesamt für alle Reisenden.</p>
    </section>
  );
}
