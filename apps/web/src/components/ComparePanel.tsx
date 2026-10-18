import type { CompareResponse, ScoredItinerary } from "@/lib/types";

export function ComparePanel({ data, items, onClose }: { data: CompareResponse; items: ScoredItinerary[]; onClose: () => void }) {
  return (
    <section className="panel stack" aria-labelledby="cmp-title">
      <div className="row" style={{ justifyContent: "space-between" }}>
        <h2 id="cmp-title" style={{ margin: 0 }}>Vergleich</h2>
        <button className="btn secondary small" type="button" onClick={onClose}>Schließen</button>
      </div>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th scope="col">Kriterium</th>
              {data.itinerary_ids.map((id) => {
                const it = items.find((i) => i.itinerary.id === id);
                return <th key={id} scope="col">Rang {it?.rank ?? "?"} · {it?.itinerary.destination}</th>;
              })}
            </tr>
          </thead>
          <tbody>
            {data.rows.map((row) => (
              <tr key={row.label}>
                <th scope="row">{row.label}</th>
                {row.values.map((v, i) => (
                  <td key={i} className={row.best_index === i ? "best" : undefined}>
                    {v}
                    {row.best_index === i && <span className="sr-only"> (bester Wert)</span>}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}
