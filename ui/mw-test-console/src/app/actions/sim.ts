"use server";
// Partner simulators, core-mock faults and the inboxes (test-only APIs, key-auth): set the
// behaviour a failure test needs, read what the partners received, replay a callback, read the
// SMS (SMSC simulator) and email (GreenMail) a wallet got.

import { normaliseMsisdn } from "@/lib/format";
import { PRESETS } from "@/lib/presets";
import type { Outcome } from "@/lib/types";
import { Trace } from "@/lib/server/edge";
import { PARTNERS, type SimPartner } from "@/lib/server/test-apis";

export type SimState = Outcome | null;
const str = (f: FormData, k: string) => String(f.get(k) ?? "").trim();
const done = (t: Trace, headline: string, data?: Record<string, unknown>): Outcome => ({ ok: !t.failed, headline, steps: t.steps, data });

export async function applyPreset(_prev: SimState, form: FormData): Promise<SimState> {
  const t = new Trace();
  const p = PRESETS.find((x) => x.id === str(form, "preset"));
  if (!p) return done(t, "unknown preset");
  for (const c of p.calls) {
    await t.call(`${c.method} ${c.path}`, c.method, c.path, { apikey: true, json: c.json }, [200, 201, 202, 204]);
  }
  return done(t, t.failed ? `${p.title}: a call failed` : `${p.title}: applied. Next: ${p.then}`);
}

function num(v: string): number | undefined {
  return v === "" ? undefined : Number(v);
}

/** Set one partner's scenario from the form (fields left empty take the simulator's defaults). */
export async function setScenario(_prev: SimState, form: FormData): Promise<SimState> {
  const t = new Trace();
  const partner = str(form, "partner") as SimPartner;
  if (!PARTNERS.includes(partner)) return done(t, "choose a partner");
  if (str(form, "op") === "reset") {
    await t.call(`reset ${partner} to normal`, "DELETE", `/test/sim/${partner}/scenario`, { apikey: true }, [204]);
    return done(t, `${partner} reset`);
  }
  const s: Record<string, unknown> = {
    forceMode: str(form, "forceMode") || undefined,
    latencyMs: num(str(form, "latencyMs")),
    errorRate: num(str(form, "errorRate")),
    outage: form.get("outage") === "on",
    timeoutDelayMs: num(str(form, "timeoutDelayMs")),
    callbackMode: str(form, "callbackMode") || "NONE",
    callbackDelayMs: num(str(form, "callbackDelayMs")),
    callbackOnly: form.get("callbackOnly") === "on",
  };
  if (s.callbackMode !== "NONE") {
    s.callbackUrl = partner === "c2wbank" ? "http://apisix:9080/partner/c2wbank/callback/debits"
      : partner === "billagg" ? "http://apisix:9080/partner/billagg/callback/payments" : undefined;
  }
  await t.call(`set the ${partner} scenario`, "POST", `/test/sim/${partner}/scenario`, { apikey: true, json: s }, [200, 201, 202, 204]);
  await t.call(`read back the ${partner} scenario`, "GET", `/test/sim/${partner}/scenario`, { apikey: true });
  return done(t, `${partner} scenario set`);
}

export async function readPartner(_prev: SimState, form: FormData): Promise<SimState> {
  const t = new Trace();
  const partner = str(form, "partner") as SimPartner;
  if (!PARTNERS.includes(partner)) return done(t, "choose a partner");
  await t.call(`current ${partner} scenario`, "GET", `/test/sim/${partner}/scenario`, { apikey: true });
  const r = await t.call(`requests ${partner} received (newest last)`, "GET", `/test/sim/${partner}/requests`, { apikey: true });
  const list = Array.isArray(r.body) ? (r.body as unknown[]) : [];
  const last = t.steps[t.steps.length - 1];
  if (last) last.body = list.slice(-10);
  return done(t, `${partner}: ${list.length} recorded request(s), last 10 shown`);
}

export async function replayCallback(_prev: SimState, form: FormData): Promise<SimState> {
  const t = new Trace();
  const partner = str(form, "partner") as SimPartner;
  const ref = str(form, "reference");
  if (!PARTNERS.includes(partner) || !/^[\w-]{3,80}$/.test(ref)) return done(t, "choose a partner and enter the partner reference (transactionId it received)");
  await t.call(`replay the ${partner} callback of ${ref}`, "POST", `/test/sim/${partner}/callbacks/${encodeURIComponent(ref)}/replay`, { apikey: true, json: {} });
  return done(t, "callback replayed: the middleware must apply it at most once (check the balance and the audit trail)");
}

export async function coreFault(_prev: SimState, form: FormData): Promise<SimState> {
  const t = new Trace();
  if (str(form, "op") === "reset") {
    await t.call("remove every core-mock fault", "DELETE", "/test/core-mock/v1/faults", { apikey: true }, [204]);
    return done(t, "core-mock faults cleared");
  }
  const f: Record<string, unknown> = { mode: str(form, "mode"), commandType: str(form, "commandType") || undefined, times: Number(str(form, "times") || "1") };
  if (str(form, "reasonCode")) f.reasonCode = str(form, "reasonCode");
  if (str(form, "delayMs")) f.delayMs = Number(str(form, "delayMs"));
  await t.call(`inject ${f.mode} on ${f.commandType ?? "every command"}`, "POST", "/test/core-mock/v1/faults", { apikey: true, json: f }, [201]);
  return done(t, "fault injected: it applies to the next matching command(s)");
}

export async function walletLookup(_prev: SimState, form: FormData): Promise<SimState> {
  const t = new Trace();
  const w = normaliseMsisdn(str(form, "msisdn"));
  if (!w) return done(t, "enter a wallet number");
  await t.call(`core-mock wallet ${w}`, "GET", `/test/core-mock/v1/wallets/${w}`, { apikey: true });
  return done(t, `wallet ${w}`);
}

export async function smpp(_prev: SimState, form: FormData): Promise<SimState> {
  const t = new Trace();
  const op = str(form, "op");
  if (op === "drop") await t.call("close every SMPP bind of the gp USSD gateway", "POST", "/test/sim/ussdgw/smpp/drop", { apikey: true, json: {} }, [204]);
  if (op === "throttle") await t.call("refuse the next 2 screens with 0x58 throttled", "POST", "/test/sim/ussdgw/smpp/scenario", { apikey: true, json: { throttleNext: 2 } }, [204]);
  if (op === "reset") await t.call("normal SMPP gateway", "POST", "/test/sim/ussdgw/smpp/scenario", { apikey: true, json: {} }, [204]);
  const r = await t.call("binds of mw-ussd on the gp SMPP gateway", "GET", "/test/sim/ussdgw/smpp/binds", { apikey: true });
  return done(t, `SMPP binds: ${JSON.stringify(r.body)}`);
}

/** SMS the SMSC simulator received for a number and email GreenMail holds for an address. */
export async function inbox(_prev: SimState, form: FormData): Promise<SimState> {
  const t = new Trace();
  const w = normaliseMsisdn(str(form, "msisdn"));
  const email = str(form, "email");
  if (str(form, "op") === "clearSms") {
    await t.call("clear every SMS of the SMSC simulator", "DELETE", "/test/sim/smsc/messages", { apikey: true }, [200, 204]);
    return done(t, "SMS inbox cleared");
  }
  let sms: unknown[] = [];
  let mail: unknown[] = [];
  if (w) {
    const r = await t.call(`SMS to ${w}`, "GET", `/test/sim/smsc/messages?msisdn=${w}`, { apikey: true });
    sms = Array.isArray(r.body) ? (r.body as unknown[]).slice().reverse() : [];
    t.steps[t.steps.length - 1]!.body = `${sms.length} message(s), newest first in the list below`;
  }
  if (email) {
    const r = await t.call(`email to ${email}`, "GET", `/test/mail/user/${encodeURIComponent(email)}/messages/`, { apikey: true }, [200, 400]);
    mail = Array.isArray(r.body) ? (r.body as unknown[]).slice().reverse() : [];
    t.steps[t.steps.length - 1]!.body = r.status === 400 ? "mailbox empty" : `${mail.length} email(s)`;
  }
  if (!w && !email) return done(t, "enter a number and/or an email address");
  return done(t, `${sms.length} SMS, ${mail.length} email`, { sms, mail });
}
