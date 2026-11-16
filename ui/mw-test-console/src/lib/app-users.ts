// The app users App flows can sign in as (pure, no I/O): core-mock's personas (customer01 ...) and
// mw-seeder's mw-auth personas (seed-customer01 ..., the API suite's seed-run-customerNN), only the
// wallets with the seeded app PIN (manifest appPins.walletIds), grouped by role. Without a manifest
// the personas only (their PIN is core-mock's fixture). A PIN-ready wallet no persona names is listed
// with its MSISDN as the user (walletOf takes an MSISDN as its own wallet).

import { APP_PERSONAS, type AppRole } from "./personas";
import type { SeedManifest } from "./seed-manifest";

export type AppUser = { user: string; msisdn: string; role: AppRole; name?: string };

export const APP_ROLES: readonly AppRole[] = ["customer", "agent", "merchant"];
export const ROLE_LABEL: Record<AppRole, string> = { customer: "Customer", agent: "Agent", merchant: "Merchant" };

const asRole = (v: string | undefined): AppRole | undefined => {
  const r = (v ?? "").toLowerCase();
  return (APP_ROLES as readonly string[]).includes(r) ? (r as AppRole) : undefined;
};

/** Every PIN-ready app user, personas first, then the seeder's users, then the run customers, then wallets without a user. */
export function appUsers(m: SeedManifest | null): AppUser[] {
  const pinIds = m?.appPins ? new Set(m.appPins.walletIds) : null;
  const ready = (msisdn: string) => !pinIds || pinIds.has(msisdn);
  const out = new Map<string, AppUser>();
  for (const p of APP_PERSONAS) if (ready(p.msisdn)) out.set(p.msisdn, { user: p.user, msisdn: p.msisdn, role: p.role, name: p.name });
  const seeded = (m?.personas ?? []).filter((p) => p.realm === "mw-auth" && p.attributes.msisdn);
  // the API suite's run customers last: binding one here replaces the suite's device for it
  const ordered = [...seeded.filter((p) => p.persona !== "run-customer"), ...seeded.filter((p) => p.persona === "run-customer")];
  for (const p of ordered) {
    const msisdn = p.attributes.msisdn as string;
    if (out.has(msisdn) || !ready(msisdn)) continue;
    const w = m?.wallets.find((x) => x.walletId === msisdn);
    const role = asRole(p.roles[0]) ?? asRole(w?.type) ?? asRole(p.persona);
    if (!role) continue;
    out.set(msisdn, { user: p.username, msisdn, role, name: w?.holderName ?? p.displayName });
  }
  for (const id of pinIds ?? []) {
    if (out.has(id) || !/^01[3-9]\d{8}$/.test(id)) continue;
    const w = m?.wallets.find((x) => x.walletId === id);
    const role = asRole(w?.type);
    if (role) out.set(id, { user: id, msisdn: id, role, name: w?.holderName });
  }
  return [...out.values()];
}

/** The users of one role, in list order. */
export const usersOf = (users: readonly AppUser[], role: AppRole): AppUser[] => users.filter((u) => u.role === role);

/** The wallet select's text: "Rahim Uddin, 01711000001 (customer01)". */
export function userLabel(u: AppUser): string {
  const who = u.name ? `${u.name}, ${u.msisdn}` : u.msisdn;
  return u.user === u.msisdn ? who : `${who} (${u.user})`;
}

/** The MSISDN an app user logs in with, from the list; an MSISDN is its own wallet; else undefined. */
export function msisdnOf(users: readonly AppUser[], user: string): string | undefined {
  const u = users.find((x) => x.user === user);
  if (u) return u.msisdn;
  return /^01[3-9]\d{8}$/.test(user) ? user : undefined;
}
