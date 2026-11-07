import "server-only";
// The app's bound device per customers-realm user (contracts app.v1, spec device binding): an EC
// P-256 key made here, bound with the OTP the SMSC simulator received, every submit signed.
// In memory: a restart of the test console forgets the devices (the next submit binds again; only
// one device per wallet is active, so binding here also unbinds the API suite's device of that user).

import { canonical, newDeviceKey, signCanonical, type DeviceKey } from "@/lib/crypto";
import { EdgeError, poll, type Trace } from "./edge";
import { codes, freshCodes } from "./test-apis";
import { bearer, walletOf } from "./tokens";

type Device = { id: string; key: DeviceKey; bound: boolean; lastUse: number };
const g = globalThis as unknown as { __tcDevices?: Map<string, Device> };
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

export function deviceState(user: string): { bound: boolean; deviceId?: string } {
  const d = devices.get(user);
  return d ? { bound: d.bound, deviceId: d.id } : { bound: false };
}

/** Bearer, X-Device-Id and the request signature headers for one call. */
export async function signedHeaders(user: string, method: string, path: string, body: string): Promise<Record<string, string>> {
  const d = deviceOf(user);
  const ts = String(Date.now());
  const nonce = `tc-${crypto.randomUUID()}`;
  return {
    ...(await bearer("customers", user)),
    "X-Device-Id": d.id, "X-Timestamp": ts, "X-Nonce": nonce,
    "X-Signature": signCanonical(d.key.privatePem, canonical(method, path, body, ts, nonce, d.id)),
  };
}

/** Unsigned app GETs: bearer and X-Device-Id. */
export async function deviceHeaders(user: string): Promise<Record<string, string>> {
  return { ...(await bearer("customers", user)), "X-Device-Id": deviceOf(user).id };
}

/**
 * Makes sure the user's device is bound and its session open; records each step on the trace:
 * POST /devices/otp, the OTP read from the SMSC simulator, POST /devices; or POST /sessions when
 * the bound device was idle for more than 4 minutes (the gateway closes it after 5).
 */
export async function ready(t: Trace, user: string, force = false): Promise<void> {
  if (force) forgetDevice(user);
  const d = deviceOf(user);
  if (!d.bound) {
    const wallet = await walletOf(user);
    const before = await codes(wallet, BIND_OTP);
    const r = await t.call("1. ask for the device binding OTP (SMS to the wallet)", "POST", "/app/v1/devices/otp",
      { headers: { ...(await bearer("customers", user)), "X-Device-Id": d.id }, raw: '{"locale":"en"}' }, [202]);
    if (r.status !== 202) throw new EdgeError("the gateway refused to send a binding OTP");
    const fresh = await poll(() => freshCodes(wallet, BIND_OTP, before), (l) => l.length > 0, 30000);
    const otp = fresh[fresh.length - 1];
    if (!otp) {
      t.note("2. read the OTP from the SMSC simulator", `no binding OTP SMS reached ${wallet} within 30 s (mw-notify / SMSC?)`, false);
      throw new EdgeError("no binding OTP arrived");
    }
    t.note("2. read the OTP from the SMSC simulator", `OTP ${otp} received by ${wallet}`);
    const body = JSON.stringify({ publicKey: d.key.publicKey, otp });
    const b = await t.call("3. bind the device (public key + OTP, signed)", "POST", "/app/v1/devices",
      { headers: await signedHeaders(user, "POST", "/app/v1/devices", body), raw: body }, [201]);
    if (b.status !== 201) throw new EdgeError("binding the device failed");
    d.bound = true;
  } else if (Date.now() - d.lastUse > 4 * 60000) {
    const r = await t.call("open a new app session for the bound device (signed)", "POST", "/app/v1/sessions",
      { headers: await signedHeaders(user, "POST", "/app/v1/sessions", ""), raw: "" });
    if (r.status !== 200) {
      // the gateway forgot the device (e.g. bound again elsewhere): bind once more
      forgetDevice(user);
      return ready(t, user);
    }
  }
  d.lastUse = Date.now();
}
