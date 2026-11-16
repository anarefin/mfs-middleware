// mw-seeder's manifest (services/mw-seeder dto/SeedManifest.java, schemaVersion 1): what the seed
// profile put in place. It holds no secret: a persona names the deploy/.env variables of its
// password and TOTP secret, which the server resolves. Parsed defensively: an entry of the wrong
// shape is skipped, newer optional fields (displayName, holderName, importedUsers) may be missing.
// Pure, no I/O (lib/server/seed.ts reads the file).

import { APP_PERSONAS, OTHER_FIXTURES, PORTAL_PERSONAS } from "./personas";

export type Realm = "ops" | "portals" | "mw-auth";

export type Persona = {
  persona: string;
  realm: Realm;
  username: string;
  displayName?: string;
  roles: string[];
  attributes: Record<string, string>;
  loginClient?: string;
  passwordEnv?: string;
  totpSecretEnv: string | null;
};

export type ManifestWallet = { walletId: string; type: string; openingBalanceMinor: number; holderName?: string };
export type ManifestPartner = { partnerId: string; family: string; version: number; enabled: boolean; timeoutMs: number | null };
export type ManifestMenu = { menuId: string; version: number; mnos: string[] };
export type ManifestTemplate = { code: string; channel: string; locale: string; version: number };

export type SeedManifest = {
  schemaVersion: 1;
  personas: Persona[];
  importedUsers: Persona[];
  wallets: ManifestWallet[];
  partners: ManifestPartner[];
  ussdMenus: ManifestMenu[];
  templates: ManifestTemplate[];
  appPins: { pinEnv: string; walletIds: string[] } | null;
};

export type Parsed = { ok: true; value: SeedManifest } | { ok: false; error: string };

type Obj = Record<string, unknown>;
const isObj = (v: unknown): v is Obj => typeof v === "object" && v !== null && !Array.isArray(v);
const str = (v: unknown): string | undefined => (typeof v === "string" && v !== "" ? v : undefined);
const num = (v: unknown): number | undefined => (typeof v === "number" && Number.isFinite(v) ? v : undefined);
const strings = (v: unknown): string[] => (Array.isArray(v) ? v.filter((x): x is string => typeof x === "string") : []);
const list = <T>(v: unknown, one: (o: Obj) => T | null): T[] =>
  Array.isArray(v) ? v.flatMap((x) => (isObj(x) ? [one(x)].filter((y): y is T => y !== null) : [])) : [];

function persona(o: Obj): Persona | null {
  const username = str(o.username);
  const realm = o.realm;
  if (!username || (realm !== "ops" && realm !== "portals" && realm !== "mw-auth")) return null;
  const attributes: Record<string, string> = {};
  if (isObj(o.attributes)) for (const [k, v] of Object.entries(o.attributes)) if (typeof v === "string") attributes[k] = v;
  return {
    persona: str(o.persona) ?? username,
    realm,
    username,
    displayName: str(o.displayName),
    roles: strings(o.roles),
    attributes,
    loginClient: str(o.loginClient),
    passwordEnv: str(o.passwordEnv),
    totpSecretEnv: str(o.totpSecretEnv) ?? null,
  };
}

function wallet(o: Obj): ManifestWallet | null {
  const walletId = str(o.walletId);
  const type = str(o.type);
  const openingBalanceMinor = num(o.openingBalanceMinor);
  if (!walletId || !type || openingBalanceMinor === undefined) return null;
  return { walletId, type, openingBalanceMinor, holderName: str(o.holderName) };
}

function partner(o: Obj): ManifestPartner | null {
  const partnerId = str(o.partnerId);
  if (!partnerId) return null;
  return { partnerId, family: str(o.family) ?? "", version: num(o.version) ?? 0, enabled: o.enabled === true, timeoutMs: num(o.timeoutMs) ?? null };
}

function menu(o: Obj): ManifestMenu | null {
  const menuId = str(o.menuId);
  return menuId ? { menuId, version: num(o.version) ?? 0, mnos: strings(o.mnos) } : null;
}

function template(o: Obj): ManifestTemplate | null {
  const code = str(o.code);
  return code ? { code, channel: str(o.channel) ?? "", locale: str(o.locale) ?? "", version: num(o.version) ?? 0 } : null;
}

export function parseManifest(json: unknown): Parsed {
  if (!isObj(json)) return { ok: false, error: "the manifest is not a JSON object" };
  if (json.schemaVersion !== 1) return { ok: false, error: `unknown manifest schemaVersion ${String(json.schemaVersion)} (the console reads 1)` };
  if (!Array.isArray(json.personas)) return { ok: false, error: "the manifest has no personas" };
  const pins = isObj(json.appPins) && str(json.appPins.pinEnv) ? { pinEnv: String(json.appPins.pinEnv), walletIds: strings(json.appPins.walletIds) } : null;
  return {
    ok: true,
    value: {
      schemaVersion: 1,
      personas: list(json.personas, persona),
      importedUsers: list(json.importedUsers, persona),
      wallets: list(json.wallets, wallet),
      partners: list(json.partners, partner),
      ussdMenus: list(json.ussdMenus, menu),
      templates: list(json.templates, template),
      appPins: pins,
    },
  };
}

/** Every login of a Keycloak realm: the seeder's personas first, then the realm import's users, then `known` ones not listed. */
export function personasOf(m: SeedManifest | null, realm: "ops" | "portals", known: readonly Persona[] = []): Persona[] {
  const seen = new Set<string>();
  return [...(m?.personas ?? []), ...(m?.importedUsers ?? []), ...known].filter((p) => p.realm === realm && !seen.has(p.username) && seen.add(p.username));
}

/** The realm portals import's users (deploy/keycloak/realms/portals-realm.json), known without a manifest; all have the pre-set secret. */
export const PORTALS_IMPORT: readonly Persona[] = PORTAL_PERSONAS.map((p) => ({
  persona: p.user, realm: "portals", username: p.user, displayName: p.name, roles: [], attributes: {},
  loginClient: "mw-portal", passwordEnv: "KC_PORTALS_TEST_PASSWORD", totpSecretEnv: "KC_PORTALS_TEST_TOTP_SECRET",
}));

export type AppWalletRow = {
  walletId: string;
  user?: string;
  name?: string;
  role: string;
  hasPin: boolean;
  openingBalanceMinor?: number;
};

const RUN = "run-customer";

/** The run customers of the API suite (seed-run-customerNN). */
export function runCustomers(m: SeedManifest): { username: string; walletId: string; name?: string }[] {
  return m.personas
    .filter((p) => p.persona === RUN && p.attributes.msisdn)
    .map((p) => ({ username: p.username, walletId: p.attributes.msisdn as string, name: p.displayName ?? m.wallets.find((w) => w.walletId === p.attributes.msisdn)?.holderName }));
}

/**
 * The app wallets: core-mock's named fixtures (customer01 ...), the seeder's app users and wallets,
 * and every wallet with the seeded app PIN; the run customers are listed apart. One row per wallet.
 */
export function appWalletRows(m: SeedManifest | null): AppWalletRow[] {
  const pinIds = new Set(m?.appPins?.walletIds ?? []);
  const runIds = new Set(m ? runCustomers(m).map((r) => r.walletId) : []);
  const rows = new Map<string, AppWalletRow>();
  const put = (r: AppWalletRow) => {
    if (runIds.has(r.walletId)) return;
    const had = rows.get(r.walletId);
    rows.set(r.walletId, had ? { ...had, ...Object.fromEntries(Object.entries(r).filter(([, v]) => v !== undefined)), hasPin: had.hasPin || r.hasPin } : r);
  };
  for (const p of APP_PERSONAS) put({ walletId: p.msisdn, user: p.user, name: p.name, role: p.role, hasPin: !m || pinIds.has(p.msisdn) });
  for (const f of OTHER_FIXTURES) put({ walletId: f.msisdn, name: f.name, role: f.role, hasPin: pinIds.has(f.msisdn) });
  for (const p of m?.personas ?? []) {
    const id = p.attributes.msisdn;
    if (p.realm !== "mw-auth" || !id || p.persona === RUN) continue;
    const w = m?.wallets.find((x) => x.walletId === id);
    put({ walletId: id, user: p.username, name: w?.holderName ?? p.displayName, role: p.roles[0] ?? p.persona, hasPin: pinIds.has(id), openingBalanceMinor: w?.openingBalanceMinor });
  }
  for (const w of m?.wallets ?? []) {
    if (w.type === "ORG") continue;
    put({ walletId: w.walletId, name: w.holderName, role: w.type.toLowerCase(), hasPin: pinIds.has(w.walletId), openingBalanceMinor: w.openingBalanceMinor });
  }
  for (const id of pinIds) if (!rows.has(id) && !runIds.has(id)) put({ walletId: id, role: "app user", hasPin: true });
  return [...rows.values()];
}
