import { readFileSync } from "node:fs";
import { manifestPath } from "./env";

/**
 * The seeded ops personas (mw-seeder, ticket 57) the suite signs in as. Names and roles come from the
 * seed manifest (build/seed/manifest.json after `make seed`); the password and TOTP secret from the
 * deploy/.env variables the manifest names (make ui-e2e exports them), never from the repo.
 */
export const PERSONAS = ["maker", "checker", "viewer"] as const;
export type PersonaName = (typeof PERSONAS)[number];

export type ManifestPersona = {
  persona: string;
  realm: string;
  username: string;
  roles: string[];
  passwordEnv: string;
  totpSecretEnv?: string;
};

export type Persona = { name: PersonaName; username: string; roles: string[]; password: string; totpSecret: string };

/** The ops personas of a manifest text; a missing one is listed in `missing`. */
export function parseManifest(text: string): { personas: Map<PersonaName, ManifestPersona>; missing: PersonaName[] } {
  const doc = JSON.parse(text) as { personas?: ManifestPersona[] };
  const personas = new Map<PersonaName, ManifestPersona>();
  for (const p of doc.personas ?? []) {
    if (p.realm === "ops" && (PERSONAS as readonly string[]).includes(p.persona)) personas.set(p.persona as PersonaName, p);
  }
  return { personas, missing: PERSONAS.filter((n) => !personas.has(n)) };
}

let cache: Map<PersonaName, ManifestPersona> | undefined;

export function persona(name: PersonaName): Persona {
  cache ??= parseManifest(readFileSync(manifestPath(), "utf8")).personas;
  const p = cache.get(name);
  if (!p) throw new Error(`the seed manifest has no ops persona ${name} (make seed)`);
  const password = process.env[p.passwordEnv];
  const totpSecret = p.totpSecretEnv ? process.env[p.totpSecretEnv] : undefined;
  if (!password || !totpSecret) throw new Error(`${p.passwordEnv} / ${p.totpSecretEnv} not set (run through make ui-e2e)`);
  return { name, username: p.username, roles: p.roles, password, totpSecret };
}
