# Entwicklung

Voraussetzungen: Python 3.12, Node.js 22, JDK 17, Android SDK (Plattform 35). Unter Windows funktioniert alles in Git Bash.

## Backend

```bash
cd backend
python -m venv .venv
.venv/Scripts/pip install -r requirements-dev.txt     # Linux/macOS: .venv/bin/pip
cp .env.example .env                                   # optional
DEMO_MODE=true .venv/Scripts/uvicorn app.main:app --reload
# http://localhost:8000/docs  (OpenAPI/Swagger)
.venv/Scripts/python -m pytest
.venv/Scripts/ruff check app tests ../scripts
```

Nach Schemaänderungen:

```bash
.venv/Scripts/alembic revision --autogenerate -m "beschreibung"   # Migration erzeugen und prüfen
.venv/Scripts/python -c "import json; from app.main import create_app; json.dump(create_app().openapi(), open('../shared/openapi.json','w',encoding='utf-8'), ensure_ascii=False, indent=1)"
cd ../apps/web && npm run gen:api                                   # TS-Typen neu generieren
```

## Web

```bash
cd apps/web
npm ci
NEXT_PUBLIC_API_BASE_URL=http://localhost:8000 npm run dev   # http://localhost:3000
npm test && npm run typecheck && npm run lint && npm run build
```

## Android

```bash
cd apps/android
echo "sdk.dir=/pfad/zum/android-sdk" > local.properties
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:assembleDebug -PapiBaseUrl=http://10.0.2.2:8000   # Emulator → lokales Backend
```

Debug-Builds haben die Paket-ID `com.atomkarnickel.aviationoptimizer.debug` und können parallel zur Release-App installiert werden.

## Gemeinsame Testvektoren

Logik, die auf Web und Android identisch sein muss, wird gegen Dateien in `shared/test-vectors/` getestet:

| Datei | Inhalt | Erzeugt von |
|---|---|---|
| `semver.json` | Versionsvergleich, Update-Status | von Hand gepflegt |
| `exports.json` | Logbuch-Statistik, Logbuch-CSV, Kalender-Export (.ics, byte-genau) | `python scripts/gen_export_vectors.py` (Python-Referenzimplementierung) |

Nach einer Änderung an Statistik, CSV- oder ICS-Format zuerst die Referenz in `scripts/gen_export_vectors.py` anpassen, die Datei neu erzeugen und dann Web (`src/lib/exports.ts`) und Android (`domain/Exports.kt`) nachziehen. Der Backend-Test `test_export_vectors.py` schlägt fehl, wenn die Datei nicht zur Referenz passt.
