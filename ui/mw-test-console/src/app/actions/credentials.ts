"use server";
// The logins and secrets pages: the live TOTP code of a seeded login (computed here from the secret
// its persona names, so scripts/totp.sh is not needed), the latest OTP an MSISDN received, and a PIN
// encrypted under mw-crypto's transport key for hand-made API calls. Nothing is logged.

import { normaliseMsisdn } from "@/lib/format";
import { latestOtps, type LatestOtp } from "@/lib/otp-lookup";
import { totpNow } from "@/lib/server/seed";
import type { TotpNow } from "@/lib/totp-window";
import { pins, sim } from "@/lib/server/test-apis";

export async function currentTotp(username: string): Promise<TotpNow> {
  if (typeof username !== "string" || !/^[a-z0-9._-]{1,64}$/i.test(username)) return { ok: false, error: "unknown user" };
  return totpNow(username);
}

export type OtpLookupState = { ok: true; msisdn: string; otps: LatestOtp[]; smsCount: number } | { ok: false; error: string } | null;

export async function lookupOtp(_prev: OtpLookupState, form: FormData): Promise<OtpLookupState> {
  const msisdn = normaliseMsisdn(String(form.get("msisdn") ?? ""));
  if (!msisdn) return { ok: false, error: "type a mobile number (01XXXXXXXXX)" };
  try {
    const sms = await sim.sms(msisdn);
    return { ok: true, msisdn, otps: latestOtps(sms), smsCount: sms.length };
  } catch (e) {
    return { ok: false, error: (e as Error).message };
  }
}

export type CipherState = { ok: true; keyId: string; ciphertext: string } | { ok: false; error: string } | null;

export async function pinCiphertext(_prev: CipherState, form: FormData): Promise<CipherState> {
  const pin = String(form.get("pin") ?? "").trim();
  if (!/^[0-9]{4,6}$/.test(pin)) return { ok: false, error: "a PIN has 4 to 6 digits (mw-auth: 5)" };
  try {
    const e = await pins.encrypt(pin);
    return { ok: true, keyId: e.keyId, ciphertext: e.ciphertext };
  } catch (e) {
    return { ok: false, error: (e as Error).message };
  }
}
