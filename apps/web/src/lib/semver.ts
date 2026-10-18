// Gleiche Regeln wie backend/app/services/releases.py und Android (VersionUtils.kt).
// Getestet mit den gemeinsamen Testvektoren in shared/test-vectors/semver.json.

const SEMVER = /^v?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z.-]+))?$/;

export interface ParsedVersion {
  major: number;
  minor: number;
  patch: number;
  pre: string | null;
}

export function parseVersion(value: string): ParsedVersion | null {
  const m = SEMVER.exec((value ?? "").trim());
  if (!m) return null;
  return { major: Number(m[1]), minor: Number(m[2]), patch: Number(m[3]), pre: m[4] ?? null };
}

function comparePre(a: string | null, b: string | null): number {
  if (a === b) return 0;
  if (a === null) return 1;
  if (b === null) return -1;
  const pa = a.split(".");
  const pb = b.split(".");
  for (let i = 0; i < Math.min(pa.length, pb.length); i++) {
    const x = pa[i];
    const y = pb[i];
    if (x === y) continue;
    const xn = /^\d+$/.test(x);
    const yn = /^\d+$/.test(y);
    if (xn && yn) return Number(x) > Number(y) ? 1 : -1;
    if (xn !== yn) return xn ? -1 : 1;
    return x > y ? 1 : -1;
  }
  return Math.sign(pa.length - pb.length);
}

export function compareVersions(a: string, b: string): number {
  const pa = parseVersion(a);
  const pb = parseVersion(b);
  if (!pa || !pb) throw new Error(`Ungültige Version: ${!pa ? a : b}`);
  for (const key of ["major", "minor", "patch"] as const) {
    if (pa[key] !== pb[key]) return pa[key] > pb[key] ? 1 : -1;
  }
  return comparePre(pa.pre, pb.pre);
}

export function versionCode(version: string): number {
  const p = parseVersion(version);
  if (!p || p.pre !== null) throw new Error(`Nur stabile Versionen: ${version}`);
  if (p.minor > 99 || p.patch > 99) throw new Error("MINOR und PATCH müssen < 100 sein");
  return p.major * 10000 + p.minor * 100 + p.patch;
}
