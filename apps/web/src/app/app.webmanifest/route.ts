// Web-App-Manifest als statische Route. Bewusst nicht app/manifest.ts: Next 14 verlinkt dieses ohne basePath,
// die App läuft auf GitHub Pages aber unter /<repo>/. Verlinkt wird über metadata.manifest in layout.tsx.
export const dynamic = "force-static";

const base = process.env.NEXT_PUBLIC_BASE_PATH || "";

export function GET() {
  const manifest = {
    name: "Aviation Trip Optimizer",
    short_name: "Trip Optimizer",
    description: "Flugreisen nach Preis, Reisezeit, Aufenthaltsdauer und Flugzeugerlebnis optimieren.",
    lang: "de",
    start_url: `${base}/`,
    scope: `${base}/`,
    display: "standalone",
    background_color: "#0b1a33",
    theme_color: "#0b1a33",
    icons: [{ src: `${base}/icon.svg`, sizes: "any", type: "image/svg+xml", purpose: "any" }],
  };
  return new Response(JSON.stringify(manifest), { headers: { "Content-Type": "application/manifest+json" } });
}
