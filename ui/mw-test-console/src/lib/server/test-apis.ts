import "server-only";
// The test-only APIs behind APISIX key-auth (deploy/apisix/routes-test, MW_E2E_ROUTES=1):
// core-mock (/test/core-mock/v1: wallets, faults), the simulators (/test/sim: SMSC inbox, USSD
// gateway, merchant IPNs; /test/mock-control: scenarios, switches, journal of every mock party) and GreenMail.

import { encryptPin, type EncryptedPin } from "@/lib/crypto";
import type { MockPartyName } from "@/lib/mock-parties";
import type { SmsRow } from "@/lib/types";
import { EdgeError, edge } from "./edge";

export type Wallet = { balanceMinor: number; heldMinor: number; status?: string };

export const core = {
  async wallet(id: string): Promise<Wallet | null> {
    const r = await edge("GET", `/test/core-mock/v1/wallets/${encodeURIComponent(id)}`, { apikey: true });
    return r.status === 200 ? (r.body as Wallet) : null;
  },
};

/**
 * The transaction PIN (ticket 72): one PIN per wallet, held by mw-auth, encrypted under the mw-crypto
 * PIN_TRANSPORT key, the key an app gets from GET /app/v1/pin-key and the checkout page in its
 * `pinKey` (both mw-auth's GET /auth/v1/pin-key, public, read here). The core has no PIN key any more.
 */
export const pins = {
  async transportKey(): Promise<{ keyId: string; publicKeyPem: string }> {
    const g = globalThis as unknown as { __tcTransportKey?: { keyId: string; publicKeyPem: string } };
    if (g.__tcTransportKey) return g.__tcTransportKey;
    const r = await edge("GET", "/auth/v1/pin-key");
    if (r.status !== 200) throw new EdgeError(`mw-auth's PIN transport key is not available (HTTP ${r.status})`);
    return (g.__tcTransportKey = r.body as { keyId: string; publicKeyPem: string });
  },
  async encrypt(pin: string): Promise<EncryptedPin> {
    const k = await pins.transportKey();
    return encryptPin(pin, k.publicKeyPem, k.keyId);
  },
};


export const sim = {
  async sms(msisdn: string): Promise<SmsRow[]> {
    const r = await edge("GET", `/test/sim/smsc/messages?msisdn=${encodeURIComponent(msisdn)}`, { apikey: true });
    return r.status === 200 && Array.isArray(r.body) ? (r.body as SmsRow[]) : [];
  },
  async mail(email: string): Promise<Record<string, unknown>[]> {
    const r = await edge("GET", `/test/mail/user/${encodeURIComponent(email)}/messages/`, { apikey: true });
    return r.status === 200 && Array.isArray(r.body) ? (r.body as Record<string, unknown>[]) : [];
  },
  /** The mock party's journal (mock-control API, ADR D33). */
  async requests(party: MockPartyName): Promise<Record<string, unknown>[]> {
    const r = await edge("GET", `/test/mock-control/${party}/journal`, { apikey: true });
    return r.status === 200 && Array.isArray(r.body) ? (r.body as Record<string, unknown>[]) : [];
  },
};

/** Codes in the newest SMS to `msisdn` matching `re` (group 1) that were not in `before`. */
export async function freshCodes(msisdn: string, re: RegExp, before: string[]): Promise<string[]> {
  const all = (await sim.sms(msisdn)).map((m) => re.exec(m.text ?? "")).filter((m): m is RegExpExecArray => !!m).map((m) => m[1] ?? "");
  return all.filter((c) => !before.includes(c));
}

export async function codes(msisdn: string, re: RegExp): Promise<string[]> {
  return freshCodes(msisdn, re, []);
}
