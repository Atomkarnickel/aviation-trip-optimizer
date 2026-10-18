"use client";

import { AircraftPreferences } from "@/components/AircraftPreferences";
import { aircraftName } from "@/lib/aircraft";
import { PRICE_SOURCE_LABEL, dateShort, dateTime, money } from "@/lib/format";
import { useFavorites, useSettings } from "@/lib/settings";

export default function FavoritesPage() {
  const { favorites, remove, loaded } = useFavorites();
  const [settings, setSettings] = useSettings();
  return (
    <div className="stack">
      <div className="page-head">
        <div>
          <h1>Favoriten</h1>
          <p className="muted">Gespeicherte Reisen und markierte Flugzeuge. Lokal in diesem Browser gespeichert.</p>
        </div>
      </div>

      <section className="stack" aria-labelledby="fav-trips">
        <h2 id="fav-trips">Gemerkte Reisen</h2>
        {loaded && favorites.length === 0 && <p className="muted">Noch keine Reisen gemerkt. Nutze „☆ Merken“ in den Suchergebnissen.</p>}
        {favorites.map((f) => (
          <article key={f.itinerary.id} className="panel stack" style={{ gap: 6 }}>
            <div className="row" style={{ justifyContent: "space-between" }}>
              <h3 style={{ margin: 0 }}>
                {f.itinerary.origin} ⇄ {f.itinerary.destination} · {dateShort(f.itinerary.outbound_date)} – {dateShort(f.itinerary.return_date)}
              </h3>
              <strong>{money(f.itinerary.price.total, f.itinerary.price.currency)}</strong>
            </div>
            <p className="small" style={{ margin: 0 }}>{f.summary}</p>
            <div className="row" style={{ gap: 6 }}>
              {f.itinerary.is_demo && <span className="chip demo">DEMO</span>}
              {f.itinerary.aircraft_type_ids.map((id) => <span className="chip" key={id}>{aircraftName(id)}</span>)}
            </div>
            <p className="small muted" style={{ margin: 0 }}>
              Gespeichert {dateTime(f.savedAt)} · Preisstand {dateTime(f.itinerary.fetched_at)} ({PRICE_SOURCE_LABEL[f.itinerary.price_source]}). Preise und Verfügbarkeit können sich inzwischen geändert haben.
            </p>
            <div className="row">
              {f.itinerary.booking_options.map((b) => (
                <a key={b.url} className="btn small" href={b.url} target="_blank" rel="noopener noreferrer">Zur Airline-Website ↗</a>
              ))}
              <button className="btn danger small" type="button" onClick={() => remove(f.itinerary.id)}>Entfernen</button>
            </div>
          </article>
        ))}
      </section>

      <section className="panel stack" aria-labelledby="fav-aircraft">
        <h2 id="fav-aircraft">Markierte Flugzeuge</h2>
        <AircraftPreferences settings={settings} onChange={setSettings} onlyMarked />
      </section>
    </div>
  );
}
