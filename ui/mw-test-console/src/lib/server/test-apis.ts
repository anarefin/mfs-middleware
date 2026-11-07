import "server-only";
// The test-only APIs behind APISIX key-auth (deploy/apisix/routes-test, MW_E2E_ROUTES=1):
// core-mock (/test/core-mock/v1: wallets, faults, PIN key), the simulators (/test/sim: partner
// scenarios, recorded requests, callback replay, SMSC, USSD gateway, merchant IPN) and GreenMail.

import { encryptPin, type EncryptedPin } from "@/lib/crypto";
import type { SmsRow } from "@/lib/types";
import { EdgeError, edge } from "./edge";

export type Wallet = { balanceMinor: number; heldMinor: number; status?: string };

export const core = {
  async wallet(id: string): Promise<Wallet | null> {
    const r = await edge("GET", `/test/core-mock/v1/wallets/${encodeURIComponent(id)}`, { apikey: true });
    return r.status === 200 ? (r.body as Wallet) : null;
  },
  async pinKey(): Promise<{ keyId: string; publicKeyPem: string }> {
    const g = globalThis as unknown as { __tcPinKey?: { keyId: string; publicKeyPem: string } };
    if (g.__tcPinKey) return g.__tcPinKey;
    const r = await edge("GET", "/test/core-mock/v1/pin-public-key", { apikey: true });
    if (r.status !== 200) throw new EdgeError(`the core's PIN key is not available (HTTP ${r.status}): are the test routes on (make tester)?`);
    return (g.__tcPinKey = r.body as { keyId: string; publicKeyPem: string });
  },
  async encrypt(pin: string): Promise<EncryptedPin> {
    const k = await core.pinKey();
    return encryptPin(pin, k.publicKeyPem, k.keyId);
  },
};

export type SimPartner = "ocs" | "billagg" | "c2wbank";
export const PARTNERS: readonly SimPartner[] = ["ocs", "billagg", "c2wbank"];

export const sim = {
  async sms(msisdn: string): Promise<SmsRow[]> {
    const r = await edge("GET", `/test/sim/smsc/messages?msisdn=${encodeURIComponent(msisdn)}`, { apikey: true });
    return r.status === 200 && Array.isArray(r.body) ? (r.body as SmsRow[]) : [];
  },
  async mail(email: string): Promise<Record<string, unknown>[]> {
    const r = await edge("GET", `/test/mail/user/${encodeURIComponent(email)}/messages/`, { apikey: true });
    return r.status === 200 && Array.isArray(r.body) ? (r.body as Record<string, unknown>[]) : [];
  },
  async requests(partner: SimPartner): Promise<Record<string, unknown>[]> {
    const r = await edge("GET", `/test/sim/${partner}/requests`, { apikey: true });
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
