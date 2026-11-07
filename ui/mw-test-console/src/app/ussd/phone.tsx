"use client";
// A feature phone dialling *268#: the screen mw-ussd sent, a keypad and the reply box, and the
// session's transcript. Inputs typed on a PIN screen are shown as ****.

import { useActionState, useRef, useState } from "react";
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
    <div className="cols">
      <div>
        <form ref={formRef} action={(f) => { press(f); setInput(""); }} aria-busy={pending}>
          <div className="row" style={{ marginBottom: 12 }}>
            <label>MNO (transport)
              <select name="mno" value={mno} onChange={(e) => setMno(e.target.value)} data-testid="ussd-mno">
                <option value="robi">robi (HTTP)</option>
                <option value="gp">gp (SMPP)</option>
              </select>
            </label>
            <label>Subscriber<input name="msisdn" value={msisdn} onChange={(e) => setMsisdn(e.target.value)} data-testid="ussd-msisdn" /></label>
            <label>Code<input name="serviceCode" defaultValue="*268#" /></label>
          </div>
          <div className="phone">
            <div className="meta"><span>{mno.toUpperCase()} {mno === "gp" ? "· SMPP" : "· HTTP"}</span><span>{msisdn}</span></div>
            <div className={`screen ${ended ? "ended" : ""}`} data-testid="ussd-screen">
              {pending ? "…" : last ? last.message ?? `(no screen: HTTP ${last.status})` : "Press Dial to start a session"}
            </div>
            <div className="meta"><span>{ended ? (screens.length ? "session ended" : "idle") : "session open"}</span><span>{inputs.length} input(s)</span></div>
            <input name="input" value={input} onChange={(e) => setInput(e.target.value)} placeholder="reply" autoComplete="off"
              type={last && asksForPin(last.message ?? "") ? "password" : "text"} data-testid="ussd-input" disabled={ended} />
            <div className="keypad">
              {KEYS.map((k) => <button key={k} type="button" onClick={() => setInput((v) => v + k)} disabled={ended}>{k}</button>)}
            </div>
            <div className="calls">
              <button type="submit" name="op" value={ended ? "dial" : "send"} className="send" disabled={pending} data-testid="ussd-send">
                {ended ? "Dial" : "Send"}
              </button>
              <button type="button" className="end" onClick={() => setInput("")}>Clear</button>
            </div>
          </div>
        </form>
        <p className="hint" style={{ maxWidth: 300 }}>
          Main menu: 1 Send Money, 2 Cash Out, 3 Mobile Recharge, 4 Pay Bill, 9 switch language (the session starts in Bangla).
          PIN 1234. Over SMPP every key press replays the session from the dial (the simulator cannot resume an SMPP session).
        </p>
      </div>
      <div>
        <section className="panel">
          <h2>Transcript</h2>
          <ol className="transcript" data-testid="ussd-transcript">
            {screens.map((s, i) => {
              const prevScreen = screens[i - 1];
              const typed = i > 0 ? inputs[i - 1] : undefined;
              const shown = typed === undefined ? null : prevScreen && asksForPin(prevScreen.message ?? "") ? "****" : typed;
              return (
                <li key={i}>
                  {shown !== null ? <div className="hint">you typed: <code>{shown}</code></div> : null}
                  <div>{s.flag === "END" ? "[END] " : ""}{s.message}</div>
                </li>
              );
            })}
          </ol>
        </section>
        {state ? <section className="panel"><h2>Last call</h2><Result state={state} /></section> : null}
      </div>
    </div>
  );
}
