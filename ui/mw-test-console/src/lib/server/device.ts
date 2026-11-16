import "server-only";
// The app's bound device and mw-auth session per app user (spec 9.1, 18.7; ticket 69): an EC P-256
// key made here, bound at mw-auth with the MSISDN, the OTP the SMSC simulator received and the app
// PIN, then a login (MSISDN + PIN, signed) gives the access and refresh tokens; every submit signed.
// In memory: a restart of the test console forgets the devices (the next submit binds again; only
// one device per wallet is active, so binding here also unbinds the API suite's device of that user).

import { canonical, encryptPin, newDeviceKey, signCanonical, type DeviceKey, type EncryptedPin } from "@/lib/crypto";
import { EdgeError, edge, poll, type Trace } from "./edge";
import { appPin } from "./env";
import { codes, freshCodes } from "./test-apis";
import { walletOf } from "./tokens";

type Device = {
  id: string; key: DeviceKey; bound: boolean; lastUse: number;
  access?: string; refresh?: string; exp?: number; loggedInAt?: number;
};
const g = globalThis as unknown as { __tcDevices?: Map<string, Device>; __tcAuthPinKey?: { keyId: string; publicKeyPem: string } };
const devices = (g.__tcDevices ??= new Map());

const BIND_OTP = /Your Bits code to bind a new device is ([0-9]{6})/;

export function deviceOf(user: string): Device {
  let d = devices.get(user);
  if (!d) {
    d = { id: `tc-${crypto.randomUUID()}`, key: newDeviceKey(), bound: false, lastUse: 0 };
    devices.set(user, d);
  }
  return d;
}

export function forgetDevice(user: string): void {
  devices.delete(user);
}

export type DeviceState = { bound: boolean; deviceId?: string; loggedInAt?: number; lastUse?: number; session: boolean };

/** The user's device in this console: bound or not, and its mw-auth session (login time; live while used within 4 min). */
export function deviceState(user: string): DeviceState {
  const d = devices.get(user);
  if (!d) return { bound: false, session: false };
  const session = Boolean(d.access) && Date.now() - d.lastUse <= 4 * 60000;
  return { bound: d.bound, deviceId: d.id, loggedInAt: d.access ? d.loggedInAt : undefined, lastUse: d.lastUse || undefined, session };
}

/** The app PIN encrypted under mw-auth's PIN transport key (GET /auth/v1/pin-key, public). */
async function encryptedAppPin(): Promise<EncryptedPin> {
  if (!g.__tcAuthPinKey) {
    const r = await edge("GET", "/auth/v1/pin-key");
    if (r.status !== 200) throw new EdgeError(`mw-auth's PIN transport key is not available (HTTP ${r.status})`);
    g.__tcAuthPinKey = r.body as { keyId: string; publicKeyPem: string };
  }
  return encryptPin(appPin(), g.__tcAuthPinKey.publicKeyPem, g.__tcAuthPinKey.keyId);
}

/** The device signature headers (no bearer: mw-auth's /auth/v1 takes none). */
function deviceSigned(d: Device, method: string, path: string, body: string): Record<string, string> {
  const ts = String(Date.now());
  const nonce = `tc-${crypto.randomUUID()}`;
  return {
    "X-Device-Id": d.id, "X-Timestamp": ts, "X-Nonce": nonce,
    "X-Signature": signCanonical(d.key.privatePem, canonical(method, path, body, ts, nonce, d.id)),
  };
}

/** Bearer (the mw-auth access token), X-Device-Id and the request signature headers for one call. */
export async function signedHeaders(user: string, method: string, path: string, body: string): Promise<Record<string, string>> {
  const d = deviceOf(user);
  return { Authorization: `Bearer ${d.access ?? ""}`, ...deviceSigned(d, method, path, body) };
}

/** Unsigned app GETs: bearer and X-Device-Id. */
export async function deviceHeaders(user: string): Promise<Record<string, string>> {
  const d = deviceOf(user);
  return { Authorization: `Bearer ${d.access ?? ""}`, "X-Device-Id": d.id };
}

function keep(d: Device, body: unknown): void {
  const b = body as { accessToken: string; refreshToken: string; expiresIn: number };
  d.access = b.accessToken;
  d.refresh = b.refreshToken;
  d.exp = Date.now() + b.expiresIn * 1000;
}

/**
 * Makes sure the user's device is bound, logged in at mw-auth and its access token fresh; records
 * each step on the trace: POST /auth/v1/otp (MSISDN, purpose BIND), the OTP read from the SMSC
 * simulator, POST /auth/v1/devices (key + OTP + PIN, signed), POST /auth/v1/sessions (login: MSISDN +
 * PIN, signed). A device idle for more than 4 minutes logs in again (mw-auth ends the session after
 * 5); an access token about to expire is refreshed (POST /auth/v1/tokens/refresh, signed).
 */
export async function ready(t: Trace, user: string, force = false): Promise<void> {
  if (force) forgetDevice(user);
  const d = deviceOf(user);
  const wallet = await walletOf(user);
  if (!d.bound) {
    const before = await codes(wallet, BIND_OTP);
    const r = await t.call("1. ask for the device binding OTP (SMS to the wallet)", "POST", "/auth/v1/otp",
      { headers: { "X-Device-Id": d.id, "Content-Type": "application/json" }, raw: JSON.stringify({ msisdn: wallet, purpose: "BIND", locale: "en" }) }, [202]);
    if (r.status !== 202) throw new EdgeError("mw-auth refused to send a binding OTP");
    const fresh = await poll(() => freshCodes(wallet, BIND_OTP, before), (l) => l.length > 0, 30000);
    const otp = fresh[fresh.length - 1];
    if (!otp) {
      t.note("2. read the OTP from the SMSC simulator", `no binding OTP SMS reached ${wallet} within 30 s (mw-notify / SMSC?)`, false);
      throw new EdgeError("no binding OTP arrived");
    }
    t.note("2. read the OTP from the SMSC simulator", `OTP ${otp} received by ${wallet}`);
    const body = JSON.stringify({ msisdn: wallet, publicKey: d.key.publicKey, otp, encryptedPin: await encryptedAppPin() });
    const b = await t.call("3. bind the device (public key + OTP + app PIN, signed)", "POST", "/auth/v1/devices",
      { headers: { ...deviceSigned(d, "POST", "/auth/v1/devices", body), "Content-Type": "application/json" }, raw: body }, [201]);
    if (b.status !== 201) throw new EdgeError("binding the device failed");
    d.bound = true;
    d.access = undefined;
  }
  if (!d.access || Date.now() - d.lastUse > 4 * 60000) {
    const body = JSON.stringify({ msisdn: wallet, encryptedPin: await encryptedAppPin() });
    const r = await t.call("log in at mw-auth (MSISDN + app PIN, signed)", "POST", "/auth/v1/sessions",
      { headers: { ...deviceSigned(d, "POST", "/auth/v1/sessions", body), "Content-Type": "application/json" }, raw: body }, [200]);
    if (r.status !== 200) {
      if (!force && (r.body as { code?: string } | null)?.code === "MW-AUTH-047") {
        // the device was unbound elsewhere (e.g. the API suite bound its own): bind once more
        return ready(t, user, true);
      }
      throw new EdgeError("the login at mw-auth failed");
    }
    keep(d, r.body);
    d.loggedInAt = Date.now();
  } else if ((d.exp ?? 0) - 30000 < Date.now()) {
    const body = JSON.stringify({ refreshToken: d.refresh });
    const r = await t.call("refresh the tokens at mw-auth (signed)", "POST", "/auth/v1/tokens/refresh",
      { headers: { ...deviceSigned(d, "POST", "/auth/v1/tokens/refresh", body), "Content-Type": "application/json" }, raw: body }, [200]);
    if (r.status !== 200) {
      d.access = undefined;
      return ready(t, user);
    }
    keep(d, r.body);
  }
  d.lastUse = Date.now();
}
