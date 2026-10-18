# Deployment (kostenlos)

| Teil | Hosting | Kosten |
|---|---|---|
| Webversion | GitHub Pages | kostenlos (öffentliches Repo) |
| APK | GitHub Releases | kostenlos |
| CI/CD | GitHub Actions | kostenlos (öffentliches Repo) |
| Backend | Render Free Web Service (Docker) | kostenlos – schläft nach ca. 15 min Inaktivität |
| Datenbank | SQLite im Container | kostenlos – nur Cache, wird bei Neustart geleert |

## Webversion (GitHub Pages)

- Workflow: `.github/workflows/deploy-web.yml` – bei Push auf `main` (relevante Pfade), manuell und nach jedem stabilen Release.
- Ablauf: `npm ci` → Typprüfung → Lint → Tests → statischer Build → Upload → Deploy → HTTP-Erreichbarkeitsprüfung. Bei Fehlern wird nichts veröffentlicht.
- Einrichtung (bereits erledigt): Pages-Quelle „GitHub Actions“; Release-Tags `v*` dürfen nach `github-pages` deployen.
- Die Backend-URL kommt aus der Repository-Variable `API_BASE_URL` (öffentlich, kein Secret) oder wird im Profil hinterlegt.

## Backend auf Render (kostenlos)

Warum SQLite: Die Datenbank enthält nur Caches (Suchergebnisse 15 min, OpenSky-Abflüge, Flugzeugregister) und die bei jedem Start neu geladenen Referenzdaten. Geht sie bei einem Neustart verloren, werden die Daten einfach neu abgefragt. Die kostenlose Render-Postgres-Datenbank läuft dagegen nach 30 Tagen ab und wird deshalb nicht genutzt.

1. Konto auf <https://render.com> anlegen (kostenlos, ohne Zahlungsdaten für den Free-Tarif).
2. *New → Blueprint* → dieses Repository → `render.yaml` übernehmen (Plan `free`).
3. Im Dashboard die Secrets setzen: `TRAVELPAYOUTS_TOKEN`, optional `TRAVELPAYOUTS_MARKER`, `OPENSKY_CLIENT_ID`, `OPENSKY_CLIENT_SECRET` (siehe `docs/PROVIDERS.md`).
4. Nach dem ersten Deploy prüfen: `https://<dienst>.onrender.com/api/v1/health` und `/docs`.
5. URL als Voreinstellung für Web und APK hinterlegen:
   ```bash
   gh variable set API_BASE_URL --body https://<dienst>.onrender.com
   gh workflow run "Deploy Web (GitHub Pages)"
   ```
   (Die APK übernimmt den Wert beim nächsten Release; Nutzer können die URL jederzeit im Profil ändern.)

**Aufwachen:** Nach Inaktivität braucht die erste Anfrage 30–60 s. Web und Android zeigen dann „Backend wird gestartet …“ statt eines Fehlers; die Timeouts sind dafür auf 90 s ausgelegt.

## Alternative: eigener Rechner / Container

```bash
docker build -f backend/Dockerfile -t aviation-backend .
docker run -p 8000:8000 -e TRAVELPAYOUTS_TOKEN=... -e OPENSKY_CLIENT_ID=... -e OPENSKY_CLIENT_SECRET=... \
  -e CORS_ORIGINS=https://atomkarnickel.github.io aviation-backend
```

Für Zugriff aus der Web-App braucht das Backend HTTPS (z. B. kostenloser Cloudflare-Tunnel).

## Umgebungsvariablen

| Variable | Beschreibung |
|---|---|
| `TRAVELPAYOUTS_TOKEN` | kostenlose Preisdaten (Standard-Provider) |
| `TRAVELPAYOUTS_MARKER` | optional, Partner-ID für Aviasales-Links |
| `OPENSKY_CLIENT_ID`, `OPENSKY_CLIENT_SECRET` | kostenloser OpenSky-API-Client für den Typverlauf |
| `AIRCRAFT_HISTORY_ENABLED` | `false` schaltet den Typverlauf ab |
| `DUFFEL_ACCESS_TOKEN` | optional (Sandbox kostenlos, echte Angebote nicht) |
| `FLIGHT_PROVIDER` | `auto` (Standard), `travelpayouts`, `duffel`, `demo` |
| `DEMO_MODE` | `true` = synthetische, gekennzeichnete Demodaten |
| `DATABASE_URL` | Standard SQLite; PostgreSQL möglich |
| `CORS_ORIGINS` | erlaubte Origins (GitHub-Pages-Domain) |
| `GITHUB_TOKEN` | optional, höheres GitHub-API-Limit für `/api/v1/releases` |
| `SEARCH_RATE_LIMIT_PER_MINUTE` | Standard 20 |
| `AIRCRAFT_HISTORY_RATE_LIMIT_PER_MINUTE` | Standard 6 |
