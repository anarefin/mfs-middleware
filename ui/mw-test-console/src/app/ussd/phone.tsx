"use client";
// A feature phone dialling *268#: the screen mw-ussd sent, a keypad and the reply box, and the
// session's transcript. Inputs typed on a PIN screen are shown as ****.

import { Delete, ListChecks, PhoneCall, ScrollText } from "lucide-react";
import { useActionState, useRef, useState } from "react";
import { Hint, Panel, TwoColumns } from "@/components/section";
import { Card } from "@/components/ui/card";
import { Field } from "@/components/ui/field";
import { Input, NativeSelect } from "@/components/ui/input";
import { cn } from "@/lib/utils";
import { asksForPin } from "@/lib/format";
import { ussdPress } from "../actions/ussd";
import { Result } from "../components/result";

const KEYS = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"];

export function Phone() {
  const [state, press, pending] = useActionState(ussdPress, null);
  const [input, setInput] = useState("");
  const [mno, setMno] = useState("robi");
  const [msisdn, setMsisdn] = useState("01711000001");
  const formRef = useRef<HTMLFormElement>(null);
  const screens = state?.data?.screens ?? [];
  const last = screens[screens.length - 1];
  const ended = state?.data?.ended ?? true;
  const inputs = state?.data?.inputs ?? [];

  return (
    <TwoColumns className="lg:grid-cols-[360px_minmax(0,1fr)]">
      <div className="flex min-w-0 flex-col gap-4">
        <form ref={formRef} action={(f) => { press(f); setInput(""); }} aria-busy={pending} className="flex flex-col gap-4">
          <Card className="grid grid-cols-2 gap-3 p-4">
            <Field label="MNO (transport)">
              <NativeSelect name="mno" value={mno} onChange={(e) => setMno(e.target.value)} data-testid="ussd-mno">
                <option value="robi">robi (HTTP)</option>
                <option value="gp">gp (SMPP)</option>
              </NativeSelect>
            </Field>
            <Field label="Code"><Input name="serviceCode" defaultValue="*268#" className="font-mono" /></Field>
            <Field label="Subscriber" className="col-span-2"><Input name="msisdn" value={msisdn} onChange={(e) => setMsisdn(e.target.value)} data-testid="ussd-msisdn" className="font-mono" /></Field>
          </Card>
          {/* the handset depicts hardware: fixed colours in both themes */}
          <div className="mx-auto w-[300px] rounded-[2rem] border-4 border-[#0b0f17] bg-[#1f2937] px-4 py-5 text-white shadow-xl">
            <div className="mx-auto mb-3 h-1.5 w-14 rounded-full bg-[#0b0f17]" />
            <div className="my-1.5 flex justify-between text-[11px] text-[#cbd5e1]"><span>{mno.toUpperCase()} {mno === "gp" ? "· SMPP" : "· HTTP"}</span><span className="font-mono">{msisdn}</span></div>
            <div
              className={cn(
                "min-h-[200px] rounded-md p-2.5 font-mono text-[13px] break-words whitespace-pre-wrap text-[#111] shadow-[inset_0_2px_6px_rgba(0,0,0,0.35)]",
                ended ? "bg-[#d1d5db]" : "bg-[#d9f99d]",
              )}
              data-testid="ussd-screen"
            >
              {pending ? "…" : last ? last.message ?? `(no screen: HTTP ${last.status})` : "Press Dial to start a session"}
            </div>
            <div className="my-1.5 flex justify-between text-[11px] text-[#cbd5e1]"><span>{ended ? (screens.length ? "session ended" : "idle") : "session open"}</span><span>{inputs.length} input(s)</span></div>
            <input name="input" value={input} onChange={(e) => setInput(e.target.value)} placeholder="reply" autoComplete="off"
              type={last && asksForPin(last.message ?? "") ? "password" : "text"} data-testid="ussd-input" disabled={ended}
              className="mt-2 h-9 w-full rounded-md border border-[#4b5563] bg-[#111827] px-3 font-mono text-sm text-white outline-none placeholder:text-[#6b7280] focus-visible:border-[#9ca3af] disabled:opacity-50" />
            <div className="mt-3 grid grid-cols-3 gap-1.5">
              {KEYS.map((k) => (
                <button key={k} type="button" onClick={() => setInput((v) => v + k)} disabled={ended}
                  className="rounded-lg bg-[#374151] py-2.5 text-base font-medium text-white shadow-[0_2px_0_#111827] transition-colors hover:bg-[#4b5563] active:translate-y-px disabled:opacity-50">
                  {k}
                </button>
              ))}
            </div>
            <div className="mt-2 grid grid-cols-2 gap-1.5">
              <button type="submit" name="op" value={ended ? "dial" : "send"} disabled={pending} data-testid="ussd-send"
                className="flex items-center justify-center gap-1.5 rounded-lg bg-[#16a34a] py-2.5 font-semibold text-white hover:bg-[#15803d] disabled:opacity-60">
                <PhoneCall className="size-4" />{ended ? "Dial" : "Send"}
              </button>
              <button type="button" onClick={() => setInput("")}
                className="flex items-center justify-center gap-1.5 rounded-lg bg-[#dc2626] py-2.5 font-semibold text-white hover:bg-[#b91c1c]">
                <Delete className="size-4" />Clear
              </button>
            </div>
          </div>
        </form>
        <Hint className="mx-auto max-w-[300px]">
          Main menu: 1 Send Money, 2 Cash Out, 3 Mobile Recharge, 4 Pay Bill, 9 switch language (the session starts in Bangla).
          PIN 1234. Over SMPP every key press replays the session from the dial (the simulator cannot resume an SMPP session).
        </Hint>
      </div>
      <div className="flex min-w-0 flex-col gap-5">
        <Panel title="Transcript" icon={ScrollText}>
          <ol className="m-0 flex list-none flex-col gap-2 p-0 text-xs" data-testid="ussd-transcript">
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
