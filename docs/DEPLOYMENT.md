# Deployment

## Webversion (GitHub Pages)

- Workflow: `.github/workflows/deploy-web.yml` – bei jedem Push auf `main` (relevante Pfade), manuell und nach jedem stabilen Release.
- Ablauf: `npm ci` → Typprüfung → Lint → Tests → statischer Produktionsbuild (`next build`, `output: export`) → Upload → Deploy → Erreichbarkeitsprüfung per HTTP. Schlägt ein Schritt fehl, wird nichts veröffentlicht.
- Einrichtung (einmalig, bereits erledigt): *Settings → Pages → Source: GitHub Actions* bzw. `gh api -X POST repos/<owner>/<repo>/pages -f build_type=workflow`.
- Die Web-App ist rein clientseitig. Sie spricht dasselbe Backend wie die Android-App an. Die Backend-URL kommt aus der Repository-Variable `API_BASE_URL` (Build-Zeit, kein Secret) oder wird vom Nutzer im Profil hinterlegt.

## Backend

Das Backend ist ein Docker-Container (`backend/Dockerfile`, Build-Kontext = Repository-Root). Datenbankmigrationen laufen beim Start automatisch (Alembic `upgrade head`).

### Variante A: Render (Blueprint `render.yaml`)

1. Konto bei <https://render.com> anlegen, Repository verbinden.
2. *New → Blueprint* → `render.yaml` auswählen. Erstellt Web-Service + PostgreSQL.
3. Im Dashboard das Secret `DUFFEL_ACCESS_TOKEN` setzen.
4. Nach dem Deploy die URL (z. B. `https://aviation-trip-optimizer-api.onrender.com`) prüfen: `/api/v1/health`, `/docs`.
5. URL als Repository-Variable setzen, damit Web und APK sie standardmäßig verwenden:
   `gh variable set API_BASE_URL --body https://…` und Web-Deploy erneut auslösen (`gh workflow run "Deploy Web (GitHub Pages)"`).

Hinweis: Kostenlose Render-Instanzen schlafen bei Inaktivität ein (erste Anfrage dauert länger); kostenlose Postgres-Instanzen sind zeitlich begrenzt – für Dauerbetrieb einen bezahlten Tarif wählen.

### Variante B: beliebiger Container-Host (Fly.io, Railway, eigener Server)

```bash
docker build -f backend/Dockerfile -t aviation-backend .
docker run -p 8000:8000 \
  -e DATABASE_URL=postgresql://user:pass@host:5432/aviation \
  -e DUFFEL_ACCESS_TOKEN=duffel_test_... \
  -e CORS_ORIGINS=https://atomkarnickel.github.io \
  aviation-backend
```

HTTPS terminiert der Hosting-Anbieter bzw. ein Reverse-Proxy. Die Apps akzeptieren nur HTTPS-Backends.

### Umgebungsvariablen

| Variable | Pflicht | Beschreibung |
|---|---|---|
| `DATABASE_URL` | prod | PostgreSQL-URL (`postgres://`/`postgresql://` werden normalisiert). Standard: SQLite-Datei |
| `DUFFEL_ACCESS_TOKEN` | für Live-Suche | Duffel-Token. Ohne Token: Status `provider_not_configured` |
| `DEMO_MODE` | nein | `true` = synthetische, gekennzeichnete Demodaten (nur Entwicklung/Vorführung) |
| `CORS_ORIGINS` | ja | Kommagetrennte erlaubte Origins (z. B. die GitHub-Pages-Domain) |
| `GITHUB_TOKEN` | nein | erhöht das GitHub-API-Limit für `/api/v1/releases` |
| `SEARCH_RATE_LIMIT_PER_MINUTE` | nein | Standard 20 Suchen/Minute/IP |
| `MAX_PROVIDER_CALLS_PER_SEARCH` | nein | Standard 24 |
| `SEARCH_CACHE_TTL_SECONDS` | nein | Standard 900 |

Für mehrere Instanzen sollten Rate-Limit und In-Flight-Deduplizierung auf Redis umgestellt werden (derzeit prozesslokal).
