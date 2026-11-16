"use client";
// A feature phone dialling the MNO's own short code (lib/ussd-mnos.ts): the screen mw-ussd sent, a
// keypad and the reply box, and the session's transcript. Inputs typed on a PIN screen are shown as
// ****. The session is live only while it is open on the MNO, number and code now in the form:
// changing one of them, or End, drops it locally and the next press dials again.

import { Delete, ListChecks, PhoneCall, PhoneOff, ScrollText } from "lucide-react";
import { useActionState, useRef, useState } from "react";
import { Hint, Panel, TwoColumns } from "@/components/section";
import { Card } from "@/components/ui/card";
import { Field } from "@/components/ui/field";
import { Input, NativeSelect } from "@/components/ui/input";
import { cn } from "@/lib/utils";
import { asksForPin, normaliseMsisdn } from "@/lib/format";
import { DEFAULT_USSD_MNO, mnoLabel, mnoOf, USSD_MNOS } from "@/lib/ussd-mnos";
import { ussdPress } from "../actions/ussd";
import { Result } from "../components/result";

const KEYS = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"];

export function Phone() {
  const [state, press, pending] = useActionState(ussdPress, null);
  const [input, setInput] = useState("");
  const [mno, setMno] = useState(DEFAULT_USSD_MNO);
  const [code, setCode] = useState(mnoOf(DEFAULT_USSD_MNO)!.code);
  const [msisdn, setMsisdn] = useState("01711000001");
  const formRef = useRef<HTMLFormElement>(null);
  const transport = mnoOf(mno)?.transport ?? "HTTP";
  const screens = state?.data?.screens ?? [];
  const last = screens[screens.length - 1];
  const session = state?.data;
  const sameLine = !!session && session.mno === mno && session.msisdn === (normaliseMsisdn(msisdn) ?? msisdn) && session.code === code.trim();
  const live = !!session && !session.ended && sameLine;
  const dropped = !!session && !session.ended && !sameLine;
  const inputs = state?.data?.inputs ?? [];

  return (
    <TwoColumns className="lg:grid-cols-[23rem_minmax(0,1fr)]">
      <div className="flex min-w-0 flex-col gap-4">
        <form ref={formRef} action={(f) => { press(f); setInput(""); }} aria-busy={pending} className="flex flex-col gap-4">
          <Card className="grid grid-cols-2 gap-3 p-4">
            <Field label="MNO (transport)">
              <NativeSelect name="mno" value={mno} data-testid="ussd-mno"
                onChange={(e) => { setMno(e.target.value); setCode(mnoOf(e.target.value)?.code ?? code); }}>
                {USSD_MNOS.map((m) => <option key={m.id} value={m.id}>{mnoLabel(m)}</option>)}
              </NativeSelect>
            </Field>
            <Field label="Code"><Input name="serviceCode" value={code} onChange={(e) => setCode(e.target.value)} className="font-mono" data-testid="ussd-code" /></Field>
            <Field label="Subscriber" className="col-span-2"><Input name="msisdn" value={msisdn} onChange={(e) => setMsisdn(e.target.value)} data-testid="ussd-msisdn" className="font-mono" /></Field>
          </Card>
          {/* the handset depicts hardware: fixed colours in both themes */}
          <div className="mx-auto w-full max-w-[20rem] rounded-[2rem] border-4 border-[#0b0f17] bg-[#1f2937] px-4 py-5 text-white shadow-xl">
            <div className="mx-auto mb-3 h-1.5 w-14 rounded-full bg-[#0b0f17]" />
            <div className="my-1.5 flex justify-between text-xs text-[#cbd5e1]"><span>{mno.toUpperCase()} · {transport} · {code}</span><span className="font-mono">{msisdn}</span></div>
            <div
              className={cn(
                "min-h-[200px] rounded-md p-2.5 font-mono text-sm break-words whitespace-pre-wrap text-[#111] shadow-[inset_0_2px_6px_rgba(0,0,0,0.35)]",
                live ? "bg-[#d9f99d]" : "bg-[#d1d5db]",
              )}
              data-testid="ussd-screen"
            >
              {pending ? "…" : last ? last.message ?? `(no screen: HTTP ${last.status})` : "Press Dial to start a session"}
            </div>
            <div className="my-1.5 flex justify-between text-xs text-[#cbd5e1]"><span data-testid="ussd-status">{live ? "session open" : dropped ? "session dropped (MNO, number or code changed)" : screens.length ? "session ended" : "idle"}</span><span>{inputs.length} input(s)</span></div>
            <input name="input" value={input} onChange={(e) => setInput(e.target.value)} placeholder="reply" autoComplete="off"
              type={last && asksForPin(last.message ?? "") ? "password" : "text"} data-testid="ussd-input" disabled={!live}
              className="mt-2 h-9 w-full rounded-md border border-[#4b5563] bg-[#111827] px-3 font-mono text-sm text-white outline-none placeholder:text-[#6b7280] focus-visible:border-[#9ca3af] disabled:opacity-50" />
            <div className="mt-3 grid grid-cols-3 gap-1.5">
              {KEYS.map((k) => (
                <button key={k} type="button" onClick={() => setInput((v) => v + k)} disabled={!live}
                  className="rounded-lg bg-[#374151] py-2.5 text-base font-medium text-white shadow-[0_2px_0_#111827] transition-colors hover:bg-[#4b5563] active:translate-y-px disabled:opacity-50">
                  {k}
                </button>
              ))}
            </div>
            <div className="mt-2 grid grid-cols-3 gap-1.5">
              <button type="submit" name="op" value={live ? "send" : "dial"} disabled={pending} data-testid="ussd-send"
                className="flex items-center justify-center gap-1.5 rounded-lg bg-[#16a34a] py-2.5 font-semibold text-white hover:bg-[#15803d] disabled:opacity-60">
                <PhoneCall className="size-4" />{live ? "Send" : "Dial"}
              </button>
              <button type="submit" name="op" value="end" disabled={pending || !live} data-testid="ussd-end"
                className="flex items-center justify-center gap-1.5 rounded-lg bg-[#b45309] py-2.5 font-semibold text-white hover:bg-[#92400e] disabled:opacity-50">
                <PhoneOff className="size-4" />End
              </button>
              <button type="button" onClick={() => setInput("")}
                className="flex items-center justify-center gap-1.5 rounded-lg bg-[#dc2626] py-2.5 font-semibold text-white hover:bg-[#b91c1c]">
                <Delete className="size-4" />Clear
              </button>
            </div>
          </div>
        </form>
        <Hint className="mx-auto max-w-[20rem]">
          Main menu: 1 Send Money, 2 Cash Out, 3 Recharge, 4 Pay Bill, 5 Balance, 9 switch language (the session starts in Bangla).
          PIN 13579 (5 digits). Each MNO has its own code (picking the MNO fills it in); another MNO&apos;s code ends at once with the
          wrong-code screen. End hangs up; changing the MNO, number or code also drops the session. Over SMPP (gp) every key press
          replays the session from the dial (the simulator cannot resume an SMPP session).
        </Hint>
      </div>
      <div className="flex min-w-0 flex-col gap-5">
        <Panel title="Transcript" icon={ScrollText}>
          <ol className="m-0 flex list-none flex-col gap-2 p-0 text-sm" data-testid="ussd-transcript">
            {screens.map((s, i) => {
              const prevScreen = screens[i - 1];
              const typed = i > 0 ? inputs[i - 1] : undefined;
              const shown = typed === undefined ? null : prevScreen && asksForPin(prevScreen.message ?? "") ? "****" : typed;
              return (
                <li key={i} className={cn("rounded-md border-l-[3px] bg-muted/60 px-3 py-2 whitespace-pre-wrap", s.flag === "END" ? "border-l-muted-foreground" : "border-l-info")}>
                  {shown !== null ? <div className="mb-1 text-muted-foreground">you typed: <code className="font-mono text-foreground">{shown}</code></div> : null}
                  <div className="font-mono">{s.flag === "END" ? "[END] " : ""}{s.message}</div>
                </li>
              );
            })}
          </ol>
          {screens.length === 0 ? <Hint>No session yet: press Dial on the handset.</Hint> : null}
        </Panel>
        {state ? <Panel title="Last call" icon={ListChecks}><Result state={state} /></Panel> : null}
      </div>
    </TwoColumns>
  );
}
