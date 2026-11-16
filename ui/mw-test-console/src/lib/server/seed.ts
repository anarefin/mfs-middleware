import "server-only";
// mw-seeder's manifest (read-only volume, SEED_MANIFEST) and the deploy/.env values its personas
// name. Values are resolved only for the names in lib/secrets.ts; nothing here is ever logged.

import { readFile } from "node:fs/promises";
import { totp } from "@/lib/crypto";
import { SHOWABLE_ENVS } from "@/lib/secrets";
import { PORTALS_IMPORT, parseManifest, type Persona, type SeedManifest } from "@/lib/seed-manifest";
import { totpWindow, type TotpNow } from "@/lib/totp-window";
import { optional, seedManifestPath } from "./env";

export type ManifestLoad = { manifest: SeedManifest; error?: undefined } | { manifest: null; error: string };

export async function loadManifest(): Promise<ManifestLoad> {
  let raw: string;
  try {
    raw = await readFile(seedManifestPath(), "utf8");
  } catch {
    return { manifest: null, error: "No seed manifest: start the stack with --profile seed (mw-seeder)" };
  }
  let json: unknown;
  try {
    json = JSON.parse(raw);
  } catch {
    return { manifest: null, error: "The seed manifest is not JSON (re-run the seed profile)" };
  }
  const p = parseManifest(json);
  return p.ok ? { manifest: p.value } : { manifest: null, error: `The seed manifest cannot be read: ${p.error}` };
}

/** A deploy/.env value the pages may show; undefined when missing or not on the list. */
export function envValue(name: string | null | undefined): string | undefined {
  return name && SHOWABLE_ENVS.has(name) ? optional(name) : undefined;
}

export async function findLogin(username: string): Promise<Persona | undefined> {
  const { manifest } = await loadManifest();
  const all = [...(manifest?.personas ?? []), ...(manifest?.importedUsers ?? []), ...PORTALS_IMPORT];
  return all.find((p) => p.username === username && p.realm !== "mw-auth");
}

/** The user's current code from the pre-set secret its persona names. */
export async function totpNow(username: string): Promise<TotpNow> {
  const p = await findLogin(username);
  if (!p) return { ok: false, error: "unknown user" };
  if (!p.totpSecretEnv) return { ok: false, error: "no pre-set code: re-run the seed profile" };
  const secret = envValue(p.totpSecretEnv);
  if (!secret) return { ok: false, error: `${p.totpSecretEnv} not set in deploy/.env` };
  const now = Date.now();
  const w = totpWindow(now);
  return { ok: true, code: totp(secret, now), step: w.step, remainingMs: w.remainingMs };
}
