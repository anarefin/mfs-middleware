// What an action did: the headline, the facts to check (status, balances, SMS) and every call it
// made through the edge, in order, each expandable to its answer.

import { formatMinor } from "@/lib/format";
import type { Outcome } from "@/lib/types";

export function Result({ state }: { state: Outcome | null }) {
  if (!state) return null;
  const consoleUrl = state.data?.consoleUrl as string | undefined;
  return (
    <section className={`result ${state.ok ? "good" : "bad"}`} aria-live="polite" data-testid="result">
      <p className="headline" data-testid="headline">
        {state.ok ? "✔ " : "✖ "}
        {state.headline}
      </p>
      {state.status ? (
        <p>
          Final status: <span className={`status ${state.status}`} data-testid="txn-status">{state.status}</span>
          {state.txnId ? <> · txnId <code data-testid="txn-id">{state.txnId}</code></> : null}
          {consoleUrl ? <> · <a href={consoleUrl} target="_blank" rel="noreferrer">open in the ops console ↗</a></> : null}
        </p>
      ) : null}
      {state.data?.idempotencyKey ? <p className="hint">Idempotency-Key <code>{String(state.data.idempotencyKey)}</code></p> : null}
      {state.balances?.length ? (
        <table className="balances" data-testid="balances">
          <thead>
            <tr><th>Wallet (core-mock)</th><th>Before</th><th>After</th><th>Change</th><th>Held now</th></tr>
          </thead>
          <tbody>
            {state.balances.map((b) => {
              const d = b.beforeMinor !== null && b.afterMinor !== null ? b.afterMinor - b.beforeMinor : null;
              return (
                <tr key={b.wallet}>
                  <td className="mono">{b.wallet}</td>
                  <td>{b.beforeMinor === null ? "-" : formatMinor(b.beforeMinor)}</td>
                  <td>{b.afterMinor === null ? "-" : formatMinor(b.afterMinor)}</td>
                  <td className={d ? (d > 0 ? "up" : "down") : ""} data-testid={`delta-${b.wallet}`}>{d === null ? "-" : formatMinor(d)}</td>
                  <td>{b.heldMinor === null ? "-" : formatMinor(b.heldMinor)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      ) : null}
      {state.sms?.length ? (
        <>
          <h3>SMS received since the submit</h3>
          <ul className="sms" data-testid="sms">
            {state.sms.map((m, i) => (
              <li key={i}><span className="mono">{m.destination}</span> {m.text} <span className="hint">({m.dlrStatus ?? "no DLR yet"})</span></li>
            ))}
          </ul>
        </>
      ) : state.balances ? <p className="hint">No SMS yet (notifications can take a few seconds: see Inbox).</p> : null}
      <h3>Steps</h3>
      <ol className="steps">
        {state.steps.map((s, i) => (
          <li key={i} className={s.ok ? "ok" : "ko"}>
            <details>
              <summary>
                <span className="mark">{s.ok ? "✔" : "✖"}</span> {s.label}
                {s.call ? <span className="mono call"> {s.call}</span> : null}
                {s.status !== undefined ? <span className="code"> HTTP {s.status}</span> : null}
                {s.ms !== undefined ? <span className="hint"> {s.ms} ms</span> : null}
                {s.note ? <span className="note"> — {s.note}</span> : null}
              </summary>
              {s.body !== undefined ? <pre>{typeof s.body === "string" ? s.body : JSON.stringify(s.body, null, 2)}</pre> : null}
            </details>
          </li>
        ))}
      </ol>
    </section>
  );
}
