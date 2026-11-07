"use client";

import { useActionState } from "react";
import { inbox } from "../actions/sim";
import { Result } from "../components/result";

type Sms = { destination?: string; source?: string; text?: string; encoding?: string; segments?: number; dlrStatus?: string; receivedAt?: string };
type Mail = { subject?: string; from?: string; receivedDate?: string; contentType?: string; mimeMessage?: string };

export function Inbox() {
  const [state, run, pending] = useActionState(inbox, null);
  const sms = (state?.data?.sms ?? []) as Sms[];
  const mail = (state?.data?.mail ?? []) as Mail[];
  return (
    <>
      <form action={run} className="panel stack" aria-busy={pending}>
        <div className="row">
          <label>Wallet / mobile number<input name="msisdn" defaultValue="01711000001" data-testid="inbox-msisdn" /></label>
          <label>Email address<input name="email" defaultValue="customer01@example.test" data-testid="inbox-email" /></label>
          <button type="submit" name="op" value="read" className="primary" disabled={pending} data-testid="inbox-read">{pending ? "reading…" : "Read"}</button>
          <button type="submit" name="op" value="clearSms" className="danger" disabled={pending}>Clear every SMS</button>
        </div>
        <p className="hint">Realm users&apos; addresses: customer01@example.test … (mw-notify&apos;s dev contacts). A number ending 999 / 998 / 995 gets an UNDELIV / EXPIRED / no delivery report.</p>
      </form>
      {state ? (
        <div className="grid" style={{ marginTop: 16 }}>
          <section className="panel">
            <h2>SMS ({sms.length})</h2>
            <table data-testid="inbox-sms">
              <thead><tr><th>Received</th><th>From</th><th>Text</th><th>DLR</th></tr></thead>
              <tbody>
                {sms.map((m, i) => (
                  <tr key={i}>
                    <td className="mono">{m.receivedAt}</td>
                    <td>{m.source}</td>
                    <td lang="bn" dir="auto">{m.text}<div className="hint">{m.encoding} · {m.segments} part(s)</div></td>
                    <td>{m.dlrStatus ?? "-"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
          <section className="panel">
            <h2>Email ({mail.length})</h2>
            {mail.map((m, i) => (
              <details key={i}>
                <summary><strong>{m.subject}</strong> <span className="hint">{m.receivedDate} · {m.contentType?.split(";")[0]}</span></summary>
                <pre>{m.mimeMessage?.slice(0, 6000)}</pre>
              </details>
            ))}
          </section>
          <section className="panel"><Result state={state} /></section>
        </div>
      ) : null}
    </>
  );
}
