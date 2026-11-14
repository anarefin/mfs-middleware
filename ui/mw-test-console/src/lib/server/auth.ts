import "server-only";
// mw-auth's public API step by step for the wallet authentication page (ticket 76; tickets 68-70,
// spec 18.7): OTP per purpose (code read from the SMSC simulator), device binding, OTP or PIN login,
// logout, first PIN, PIN change and the resets. Its wallets are kept apart from the app page's: a
// fresh wallet (one of core-mock's customers without an app PIN, lib/auth.ts) with the PIN the
// tester chose, so binding second devices, locking and resetting never touch the seeded users.
// In memory (globalThis): a restart forgets them.

import { OTP_TEXT, freshCandidate, type OtpPurpose } from "@/lib/auth";
import { canonical, newDeviceKey, signCanonical, type DeviceKey } from "@/lib/crypto";
import { EdgeError, edge, poll, type EdgeResponse, type Trace } from "./edge";
import { codes, freshCodes, pins } from "./test-apis";

export type AuthDevice = { id: string; key: DeviceKey };
export type AuthWallet = {
  msisdn: string;
  device: AuthDevice;
  boundAt?: string;
  pin?: string;
  access?: string;
  refresh?: string;
  resetId?: string;
};

const g = globalThis as unknown as { __tcAuthWallets?: Map<string, AuthWallet> };
const wallets = (g.__tcAuthWallets ??= new Map());

export const newDevice = (): AuthDevice => ({ id: `tc-auth-${crypto.randomUUID()}`, key: newDeviceKey() });
export const walletFor = (msisdn: string): AuthWallet | undefined => wallets.get(msisdn);
export const known = (): string[] => [...wallets.keys()];
export const keep = (w: AuthWallet): void => void wallets.set(w.msisdn, w);

const JSON_CT = { "Content-Type": "application/json" };

function signed(d: AuthDevice, method: string, path: string, body: string): Record<string, string> {
  const ts = String(Date.now());
  const nonce = `tc-${crypto.randomUUID()}`;
  return {
    "X-Device-Id": d.id, "X-Timestamp": ts, "X-Nonce": nonce,
    "X-Signature": signCanonical(d.key.privatePem, canonical(method, path, body, ts, nonce, d.id)),
  };
}

/** POST /auth/v1/otp, then the new code from the SMSC simulator (null when refused or none arrived). */
export async function askOtp(t: Trace, d: AuthDevice, msisdn: string, purpose: OtpPurpose, label?: string): Promise<{ r: EdgeResponse; code: string | null }> {
  const before = await codes(msisdn, OTP_TEXT[purpose]);
  const r = await t.call(label ?? `ask a ${purpose} OTP (SMS to the wallet)`, "POST", "/auth/v1/otp",
    { headers: { "X-Device-Id": d.id, ...JSON_CT }, raw: JSON.stringify({ msisdn, purpose, locale: "en" }) }, [202]);
  if (r.status !== 202) return { r, code: null };
  const fresh = await poll(() => freshCodes(msisdn, OTP_TEXT[purpose], before), (l) => l.length > 0, 30000);
  const code = fresh[fresh.length - 1] ?? null;
  t.note("read the OTP from the SMSC simulator", code ? `${purpose} OTP ${code}` : "no OTP SMS within 30 s", !!code);
  return { r, code };
}

export async function bind(t: Trace, d: AuthDevice, msisdn: string, otp: string, pin: string | null, label?: string): Promise<EdgeResponse> {
  const b: Record<string, unknown> = { msisdn, publicKey: d.key.publicKey, otp };
  if (pin) b.encryptedPin = await pins.encrypt(pin);
  const body = JSON.stringify(b);
  return t.call(label ?? `bind device ${d.id} (key + OTP${pin ? " + PIN" : ""}, signed by the new key)`, "POST", "/auth/v1/devices",
    { headers: { ...signed(d, "POST", "/auth/v1/devices", body), ...JSON_CT }, raw: body }, [201]);
}

export async function login(t: Trace, d: AuthDevice, msisdn: string, by: { pin: string } | { otp: string }, label?: string, ok = [200]): Promise<EdgeResponse> {
  const b: Record<string, unknown> = { msisdn };
  if ("otp" in by) b.otp = by.otp; else b.encryptedPin = await pins.encrypt(by.pin);
  const body = JSON.stringify(b);
  return t.call(label ?? `log in (${"otp" in by ? "OTP" : "PIN"}, signed by device ${d.id})`, "POST", "/auth/v1/sessions",
    { headers: { ...signed(d, "POST", "/auth/v1/sessions", body), ...JSON_CT }, raw: body }, ok);
}

export async function refresh(t: Trace, d: AuthDevice, token: string, label: string, ok = [200]): Promise<EdgeResponse> {
  const body = JSON.stringify({ refreshToken: token });
  return t.call(label, "POST", "/auth/v1/tokens/refresh", { headers: { ...signed(d, "POST", "/auth/v1/tokens/refresh", body), ...JSON_CT }, raw: body }, ok);
}

export async function setFirstPin(t: Trace, access: string, pin: string, ok = [200]): Promise<EdgeResponse> {
  return t.call("set the first PIN (bearer of the OTP login)", "POST", "/auth/v1/pin",
    { headers: { Authorization: `Bearer ${access}` }, json: { encryptedPin: await pins.encrypt(pin) } }, ok);
}

export async function changePin(t: Trace, access: string, pin: string, newPin: string): Promise<EdgeResponse> {
  return t.call("change the PIN (bearer of a PIN login, current PIN as step-up)", "PUT", "/auth/v1/pin",
    { headers: { Authorization: `Bearer ${access}` }, json: { encryptedPin: await pins.encrypt(pin), newEncryptedPin: await pins.encrypt(newPin) } }, [200]);
}

export async function startReset(t: Trace, d: AuthDevice, msisdn: string, otp: string, factors: { nidLast4: string; dateOfBirth: string } | null): Promise<EdgeResponse> {
  const body = JSON.stringify({ msisdn, otp, ...(factors ?? {}) });
  const headers = factors ? signed(d, "POST", "/auth/v1/pin-resets", body) : { "X-Device-Id": d.id };
  return t.call(factors ? "start the self-service reset (NID last 4 + birth date, signed by the bound device)" : "take up the approved care reset (unsigned, this phone)",
    "POST", "/auth/v1/pin-resets", { headers: { ...headers, ...JSON_CT }, raw: body }, [202]);
}

export async function resetState(d: AuthDevice, id: string): Promise<EdgeResponse> {
  return edge("GET", `/auth/v1/pin-resets/${id}`, { headers: { "X-Device-Id": d.id } });
}

export async function resetPin(t: Trace, d: AuthDevice, id: string, pin: string): Promise<EdgeResponse> {
  return t.call("set the new PIN of the verified reset (this device only)", "PUT", `/auth/v1/pin-resets/${id}/pin`,
    { headers: { "X-Device-Id": d.id }, json: { encryptedPin: await pins.encrypt(pin) } }, [200]);
}

/**
 * A fresh wallet with a bound device and the first PIN: probes candidates with a LOGIN OTP until one
 * has no PIN (202), binds a new device without a PIN, logs in with the LOGIN OTP, sets the PIN.
 */
export async function freshWallet(t: Trace, pin: string): Promise<AuthWallet> {
  let msisdn: string | null = null;
  let d: AuthDevice | null = null;
  for (let i = 0; i < 25 && !msisdn; i++) {
    const candidate = freshCandidate();
    const probe = newDevice();
    const r = await edge("POST", "/auth/v1/otp", { headers: { "X-Device-Id": probe.id, ...JSON_CT },
      raw: JSON.stringify({ msisdn: candidate, purpose: "LOGIN", locale: "en" }) });
    if (r.status === 202) {
      msisdn = candidate;
      d = probe;
    } else if (r.status !== 403) {
      throw new EdgeError(`mw-auth refused the LOGIN OTP probe (HTTP ${r.status})`);
    }
  }
  if (!msisdn || !d) throw new EdgeError("no fresh wallet left among 01711000500..01711000999 (deploy/reset.sh)");
  t.note("pick a fresh wallet", `${msisdn}: core-mock customer without an app PIN (mw-auth sent it a LOGIN OTP)`);
  const b1 = await askOtp(t, d, msisdn, "BIND");
  if (!b1.code) throw new EdgeError("no binding OTP");
  const b = await bind(t, d, msisdn, b1.code, null);
  if (b.status !== 201) throw new EdgeError("binding failed");
  const l1 = await askOtp(t, d, msisdn, "LOGIN");
  if (!l1.code) throw new EdgeError("no LOGIN OTP");
  const l = await login(t, d, msisdn, { otp: l1.code }, "log in with the LOGIN OTP (no PIN yet: amr otp + device)");
  if (l.status !== 200) throw new EdgeError("OTP login failed");
  const p = await setFirstPin(t, (l.body as { accessToken: string }).accessToken, pin);
  if (p.status !== 200) throw new EdgeError("the first PIN was refused (weak? see the step)");
  const tok = p.body as { accessToken: string; refreshToken: string };
  const w: AuthWallet = { msisdn, device: d, boundAt: (b.body as { boundAt?: string }).boundAt, pin, access: tok.accessToken, refresh: tok.refreshToken };
  keep(w);
  return w;
}
