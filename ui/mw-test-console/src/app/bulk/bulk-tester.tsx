"use client";

import { useActionState, useEffect, useState } from "react";
import { PORTAL_PERSONAS } from "@/lib/personas";
import { bulk } from "../actions/bulk";
import { Result } from "../components/result";

export function BulkTester() {
  const [state, run, pending] = useActionState(bulk, null);
  const [csv, setCsv] = useState("");
  const [batchId, setBatchId] = useState("");
  const [user, setUser] = useState("org-maker01");
  // a generated sample or a new batch id lands in its field once, when that answer arrives
  useEffect(() => {
    const data = state?.data ?? {};
    if (typeof data.csv === "string") setCsv(data.csv);
    if (typeof data.batchId === "string") setBatchId(data.batchId);
  }, [state]);

  return (
    <div className="cols">
      <div className="stack">
      {/* outside the form: React resets a form after its action, which would put the select back on
          its first option while the state (and the button label) still names the chosen user */}
      <section className="panel stack">
        <h2>Portal user</h2>
        <select value={user} onChange={(e) => setUser(e.target.value)} aria-label="Portal user" data-testid="bulk-user">
          {PORTAL_PERSONAS.map((p) => <option key={p.user} value={p.user}>{p.label}</option>)}
        </select>
      </section>
      <form action={run} className="stack" aria-busy={pending}>
        <input type="hidden" name="user" value={user} />
        <section className="panel stack">
          <h2>1. File (maker)</h2>
          <div className="row">
            <label>Rows<input name="rows" defaultValue="5" inputMode="numeric" /></label>
            <label>Tk each<input name="taka" defaultValue="1.00" /></label>
            <button type="submit" name="op" value="sample" disabled={pending}>Generate sample</button>
          </div>
          <label>CSV (msisdn,amount,reference)<textarea name="csv" value={csv} onChange={(e) => setCsv(e.target.value)} data-testid="bulk-csv" /></label>
          <button type="submit" name="op" value="upload" className="primary" disabled={pending} data-testid="bulk-upload">Upload as {user}</button>
        </section>
        <section className="panel stack">
          <h2>2. Decide and follow (checker)</h2>
          <label>Batch id<input name="batchId" value={batchId} onChange={(e) => setBatchId(e.target.value)} data-testid="bulk-batch" /></label>
          <div className="row">
            <button type="submit" name="op" value="approve" className="primary" disabled={pending} data-testid="bulk-approve">Approve</button>
            <button type="submit" name="op" value="reject" className="danger" disabled={pending}>Reject</button>
            <button type="submit" name="op" value="pause" disabled={pending}>Pause</button>
            <button type="submit" name="op" value="resume" disabled={pending}>Resume</button>
          </div>
          <label>Reject reason<input name="reason" placeholder="wrong payroll month" /></label>
          <div className="row">
            <button type="submit" name="op" value="get" disabled={pending} data-testid="bulk-get">Batch status</button>
            <button type="submit" name="op" value="result" disabled={pending}>Result file</button>
            <button type="submit" name="op" value="txns" disabled={pending}>Transactions</button>
            <button type="submit" name="op" value="list" disabled={pending}>List batches</button>
          </div>
          <p className="hint">Switch the user to org-checker01 to approve org-maker01&apos;s upload. SP query: org-checker02 (ORG002 collects for DESCO and Demo Shop), empty batch id, Transactions.</p>
        </section>
      </form>
      </div>
      <div>
        {pending ? <p className="busy">working…</p> : null}
        {state ? <section className="panel"><Result state={state} /></section> : <section className="panel"><p className="hint">Generate a sample file, upload it as the maker, approve it as the checker.</p></section>}
      </div>
    </div>
  );
}
