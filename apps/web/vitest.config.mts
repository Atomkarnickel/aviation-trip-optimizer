import react from "@vitejs/plugin-react";
import { resolve } from "node:path";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [react()],
  resolve: { alias: { "@": resolve(__dirname, "src") } },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
    env: { NEXT_PUBLIC_APP_VERSION: "1.0.0", NEXT_PUBLIC_REPOSITORY: "Atomkarnickel/aviation-trip-optimizer",
           NEXT_PUBLIC_APK_PREFIX: "aviation-optimizer-", NEXT_PUBLIC_RELEASE_CACHE_SECONDS: "900" },
  },
});
