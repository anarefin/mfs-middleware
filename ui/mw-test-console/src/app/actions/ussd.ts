"use server";
// A handset dialling the USSD service through the USSD gateway simulator (/test/sim/ussdgw, the
// MNO side), on any MNO of lib/ussd-mnos.ts with its own short code. HTTP MNOs (robi, bl, teletalk)
// continue the open session on each key press (`resume`); SMPP sessions (gp) cannot be resumed by
// the simulator, so each key press replays the session from the dial with every input so far
// (only the last step runs anything: menus and inputs before the PIN move no money).
// A session counts as ended unless the simulator says it is open and every screen came back 200,
// so an error step never leaves the handset on "Send"; `end` hangs up locally (mw-ussd's session
// runs out by its TTL, as when a real handset drops).

import { normaliseMsisdn } from "@/lib/format";
import type { Outcome } from "@/lib/types";
import { mnoOf, USSD_MNOS } from "@/lib/ussd-mnos";
import { Trace } from "@/lib/server/edge";

export type UssdScreen = { status: number; message?: string; flag?: string };
export type UssdSession = { sessionId?: string; screens: UssdScreen[]; ended: boolean; inputs: string[]; mno: string; msisdn: string; code: string };
export type UssdState = (Outcome & { data: UssdSession }) | null;

export async function ussdPress(prev: UssdState, form: FormData): Promise<UssdState> {
  const t = new Trace();
  const mno = String(form.get("mno") ?? "robi");
  const config = mnoOf(mno);
  const msisdn = normaliseMsisdn(String(form.get("msisdn") ?? ""));
  const code = String(form.get("serviceCode") ?? "").trim() || config?.code || "";
  const op = String(form.get("op") ?? "dial");
  const input = String(form.get("input") ?? "").trim();
  const empty: UssdSession = { screens: [], ended: true, inputs: [], mno, msisdn: msisdn ?? "", code };
  const closed: UssdSession = prev?.data ? { ...prev.data, ended: true } : empty;
  if (op === "end") {
    return { ok: true, headline: prev?.data && !prev.data.ended ? "hung up: press Dial for a new session" : "no open session", steps: [], data: closed };
  }
  if (!config) return { ok: false, headline: `choose an MNO: ${USSD_MNOS.map((m) => m.id).join(", ")}`, steps: [], data: closed };
  if (!msisdn) return { ok: false, headline: "enter the subscriber's number (01XXXXXXXXX)", steps: [], data: closed };

  const open = op === "send" && !!prev?.data && !prev.data.ended && prev.data.mno === mno && prev.data.msisdn === msisdn && prev.data.code === code;
  if (op === "send" && !open) return { ok: false, headline: "no open session on this MNO and number: press Dial", steps: [], data: closed };
  if (op === "send" && !input) return { ok: false, headline: "type a reply", steps: t.steps, data: prev!.data };

  const http = config.transport === "HTTP";
  const inputs = open ? [...prev!.data.inputs, input] : [];
  let body: Record<string, unknown>;
  let label: string;
  if (!open) {
    body = { mno, msisdn, serviceCode: code, inputs: [] };
    label = `dial ${code} as ${msisdn} on ${mno} (${config.transport})`;
  } else if (http) {
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
  const screens = !open ? got : http ? [...prev!.data.screens, ...got] : got;
  const last = screens[screens.length - 1];
  const ok = r.status === 200 && got.length > 0 && got.every((s) => s.status === 200);
  const ended = !ok || res.ended !== false;
  return {
    ok, headline: last?.message ?? `HTTP ${r.status}`, steps: t.steps,
    data: { sessionId: res.sessionId ?? prev?.data.sessionId, screens, ended, inputs, mno, msisdn, code },
  };
}
