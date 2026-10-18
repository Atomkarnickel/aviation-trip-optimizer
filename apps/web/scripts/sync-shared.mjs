// Kopiert gemeinsame Daten (Flugzeugkatalog) aus dem Backend in die Web-App, damit der Katalog auch ohne
// verbundenes Backend angezeigt werden kann. Quelle der Wahrheit bleibt backend/app/data/.
import { copyFileSync, mkdirSync } from "node:fs";
import { resolve } from "node:path";

const root = resolve(import.meta.dirname, "../../..");
const target = resolve(import.meta.dirname, "../src/generated");
mkdirSync(target, { recursive: true });
copyFileSync(resolve(root, "backend/app/data/aircraft_types.json"), resolve(target, "aircraft_types.json"));
copyFileSync(resolve(root, "shared/test-vectors/semver.json"), resolve(target, "semver-vectors.json"));
console.log("Gemeinsame Daten synchronisiert.");
