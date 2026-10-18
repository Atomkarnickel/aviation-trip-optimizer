"use client";

import { useEffect, useState } from "react";
import { AIRCRAFT_SOURCE } from "@/lib/aircraft";
import { APP_NAME, APP_VERSION, BUILD_COMMIT, RELEASES_PAGE_URL, REPOSITORY_URL } from "@/lib/config";
import { bytes, dateShort, dateTime } from "@/lib/format";
import { checkReleases, type ReleaseCheck } from "@/lib/releases";

export default function AboutPage() {
  const [check, setCheck] = useState<ReleaseCheck | null>(null);
  const [loading, setLoading] = useState(true);

  const load = (force = false) => {
    setLoading(true);
    checkReleases({ force }).then((c) => {
      setCheck(c);
      setLoading(false);
    });
  };
  useEffect(() => load(), []);

  const latest = check?.status === "ok" ? check.latest : null;

  return (
    <div className="stack">
      <div className="page-head">
        <div>
          <h1>Über diese App</h1>
          <p className="muted">{APP_NAME} – Flugreisen für Flugzeugenthusiasten optimieren.</p>
        </div>
      </div>

      <section className="panel stack" aria-labelledby="ab-web">
        <h2 id="ab-web">Webversion (läuft gerade)</h2>
        <dl className="kv">
          <dt>App</dt><dd>{APP_NAME}</dd>
          <dt>Web-App-Version</dt><dd className="mono">{APP_VERSION} (Build {BUILD_COMMIT})</dd>
        </dl>
      </section>

      <section className="panel stack" aria-labelledby="ab-android" aria-busy={loading}>
        <div className="row" style={{ justifyContent: "space-between" }}>
          <h2 id="ab-android" style={{ margin: 0 }}>Android-App (APK)</h2>
          <button className="btn secondary small" type="button" onClick={() => load(true)} disabled={loading}>
            {loading ? "Prüfe …" : "Erneut prüfen"}
          </button>
        </div>
        <p className="small muted">Die APK ist nur für Android-Geräte gedacht. Am Desktop kannst du sie herunterladen und aufs Smartphone übertragen.</p>
        {loading && !check && <div className="skeleton" style={{ height: 60 }} />}
        {check && check.status !== "ok" && (
          <div className="banner info" role="status">
            <div>
              <strong>{check.status === "no_release" ? "Noch keine stabile APK veröffentlicht" : "Versionsprüfung nicht möglich"}</strong>
              {check.message}
            </div>
          </div>
        )}
        {latest && check?.status === "ok" && (
          <>
            {check.stale && <p className="small muted">GitHub derzeit nicht erreichbar – zwischengespeicherter Stand.</p>}
            <dl className="kv">
              <dt>Neueste stabile Android-Version</dt><dd className="mono">{latest.version}</dd>
              <dt>Veröffentlicht</dt><dd>{dateShort(latest.publishedAt)}</dd>
              <dt>Dateiname</dt><dd className="mono">{latest.apkName} ({bytes(latest.apkSize)})</dd>
              <dt>Prüfsumme (SHA-256)</dt>
              <dd>{latest.checksumUrl ? <a href={latest.checksumUrl} rel="noopener noreferrer">{latest.apkName}.sha256</a> : "nicht verfügbar"}</dd>
              <dt>Änderungen</dt><dd>{latest.summary || "–"}</dd>
            </dl>
            <div className="row">
              <a className="btn" href={latest.apkUrl} rel="noopener noreferrer">⬇ Neueste APK herunterladen (v{latest.version})</a>
              <a className="btn secondary" href={latest.htmlUrl} target="_blank" rel="noopener noreferrer">Release Notes</a>
            </div>
            <div className="banner info small">
              <div>
                <strong>Installation</strong>
                Android fragt beim Öffnen der APK ggf. nach der Erlaubnis „Unbekannte Apps installieren“ für Browser oder Dateimanager. Die Installation
                bestätigst du selbst. Prüfe vorher optional die SHA-256-Prüfsumme. Updates werden nur akzeptiert, wenn sie mit demselben Schlüssel signiert sind.
              </div>
            </div>
            {check.releases.length > 1 && (
              <details>
                <summary>Frühere stabile Versionen</summary>
                <ul className="plain small">
                  {check.releases.slice(1, 5).map((r) => (
                    <li key={r.tag}>
                      <a href={r.apkUrl} rel="noopener noreferrer">v{r.version}</a> · {dateShort(r.publishedAt)} {r.summary && `– ${r.summary}`}
                    </li>
                  ))}
                </ul>
              </details>
            )}
            <p className="small muted">Geprüft: {dateTime(check.checkedAt)}</p>
          </>
        )}
        {RELEASES_PAGE_URL && <p><a href={RELEASES_PAGE_URL} target="_blank" rel="noopener noreferrer">Alle Releases auf GitHub ↗</a></p>}
      </section>

      <section className="panel stack" aria-labelledby="ab-project">
        <h2 id="ab-project">Projekt, Daten & Lizenz</h2>
        <dl className="kv">
          <dt>Quellcode</dt><dd>{REPOSITORY_URL ? <a href={REPOSITORY_URL} target="_blank" rel="noopener noreferrer">{REPOSITORY_URL}</a> : "–"}</dd>
          <dt>Lizenz</dt><dd>MIT (siehe LICENSE im Repository)</dd>
          <dt>Flugdaten</dt><dd>Kostenlose, zwischengespeicherte Preise von Travelpayouts/Aviasales (als geschätzt gekennzeichnet); Typverlauf aus OpenSky Network und hexdb.io. Ohne Provider werden keine Ergebnisse angezeigt.</dd>
          <dt>Flugzeugkatalog</dt><dd>{AIRCRAFT_SOURCE.source} Stand {AIRCRAFT_SOURCE.updated}.</dd>
          <dt>Flugzeugtypen</dt><dd>Typen sind laut Flugplan geplant und nicht garantiert. Airlines können kurzfristig Geräte tauschen.</dd>
          <dt>Datenschutz</dt><dd>Kein Konto, kein Tracking. Einstellungen, Favoriten und Logbuch liegen nur lokal im Browser. Die Versionsprüfung ruft die öffentliche GitHub-API auf.</dd>
        </dl>
      </section>
    </div>
  );
}
