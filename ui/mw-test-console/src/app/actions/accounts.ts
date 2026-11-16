"use server";
// The account and wallet tools of the seed data page. The action names are checked against the
// fixed lists in lib/account-tools.ts: nothing else reaches mw-auth or the Keycloak admin API.

import { isKcRealm, isUserAction, isWalletAction } from "@/lib/account-tools";
import { normaliseMsisdn, takaToMinor } from "@/lib/format";
import { rangeOf } from "@/lib/seed-catalogue";
import type { Outcome } from "@/lib/types";
import { userAction, walletAction } from "@/lib/server/accounts";
import { Trace } from "@/lib/server/edge";
import { optional } from "@/lib/server/env";
import { core } from "@/lib/server/test-apis";

const fail = (t: Trace, e: unknown): Outcome => {
  t.note("stopped", (e as Error).message, false);
  return { ok: false, headline: (e as Error).message, steps: t.steps };
};

export async function runWalletTool(action: string, walletId: string, balanceTaka?: string): Promise<Outcome> {
  const t = new Trace();
  if (!isWalletAction(action)) return { ok: false, headline: "this action is not allowed", steps: [] };
  const id = normaliseMsisdn(String(walletId ?? ""));
  if (!id) return { ok: false, headline: "not a wallet number", steps: [] };
  try {
    let balanceMinor: number | undefined;
    if (action === "setBalance") {
      const s = String(balanceTaka ?? "").trim();
      balanceMinor = s === "0" ? 0 : (takaToMinor(s) ?? undefined);
      if (balanceMinor === undefined) return { ok: false, headline: "type a balance in Tk (e.g. 5000 or 5000.50)", steps: [] };
    }
    const headline = await walletAction(t, action, id, { pin: optional("MW_SEED_APP_PIN"), balanceMinor, type: rangeOf(id)?.type });
    return { ok: !t.failed, headline: `${id}: ${headline}`, steps: t.steps };
  } catch (e) {
    return fail(t, e);
  }
}

export async function runUserTool(action: string, realm: string, username: string): Promise<Outcome> {
  const t = new Trace();
  if (!isUserAction(action) || !isKcRealm(realm)) return { ok: false, headline: "this action is not allowed", steps: [] };
  if (typeof username !== "string" || !/^[a-z0-9._-]{1,64}$/i.test(username)) return { ok: false, headline: "not a username", steps: [] };
  try {
    const headline = await userAction(t, action, realm, username);
    return { ok: !t.failed, headline, steps: t.steps };
  } catch (e) {
    return fail(t, e);
  }
}

export type WalletLookup = { ok: true; msisdn: string; holderName?: string; type?: string; balanceMinor?: number; range?: string } | { ok: false; error: string } | null;

export async function lookupWallet(_prev: WalletLookup, form: FormData): Promise<WalletLookup> {
  const msisdn = normaliseMsisdn(String(form.get("msisdn") ?? ""));
  if (!msisdn) return { ok: false, error: "type a mobile number (01XXXXXXXXX)" };
  try {
    const w = (await core.wallet(msisdn)) as ({ holderName?: string; type?: string; balanceMinor: number } | null);
    const r = rangeOf(msisdn);
    if (!w) return { ok: false, error: `${msisdn}: core-mock has no such wallet${r ? "" : " (outside its generated ranges)"}` };
    return { ok: true, msisdn, holderName: w.holderName, type: w.type ?? r?.type, balanceMinor: w.balanceMinor, range: r ? `generated ${r.type.toLowerCase()} range` : undefined };
  } catch (e) {
    return { ok: false, error: (e as Error).message };
  }
}
