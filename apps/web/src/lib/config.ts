export const APP_NAME = "Aviation Trip Optimizer";
export const APP_VERSION = process.env.NEXT_PUBLIC_APP_VERSION || "0.0.0";
export const BUILD_COMMIT = process.env.NEXT_PUBLIC_BUILD_COMMIT || "local";
export const REPOSITORY = process.env.NEXT_PUBLIC_REPOSITORY || "";
export const APK_PREFIX = process.env.NEXT_PUBLIC_APK_PREFIX || "aviation-optimizer-";
export const RELEASE_CACHE_SECONDS = Number(process.env.NEXT_PUBLIC_RELEASE_CACHE_SECONDS || "900");
export const DEFAULT_API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL || "";

export const RELEASES_PAGE_URL = REPOSITORY ? `https://github.com/${REPOSITORY}/releases` : "";
export const REPOSITORY_URL = REPOSITORY ? `https://github.com/${REPOSITORY}` : "";
