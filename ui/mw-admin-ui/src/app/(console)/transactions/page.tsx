import Link from "next/link";
import { redirect } from "next/navigation";
import { formatAmount, formatTime, partyLabel } from "@/lib/format";
import { pageHref, parseSearch, STATUSES } from "@/lib/search";
import { adminApi, operatorToken } from "@/lib/server/admin";
import { BASE_PATH } from "@/lib/server/env";
import { ApiError } from "../api-error";

type Props = { searchParams: Promise<Record<string, string | string[] | undefined>> };

export default async function TransactionsPage({ searchParams }: Props) {
  const parsed = parseSearch(await searchParams);
  const { form, errors } = parsed;

  let result: Awaited<ReturnType<ReturnType<typeof adminApi>["searchTransactions"]>> | null = null;
  if (parsed.submitted && errors.length === 0) {
    const token = await operatorToken();
    if (!token) redirect("/login?error=SessionExpired");
    result = await adminApi().searchTransactions(token, parsed.query);
  }

  return (
    <>
      <h1>Transactions</h1>
      <form className="panel search" method="get" action={`${BASE_PATH}/transactions`} role="search">
        <label>Transaction ID<input name="txnId" defaultValue={form.txnId} placeholder="UUID" /></label>
        <label>MSISDN<input name="msisdn" defaultValue={form.msisdn} placeholder="01XXXXXXXXX" inputMode="tel" /></label>
        <label>Partner ref<input name="partnerRef" defaultValue={form.partnerRef} /></label>
        <label>Status
          <select name="status" defaultValue={form.status}>
            <option value="">any</option>
            {STATUSES.map((s) => <option key={s} value={s}>{s}</option>)}
          </select>
        </label>
        <label>Type<input name="txnType" defaultValue={form.txnType} placeholder="P2P, TOPUP..." /></label>
        <label>From (UTC)<input type="date" name="from" defaultValue={form.from} /></label>
        <label>To (UTC)<input type="date" name="to" defaultValue={form.to} /></label>
        <button className="primary" type="submit">Search</button>
      </form>

      {errors.length > 0 ? (
        <div className="error" role="alert">
          <ul>{errors.map((e) => <li key={e.field}>{e.field}: {e.issue}</li>)}</ul>
        </div>
      ) : null}

      {!parsed.submitted ? <p className="hint">Search by transaction ID, MSISDN, partner reference, status, type or date.</p> : null}

      {result && !result.ok ? <ApiError status={result.status} error={result.error} /> : null}

      {result?.ok ? (
        <>
          <h2>{result.data.items.length === 0 ? "No transactions found" : `Results (${parsed.offset + 1}-${parsed.offset + result.data.items.length})`}</h2>
          {result.data.items.length > 0 ? (
            <table data-testid="results">
              <thead>
                <tr><th>Created</th><th>Transaction ID</th><th>Type</th><th>Channel</th><th>Status</th><th>Amount</th><th>Initiator</th><th>Partner ref</th></tr>
              </thead>
              <tbody>
                {result.data.items.map((t) => (
                  <tr key={t.txnId}>
                    <td>{formatTime(t.createdAt)}</td>
                    <td className="mono"><Link href={`/transactions/${t.txnId}`}>{t.txnId}</Link></td>
                    <td>{t.txnType}</td>
                    <td>{t.channel}</td>
                    <td className={`status ${t.status}`}>{t.status}</td>
                    <td>{formatAmount(t.amount)}</td>
                    <td>{partyLabel(t.initiator)}</td>
                    <td className="mono">{t.partnerRef ?? ""}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : null}
          <div className="pager">
            {parsed.offset > 0 ? <Link href={pageHref(form, Math.max(0, parsed.offset - parsed.limit))}>Previous</Link> : null}
            {result.data.items.length === parsed.limit ? <Link href={pageHref(form, parsed.offset + parsed.limit)}>Next</Link> : null}
          </div>
        </>
      ) : null}
    </>
  );
}
