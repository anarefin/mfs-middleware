import { CircleAlert, Search, SearchX } from "lucide-react";
import Link from "next/link";
import { redirect } from "next/navigation";
import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/status-badge";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardFooter } from "@/components/ui/card";
import { Field } from "@/components/ui/field";
import { Input, NativeSelect } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
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
      <PageHeader title="Transactions" description="Search by transaction ID, MSISDN, partner reference, status, type or date." />

      <Card>
        <form className="grid grid-cols-[repeat(auto-fill,minmax(190px,1fr))] items-end gap-3 p-4" method="get" action={`${BASE_PATH}/transactions`} role="search">
          <Field label="Transaction ID"><Input name="txnId" defaultValue={form.txnId} placeholder="UUID" className="font-mono" /></Field>
          <Field label="MSISDN"><Input name="msisdn" defaultValue={form.msisdn} placeholder="01XXXXXXXXX" inputMode="tel" /></Field>
          <Field label="Partner ref"><Input name="partnerRef" defaultValue={form.partnerRef} /></Field>
          <Field label="Status">
            <NativeSelect name="status" defaultValue={form.status}>
              <option value="">any</option>
              {STATUSES.map((s) => <option key={s} value={s}>{s}</option>)}
            </NativeSelect>
          </Field>
          <Field label="Type"><Input name="txnType" defaultValue={form.txnType} placeholder="P2P, TOPUP..." /></Field>
          <Field label="From (UTC)"><Input type="date" name="from" defaultValue={form.from} /></Field>
          <Field label="To (UTC)"><Input type="date" name="to" defaultValue={form.to} /></Field>
          <Button type="submit"><Search />Search</Button>
        </form>
      </Card>

      {errors.length > 0 ? (
        <Alert variant="destructive" className="error" role="alert">
          <CircleAlert />
          <AlertDescription>
            <ul>{errors.map((e) => <li key={e.field}>{e.field}: {e.issue}</li>)}</ul>
          </AlertDescription>
        </Alert>
      ) : null}

      {!parsed.submitted ? (
        <div className="flex flex-col items-center gap-2 rounded-lg border border-dashed px-6 py-12 text-center text-muted-foreground">
          <Search className="size-6" />
          <p className="m-0">Fill in at least one field and search. Press <kbd className="rounded border px-1 text-xs">⌘K</kbd> anywhere to jump straight to a transaction ID.</p>
        </div>
      ) : null}

      {result && !result.ok ? <ApiError status={result.status} error={result.error} /> : null}

      {result?.ok ? (
        <Card className="overflow-hidden">
          <div className="flex items-center gap-2 border-b px-4 py-3">
            <h2 className="m-0 text-sm font-semibold">{result.data.items.length === 0 ? "No transactions found" : `Results (${parsed.offset + 1}-${parsed.offset + result.data.items.length})`}</h2>
          </div>
          {result.data.items.length > 0 ? (
            <Table data-testid="results">
              <TableHeader>
                <TableRow><TableHead>Created</TableHead><TableHead>Transaction ID</TableHead><TableHead>Type</TableHead><TableHead>Channel</TableHead><TableHead>Status</TableHead><TableHead className="text-right">Amount</TableHead><TableHead>Initiator</TableHead><TableHead>Partner ref</TableHead></TableRow>
              </TableHeader>
              <TableBody>
                {result.data.items.map((t) => (
                  <TableRow key={t.txnId}>
                    <TableCell className="whitespace-nowrap text-muted-foreground tabular-nums">{formatTime(t.createdAt)}</TableCell>
                    <TableCell className="font-mono text-xs"><Link href={`/transactions/${t.txnId}`}>{t.txnId}</Link></TableCell>
                    <TableCell>{t.txnType}</TableCell>
                    <TableCell className="text-muted-foreground">{t.channel}</TableCell>
                    <TableCell><StatusBadge status={t.status} /></TableCell>
                    <TableCell className="text-right whitespace-nowrap tabular-nums">{formatAmount(t.amount)}</TableCell>
                    <TableCell className="font-mono text-xs">{partyLabel(t.initiator)}</TableCell>
                    <TableCell className="font-mono text-xs text-muted-foreground">{t.partnerRef ?? ""}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          ) : (
            <div className="flex flex-col items-center gap-2 px-6 py-10 text-muted-foreground"><SearchX className="size-6" />Nothing matches these filters.</div>
          )}
          <CardFooter className="justify-end">
            {parsed.offset > 0 ? <Button asChild variant="outline" size="sm"><Link href={pageHref(form, Math.max(0, parsed.offset - parsed.limit))}>Previous</Link></Button> : null}
            {result.data.items.length === parsed.limit ? <Button asChild variant="outline" size="sm"><Link href={pageHref(form, parsed.offset + parsed.limit)}>Next</Link></Button> : null}
          </CardFooter>
        </Card>
      ) : null}
    </>
  );
}
