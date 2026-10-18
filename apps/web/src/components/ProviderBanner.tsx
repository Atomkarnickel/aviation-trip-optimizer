import type { ProviderStatus } from "@/lib/types";

export function ProviderBanner({ provider, backendError }: { provider: ProviderStatus | null; backendError: string | null }) {
  if (backendError) {
    return (
      <div className="banner error" role="status">
        <div>
          <strong>Backend nicht verbunden</strong>
          {backendError} Ohne Backend ist keine Flugsuche möglich – es werden keine Ergebnisse vorgetäuscht.
        </div>
      </div>
    );
  }
  if (!provider) return null;
  if (!provider.configured) {
    return (
      <div className="banner warn" role="status">
        <div>
          <strong>Keine Live-Flugdaten konfiguriert</strong>
          {provider.message}
        </div>
      </div>
    );
  }
  if (provider.mode === "demo" || provider.mode === "test") {
    return (
      <div className="banner demo" role="status">
        <div>
          <strong>{provider.mode === "demo" ? "DEMO-MODUS" : "TESTMODUS"}</strong>
          {provider.message}
        </div>
      </div>
    );
  }
  return (
    <div className="banner ok" role="status">
      <div>
        <strong>Live-Daten aktiv ({provider.name})</strong>
        {provider.message}
      </div>
    </div>
  );
}
