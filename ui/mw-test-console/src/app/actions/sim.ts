"use server";
// Mock parties (mw-simulators' mock-control API /test/mock-control/*, ADR D33, tickets 102a/b), core-mock
// faults and the inboxes (test-only APIs, key-auth): set the behaviour a failure test needs, read a
// party's journal, replay a callback, read the SMS (SMSC simulator) and email (GreenMail) a wallet got.

import { normaliseMsisdn } from "@/lib/format";
import { isParty, MOCK_PARTIES, normalCalls, scenarioBody, switchBody } from "@/lib/mock-parties";
import { PRESETS } from "@/lib/presets";
import type { Outcome } from "@/lib/types";
import { Trace } from "@/lib/server/edge";

export type SimState = Outcome | null;
const str = (f: FormData, k: string) => String(f.get(k) ?? "").trim();
const done = (t: Trace, headline: string, data?: Record<string, unknown>): Outcome => ({ ok: !t.failed, headline, steps: t.steps, data });
const MC = "/test/mock-control/";
const fields = (f: FormData, keys: string[]) => Object.fromEntries(keys.map((k) => [k, str(f, k)]));

export async function applyPreset(_prev: SimState, form: FormData): Promise<SimState> {
  const t = new Trace();
  const p = PRESETS.find((x) => x.id === str(form, "preset"));
  if (!p) return done(t, "unknown preset");
  for (const c of p.calls) {
    await t.call(`${c.method} ${c.path}`, c.method, c.path, { apikey: true, json: c.json }, [200, 201, 202, 204]);
  }
  return done(t, t.failed ? `${p.title}: a call failed` : `${p.title}: applied. Next: ${p.then}`);
}

async function normal(t: Trace, party: Parameters<typeof normalCalls>[0]): Promise<void> {
  for (const c of normalCalls(party)) await t.call(`${c.method} ${c.path}`, c.method, c.path, { apikey: true, json: c.json }, [200, 204]);
}

/** Adds one mock-control scenario to a party (empty fields take the engine's defaults). */
export async function addScenario(_prev: SimState, form: FormData): Promise<SimState> {
  const t = new Trace();
  const party = str(form, "party");
  if (!isParty(party)) return done(t, "choose a mock party");
  const body = scenarioBody(fields(form, ["operation", "behaviour", "code", "delayMs", "httpStatus", "key", "nextN"]));
  if (!body.behaviour) return done(t, "choose a behaviour");
  await t.call(`add a ${String(body.behaviour)} scenario to ${party}`, "POST", `${MC}${party}/scenarios`, { apikey: true, json: body }, [201]);
  await t.call(`${party}'s scenarios now`, "GET", `${MC}${party}/scenarios`, { apikey: true });
  return done(t, t.failed ? `${party}: scenario refused (see the answer)` : `${party}: scenario added`);
}

/** Sets a party's switch (UP, DOWN, MAINTENANCE, SLOW with a latency range) and its settings. */
export async function setSwitch(_prev: SimState, form: FormData): Promise<SimState> {
  const t = new Trace();
  const party = str(form, "party");
  if (!isParty(party)) return done(t, "choose a mock party");
  const sw = switchBody(fields(form, ["state", "latencyMinMs", "latencyMaxMs", "ratio"]));
  await t.call(`switch ${party} ${String(sw.state)}`, "PUT", `${MC}${party}/switch`, { apikey: true, json: sw });
  const info = MOCK_PARTIES.find((p) => p.name === party);
  const settings: Record<string, unknown> = {
    callbackUrl: form.get("callbacks") === "on" && info?.callbacks ? info.callbacks : null,
    callbackOnly: form.get("callbackOnly") === "on",
    timeoutDelayMs: str(form, "timeoutDelayMs") === "" ? null : Number(str(form, "timeoutDelayMs")),
  };
  await t.call(`settings of ${party}`, "PUT", `${MC}${party}/settings`, { apikey: true, json: settings });
  return done(t, `${party}: switch ${String(sw.state)}`);
}

/** Shows a party's scenarios, switch, settings and last 10 journal entries; or puts it back to normal / resets it. */
export async function partyState(_prev: SimState, form: FormData): Promise<SimState> {
  const t = new Trace();
  const party = str(form, "party");
  if (!isParty(party)) return done(t, "choose a mock party");
  const op = str(form, "op");
  if (op === "normal") {
    await normal(t, party);
    return done(t, `${party} back to normal (journal and ledgers kept)`);
  }
  if (op === "reset") {
    await t.call(`reset ${party}: journal, scenarios, switch, settings, ledgers`, "DELETE", `${MC}${party}/journal`, { apikey: true }, [204]);
    return done(t, `${party} reset`);
  }
  await t.call(`${party}'s scenarios`, "GET", `${MC}${party}/scenarios`, { apikey: true });
  await t.call(`${party}'s switch`, "GET", `${MC}${party}/switch`, { apikey: true });
  await t.call(`${party}'s settings`, "GET", `${MC}${party}/settings`, { apikey: true });
  const r = await t.call(`${party}'s journal (newest last)`, "GET", `${MC}${party}/journal`, { apikey: true });
  const list = Array.isArray(r.body) ? (r.body as unknown[]) : [];
  const last = t.steps[t.steps.length - 1];
  if (last) last.body = list.slice(-10);
  return done(t, `${party}: ${list.length} journal entr${list.length === 1 ? "y" : "ies"}, last 10 shown`);
}

export async function replayCallback(_prev: SimState, form: FormData): Promise<SimState> {
  const t = new Trace();
  const party = str(form, "party");
  const ref = str(form, "reference");
  if (!isParty(party) || !/^[\w-]{3,80}$/.test(ref)) return done(t, "choose a partner and enter the partner reference (transactionId it received)");
  await t.call(`replay the ${party} callback of ${ref}`, "POST", `${MC}${party}/callbacks/${encodeURIComponent(ref)}/replay`, { apikey: true, json: {} });
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
  if (op === "throttle") {
    await t.call("refuse the next 2 screens with 0x58 throttled", "POST", `${MC}ussdgw/scenarios`, {
      apikey: true, json: { operation: "submit", behaviour: "BUSINESS_ERROR", code: "0x58", nextN: 2 },
    }, [201]);
  }
  if (op === "reset") await normal(t, "ussdgw");
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
