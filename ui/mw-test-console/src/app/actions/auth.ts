"use server";
// Wallet authentication at mw-auth (ticket 76; spec 18.7, D20-D22): a fresh wallet with its first PIN,
// the PIN login, binding a second device (the first one is unbound; the new-device window shown),
// the PIN change, the self-service reset, taking up an approved care reset on a new phone, wrong PINs
// up to the lock. The care reset is asked and approved in the ops console (/console, maker-checker).

import { PIN_RE, coolingUntil, detailOf, inCooling } from "@/lib/auth";
import { normaliseMsisdn } from "@/lib/format";
import type { Outcome } from "@/lib/types";
import {
  askOtp, bind, changePin, freshWallet, keep, login, newDevice, refresh, resetPin, resetState, startReset, walletFor, type AuthWallet,
} from "@/lib/server/auth";
import { EdgeError, Trace, codeOf, poll } from "@/lib/server/edge";

export type AuthState = Outcome | null;

const str = (f: FormData, k: string) => String(f.get(k) ?? "").trim();

function claimsOf(token: string | undefined): Record<string, unknown> {
  try {
    return JSON.parse(Buffer.from(String(token).split(".")[1] ?? "", "base64url").toString("utf8")) as Record<string, unknown>;
  } catch {
    return {};
  }
}

function tokens(w: AuthWallet, body: unknown): void {
  const b = body as { accessToken?: string; refreshToken?: string };
  w.access = b.accessToken;
  w.refresh = b.refreshToken;
  keep(w);
}

function walletOf(form: FormData): AuthWallet {
  const m = normaliseMsisdn(str(form, "msisdn"));
  const w = m ? walletFor(m) : undefined;
  if (!w) throw new EdgeError("create a fresh wallet first (this page only acts on the wallets it made)");
  return w;
}

function pinOf(form: FormData, key: string): string {
  const p = str(form, key);
  if (!PIN_RE.test(p)) throw new EdgeError("a PIN has 5 digits");
  return p;
}

export async function authFlow(_prev: AuthState, form: FormData): Promise<AuthState> {
  const t = new Trace();
  const op = str(form, "op");
  const out = (headline: string, data: Record<string, unknown> = {}): Outcome => ({ ok: !t.failed, headline, steps: t.steps, data });
  try {
    switch (op) {
      case "fresh": {
        const w = await freshWallet(t, pinOf(form, "pin"));
        return out(`fresh wallet ${w.msisdn} ready: device bound, first PIN set (amr ${String(claimsOf(w.access).amr)})`, { msisdn: w.msisdn });
      }
      case "login": {
        const w = walletOf(form);
        const pin = pinOf(form, "pin");
        const r = await login(t, w.device, w.msisdn, { pin }, undefined, [200, 401, 423]);
        if (r.status !== 200) {
          const left = detailOf(r.body, "triesLeft");
          const after = detailOf(r.body, "retryAfter");
          return { ...out(`refused ${codeOf(r)}${left ? `: ${left} tries left` : ""}${after ? `: locked, retry after ${after} s` : ""}`, { msisdn: w.msisdn }), ok: false };
        }
        tokens(w, r.body);
        const c = claimsOf(w.access);
        return out(`logged in: amr ${String(c.amr)}, device ${String(c.deviceId)}, token ${String((r.body as { expiresIn?: number }).expiresIn)} s`, { msisdn: w.msisdn });
      }
      case "second": {
        // spec 18.7: one bound device per wallet; the binding moves, the old device's sessions end
        const w = walletOf(form);
        const pin = pinOf(form, "pin");
        const old = w.device;
        const phone = newDevice();
        const o = await askOtp(t, phone, w.msisdn, "BIND", "new phone: ask a binding OTP");
        if (!o.code) throw new EdgeError("no binding OTP");
        const b = await bind(t, phone, w.msisdn, o.code, pin, "new phone: bind (key + OTP + PIN)");
        if (b.status !== 201) throw new EdgeError("binding the second device failed");
        const boundAt = String((b.body as { boundAt?: string }).boundAt);
        const until = coolingUntil(boundAt);
        t.note("new-device (cooling) flag", `mw-auth marks this wallet's transaction assertions newDevice=true until ${until} (boundAt + 24 h, mw.auth.assertion.new-device-window); the flag travels only in the signed assertion to the core`, true,
          { boundAt, newDeviceUntil: until, coolingNow: inCooling(boundAt) });
        await login(t, old, w.msisdn, { pin }, "old phone: log in (must be refused: MW-AUTH-047)", [401]);
        if (w.refresh) await refresh(t, old, w.refresh, "old phone: refresh its token (must be refused)", [401]);
        Object.assign(w, { device: phone, boundAt, access: undefined, refresh: undefined });
        const l = await login(t, phone, w.msisdn, { pin }, "new phone: log in");
        if (l.status === 200) tokens(w, l.body);
        return out(`second device ${phone.id} bound at ${boundAt}; cooling (newDevice) until ${until}; the old device is out`, { msisdn: w.msisdn, coolingUntil: until });
      }
      case "change": {
        const w = walletOf(form);
        const pin = pinOf(form, "pin");
        const next = pinOf(form, "newPin");
        if (!w.access) {
          const l = await login(t, w.device, w.msisdn, { pin });
          if (l.status !== 200) throw new EdgeError("log in first (right PIN)");
          tokens(w, l.body);
        }
        const r = await changePin(t, String(w.access), pin, next);
        if (r.status !== 200) return { ...out(`refused ${codeOf(r)}${detailOf(r.body, "encryptedPin") ? ` (${detailOf(r.body, "encryptedPin")})` : ""}`, { msisdn: w.msisdn }), ok: false };
        tokens(w, r.body);
        w.pin = next;
        return out(`PIN changed; the session is replaced (old refresh token revoked); the wallet gets a "PIN changed" SMS`, { msisdn: w.msisdn });
      }
      case "selfReset": {
        const w = walletOf(form);
        const next = pinOf(form, "newPin");
        const o = await askOtp(t, w.device, w.msisdn, "RESET");
        if (!o.code) throw new EdgeError("no reset OTP (only the bound device may ask one)");
        const s = await startReset(t, w.device, w.msisdn, o.code, { nidLast4: str(form, "nidLast4") || "4821", dateOfBirth: str(form, "dob") || "1990-05-17" });
        if (s.status !== 202) throw new EdgeError("the reset was not opened");
        const id = String((s.body as { resetId?: string }).resetId);
        const v = await poll(() => resetState(w.device, id), (x) => (x.body as { status?: string } | null)?.status !== "PENDING_KYC", 30000);
        t.steps.push({ label: "poll the reset while the core checks the factors", call: `GET /auth/v1/pin-resets/${id}`, status: v.status, ok: v.status === 200, body: v.body, ms: v.ms });
        if ((v.body as { status?: string } | null)?.status !== "VERIFIED") throw new EdgeError("the core did not verify the factors");
        const d = await resetPin(t, w.device, id, next);
        if (d.status !== 200) throw new EdgeError("the new PIN was refused (weak?)");
        Object.assign(w, { pin: next, access: undefined, refresh: undefined });
        keep(w);
        return out(`self-service reset ${id} DONE: new PIN set, every session ended, PIN lock cleared`, { msisdn: w.msisdn });
      }
      case "careTakeUp": {
        // after an ops-maker asked PIN_RESET for the wallet and an ops-checker approved it in /console
        const w = walletOf(form);
        const next = pinOf(form, "newPin");
        const phone = newDevice();
        const o = await askOtp(t, phone, w.msisdn, "RESET", "new phone: ask a reset OTP (allowed only while a care reset is approved)");
        if (!o.code) throw new EdgeError("no reset OTP: is the care reset approved in the ops console?");
        const s = await startReset(t, phone, w.msisdn, o.code, null);
        if (s.status !== 202) throw new EdgeError("the care reset was not taken up");
        const id = String((s.body as { resetId?: string }).resetId);
        const d = await resetPin(t, phone, id, next);
        if (d.status !== 200) throw new EdgeError("the new PIN was refused (weak?)");
        const b = await askOtp(t, phone, w.msisdn, "BIND", "new phone: ask a binding OTP");
        if (!b.code) throw new EdgeError("no binding OTP");
        const bound = await bind(t, phone, w.msisdn, b.code, next, "new phone: bind with the new PIN");
        if (bound.status !== 201) throw new EdgeError("binding the new phone failed");
        Object.assign(w, { device: phone, pin: next, boundAt: (bound.body as { boundAt?: string }).boundAt, access: undefined, refresh: undefined });
        keep(w);
        return out(`care reset ${id} DONE on the new phone; it is bound with the new PIN`, { msisdn: w.msisdn });
      }
      case "wrongPins": {
        const w = walletOf(form);
        let last = "";
        for (let i = 1; i <= 3; i++) {
          const r = await login(t, w.device, w.msisdn, { pin: "97531" }, `wrong PIN ${i} (97531)`, [401, 423]);
          last = `${codeOf(r)}${detailOf(r.body, "triesLeft") ? ` (${detailOf(r.body, "triesLeft")} left)` : ""}${detailOf(r.body, "retryAfter") ? ` (retry after ${detailOf(r.body, "retryAfter")} s)` : ""}`;
          if (r.status === 423) break;
        }
        return out(`after three wrong PINs: ${last}; an ops unlock (/console) or MW_AUTH_LOCK_LEVEL1 later opens it`, { msisdn: w.msisdn });
      }
      default:
        return out("unknown step");
    }
  } catch (e) {
    t.note("stopped", (e as Error).message, false);
    return out((e as Error).message);
  }
}
