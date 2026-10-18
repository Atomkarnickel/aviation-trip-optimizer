"use client";

import { useState } from "react";
import { aircraftName } from "@/lib/aircraft";
import { AIRCRAFT_STATUS_LABEL, PRICE_SOURCE_LABEL, dateShort, dateTime, duration, money, timeLocal } from "@/lib/format";
import type { Leg, ScoredItinerary } from "@/lib/types";

function LegView({ leg }: { leg: Leg }) {
  return (
    <div className="leg">
      <h3>
        {leg.direction === "outbound" ? "Hinflug" : leg.direction === "inbound" ? "Rückflug" : "Weiterflug"} ·{" "}
        <span className="muted">{duration(leg.duration_minutes)}</span>
      </h3>
      {leg.segments.map((s, i) => (
        <div key={i}>
          <div className="segment">
            <div className="mono">
              {dateShort(s.departure_local)}
              <br />
              {timeLocal(s.departure_local)} → {timeLocal(s.arrival_local)}
              {s.arrival_local.slice(0, 10) !== s.departure_local.slice(0, 10) && <sup title="Ankunft an einem anderen Tag"> +{Math.round((Date.parse(s.arrival_local.slice(0, 10)) - Date.parse(s.departure_local.slice(0, 10))) / 86400000)}</sup>}
            </div>
            <div>
              <strong>
                {s.origin} → {s.destination}
              </strong>{" "}
              · {s.marketing_carrier} {s.flight_number}
              {s.marketing_carrier_name ? ` (${s.marketing_carrier_name})` : ""}
              {s.operating_carrier && s.operating_carrier !== s.marketing_carrier ? ` · durchgeführt von ${s.operating_carrier_name ?? s.operating_carrier}` : ""}
              <br />
              <span className="muted">
                {s.aircraft_type_id ? aircraftName(s.aircraft_type_id) : s.aircraft_name ?? "Flugzeugtyp unbekannt"} ·{" "}
                {AIRCRAFT_STATUS_LABEL[s.aircraft_status ?? "unknown"]} · {duration(s.duration_minutes)}
              </span>
            </div>
          </div>
          {leg.connections?.[i] && (
            <div className={`connection risk-${leg.connections[i].risk}`}>
              Umstieg in {leg.connections[i].airport}: {duration(leg.connections[i].minutes)}
              {leg.connections[i].note ? ` – ${leg.connections[i].note}` : ""}
            </div>
          )}
        </div>
      ))}
    </div>
  );
}

interface Props {
  item: ScoredItinerary;
  favorite: boolean;
  onToggleFavorite: () => void;
  compareSelected: boolean;
  onToggleCompare: () => void;
  compareDisabled: boolean;
  betterAlternativeRank?: number;
}

export function ResultCard({ item, favorite, onToggleFavorite, compareSelected, onToggleCompare, compareDisabled, betterAlternativeRank }: Props) {
  const [open, setOpen] = useState(item.rank <= 1);
  const it = item.itinerary;
  const out = it.legs[0];
  const headingId = `res-${it.id}`;
  return (
    <article className="panel result" aria-labelledby={headingId}>
      <div className="result-head">
        <div className="rank" aria-label={`Rang ${item.rank}`}>{item.rank}</div>
        <div style={{ minWidth: 0 }}>
          <h3 id={headingId} style={{ marginBottom: 2 }}>
            {it.origin} ⇄ {it.destination} · {dateShort(it.outbound_date)} – {dateShort(it.return_date)}
          </h3>
          <div className="result-meta">
            <span>{it.stops === 0 ? "Nonstop" : `${it.stops} Umstieg(e)`}</span>
            <span>{duration(it.total_travel_minutes)} Flugreisezeit</span>
            <span>{it.full_stay_days} volle Tage vor Ort</span>
            <span>{it.airlines.join(", ")} {out.segments[0].flight_number && `· ${out.segments[0].marketing_carrier} ${out.segments[0].flight_number}`}</span>
          </div>
          <div className="row" style={{ gap: 6, marginTop: 6 }}>
            {it.is_demo && <span className="chip demo">DEMO</span>}
            {it.price_source === "test_offer" && <span className="chip demo">TESTMODUS</span>}
            {item.wishlist_hits.map((id) => (
              <span key={id} className="chip wish">★ {aircraftName(id)}</span>
            ))}
            {it.aircraft_type_ids
              .filter((id) => !item.wishlist_hits.includes(id))
              .map((id) => (
                <span key={id} className="chip">{aircraftName(id)}</span>
              ))}
          </div>
        </div>
        <div className="score">
          <div className="price">{money(it.price.total, it.price.currency)}</div>
          <div className="small muted">
            {it.price.travelers > 1 ? `${money(it.price.per_traveler, it.price.currency)} p. P. · ` : ""}
            {item.extra_cost_vs_cheapest > 0 ? `+${money(item.extra_cost_vs_cheapest, it.price.currency)}` : "günstigste"}
          </div>
          <div className="score-value" aria-label={`Score ${Math.round(item.total_score)} von 100`}>{Math.round(item.total_score)}</div>
        </div>
      </div>

      {open && (
        <div className="result-body">
          <p style={{ margin: 0 }}>{item.explanation.summary}</p>
          <div className="grid-2">
            <div>
              <h3>Warum diese Reise</h3>
              <ul className="plain">
                {item.explanation.reasons.map((r, i) => <li key={i}>{r}</li>)}
              </ul>
            </div>
            <div>
              <h3>Kompromisse</h3>
              <ul className="plain">
                {item.explanation.tradeoffs.map((r, i) => <li key={i}>{r}</li>)}
              </ul>
              {item.explanation.better_value_note && (
                <p className="small" style={{ marginTop: 6 }}>
                  💡 {item.explanation.better_value_note}
                  {betterAlternativeRank ? ` (siehe Rang ${betterAlternativeRank})` : ""}
                </p>
              )}
            </div>
          </div>

          <div>
            <h3>Bewertung</h3>
            <div className="bars">
              {item.components.map((c) => (
                <div className="bar" key={c.key} title={c.detail}>
                  <span>{c.label} <span className="muted small">×{c.weight}</span></span>
                  <div className="bar-track" role="meter" aria-label={c.label} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(c.score * 100)}>
                    <div className="bar-fill" style={{ width: `${Math.round(c.score * 100)}%` }} />
                  </div>
                  <span className="mono small">+{c.contribution.toFixed(1)}</span>
                </div>
              ))}
            </div>
          </div>

          <div className="grid-2">
            {it.legs.map((leg, i) => <LegView key={i} leg={leg} />)}
          </div>

          <div className="grid-2">
            <div>
              <h3>Preis</h3>
              <dl className="kv small">
                <dt>Gesamt</dt><dd>{money(it.price.total, it.price.currency)} für {it.price.travelers} Reisende(n)</dd>
                {it.price.base != null && (<><dt>Grundpreis</dt><dd>{money(it.price.base, it.price.currency)}</dd></>)}
                {it.price.taxes != null && (<><dt>Steuern & Gebühren</dt><dd>{money(it.price.taxes, it.price.currency)}</dd></>)}
                <dt>Gepäck</dt><dd>{it.price.baggage_note ?? "keine Angabe"}</dd>
                <dt>Preisquelle</dt><dd>{PRICE_SOURCE_LABEL[it.price_source]} ({it.provider})</dd>
                <dt>Abgerufen</dt><dd>{dateTime(it.fetched_at)}</dd>
                {it.offer_expires_at && (<><dt>Angebot gültig bis</dt><dd>{dateTime(it.offer_expires_at)}</dd></>)}
                {item.aircraft_premium != null && (<><dt>Wunschflugzeug-Aufpreis</dt><dd>{money(item.aircraft_premium, it.price.currency)}</dd></>)}
              </dl>
            </div>
            <div>
              <h3>Unsicherheiten</h3>
              <ul className="plain small">
                {[...item.explanation.uncertainties, ...it.data_notes].filter((x, i, arr) => arr.indexOf(x) === i).map((u, i) => <li key={i}>{u}</li>)}
              </ul>
            </div>
          </div>
        </div>
      )}

      <div className="result-actions">
        <button className="btn secondary small" type="button" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
          {open ? "Details ausblenden" : "Details & Begründung"}
        </button>
        <button className="btn secondary small" type="button" aria-pressed={favorite} onClick={onToggleFavorite}>
          {favorite ? "★ Gespeichert" : "☆ Merken"}
        </button>
        <label className="check small">
          <input type="checkbox" checked={compareSelected} disabled={compareDisabled && !compareSelected} onChange={onToggleCompare} />
          Vergleichen
        </label>
        <span style={{ flex: 1 }} />
        {it.booking_options.length > 0 ? (
          it.booking_options.map((b) => (
            <a key={b.url} className="btn small" href={b.url} target="_blank" rel="noopener noreferrer" title={b.label}>
              {b.is_deep_link ? "Buchen" : "Zur Airline-Website"} ↗
            </a>
          ))
        ) : (
          <span className="small muted">{it.is_demo ? "Demo – nicht buchbar" : "Kein verifizierter Buchungslink"}</span>
        )}
      </div>
    </article>
  );
}
