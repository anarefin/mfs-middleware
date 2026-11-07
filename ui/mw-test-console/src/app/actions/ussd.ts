"use server";
// A handset dialling the USSD service through the USSD gateway simulator (/test/sim/ussdgw, the
// MNO side): robi plays the MNO's HTTP transport, gp its SMPP transport (mw-ussd binds to the
// simulator). Over HTTP each key press continues the open session (`resume`); SMPP sessions cannot
// be resumed by the simulator, so each key press replays the session from the dial with every
// input so far (only the last step runs anything: menus and inputs before the PIN move no money).

import { normaliseMsisdn } from "@/lib/format";
import type { Outcome } from "@/lib/types";
import { Trace } from "@/lib/server/edge";

export type UssdScreen = { status: number; message?: string; flag?: string };
export type UssdState = (Outcome & { data: { sessionId?: string; screens: UssdScreen[]; ended: boolean; inputs: string[]; mno: string; msisdn: string } }) | null;

const MNOS = ["robi", "gp"] as const;

export async function ussdPress(prev: UssdState, form: FormData): Promise<UssdState> {
  const t = new Trace();
  const mno = String(form.get("mno") ?? "robi");
  const msisdn = normaliseMsisdn(String(form.get("msisdn") ?? ""));
  const code = String(form.get("serviceCode") ?? "*268#").trim() || "*268#";
  const op = String(form.get("op") ?? "dial");
  const input = String(form.get("input") ?? "").trim();
  const empty = { screens: [], ended: true, inputs: [], mno, msisdn: msisdn ?? "" };
  if (!(MNOS as readonly string[]).includes(mno)) return { ok: false, headline: "choose robi (HTTP) or gp (SMPP)", steps: [], data: empty };
  if (!msisdn) return { ok: false, headline: "enter the subscriber's number (01XXXXXXXXX)", steps: [], data: empty };

  const open = op === "send" && prev?.data && !prev.data.ended && prev.data.mno === mno && prev.data.msisdn === msisdn;
  if (op === "send" && !open) return { ok: false, headline: "no open session: dial first", steps: [], data: prev?.data ?? empty };
  if (op === "send" && !input) return { ok: false, headline: "type a reply", steps: t.steps, data: prev!.data };

  const inputs = open ? [...prev!.data.inputs, input] : [];
  let body: Record<string, unknown>;
  let label: string;
  if (!open) {
    body = { mno, msisdn, serviceCode: code, inputs: [] };
    label = `dial ${code} as ${msisdn} on ${mno} (${mno === "gp" ? "SMPP" : "HTTP"})`;
  } else if (mno === "robi") {
    body = { mno, msisdn, serviceCode: code, inputs: [input], sessionId: prev!.data.sessionId, resume: true };
    label = "send the reply in the open session (HTTP CONTINUE)";
  } else {
    body = { mno, msisdn, serviceCode: code, inputs };
    label = `SMPP: replay the session from the dial with ${inputs.length} input(s)`;
  }
  const r = await t.call(label, "POST", "/test/sim/ussdgw/sessions", { apikey: true, json: body });
  const res = (r.body ?? {}) as { sessionId?: string; screens?: UssdScreen[]; ended?: boolean };
  // the step shows the screens only; the inputs (PIN) are never echoed back
  const got = res.screens ?? [];
  const screens = !open ? got : mno === "robi" ? [...prev!.data.screens, ...got] : got;
  const last = screens[screens.length - 1];
  const ok = r.status === 200 && got.every((s) => s.status === 200);
  return {
    ok, headline: last?.message ?? `HTTP ${r.status}`, steps: t.steps,
    data: { sessionId: res.sessionId ?? prev?.data.sessionId, screens, ended: res.ended ?? true, inputs, mno, msisdn },
  };
}
