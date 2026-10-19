// Kopiert gemeinsame Daten (Flugzeugkatalog, Flughäfen, Testvektoren) aus dem Backend in die Web-App, damit sie auch
// ohne verbundenes Backend verfügbar sind. Quelle der Wahrheit bleibt backend/app/data/ bzw. shared/.
import { copyFileSync, mkdirSync } from "node:fs";
import { resolve } from "node:path";

const root = resolve(import.meta.dirname, "../../..");
const target = resolve(import.meta.dirname, "../src/generated");
mkdirSync(target, { recursive: true });
copyFileSync(resolve(root, "backend/app/data/aircraft_types.json"), resolve(target, "aircraft_types.json"));
copyFileSync(resolve(root, "backend/app/data/airports.json"), resolve(target, "airports.json"));
copyFileSync(resolve(root, "shared/test-vectors/semver.json"), resolve(target, "semver-vectors.json"));
copyFileSync(resolve(root, "shared/test-vectors/exports.json"), resolve(target, "export-vectors.json"));
console.log("Gemeinsame Daten synchronisiert.");
