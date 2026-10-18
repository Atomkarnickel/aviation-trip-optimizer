import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const root = resolve(process.cwd(), "../..");
const version = readFileSync(resolve(root, "VERSION"), "utf8").trim();
const releaseConfig = JSON.parse(readFileSync(resolve(root, "shared/release-config.json"), "utf8"));

// Für GitHub Pages wird die App unter /<repo>/ ausgeliefert (NEXT_PUBLIC_BASE_PATH im Deploy-Workflow).
const basePath = process.env.NEXT_PUBLIC_BASE_PATH || "";

/** @type {import('next').NextConfig} */
const nextConfig = {
  output: "export",
  basePath,
  trailingSlash: true,
  images: { unoptimized: true },
  reactStrictMode: true,
  env: {
    NEXT_PUBLIC_APP_VERSION: version,
    NEXT_PUBLIC_BUILD_COMMIT: process.env.GITHUB_SHA ? process.env.GITHUB_SHA.slice(0, 7) : "local",
    NEXT_PUBLIC_REPOSITORY: releaseConfig.repository,
    NEXT_PUBLIC_APK_PREFIX: releaseConfig.apkAssetPrefix,
    NEXT_PUBLIC_RELEASE_CACHE_SECONDS: String(releaseConfig.releaseCacheSeconds),
    NEXT_PUBLIC_API_BASE_URL: process.env.NEXT_PUBLIC_API_BASE_URL || "",
    NEXT_PUBLIC_BASE_PATH: basePath,
  },
};

export default nextConfig;
