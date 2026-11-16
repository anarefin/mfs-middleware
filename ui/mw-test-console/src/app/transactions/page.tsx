import { FillScope } from "@/components/fill-scope";
import { HowToTest } from "@/components/how-to-test";
import { PageHeader } from "@/components/page-header";
import { initialTxnId } from "@/lib/txn-query";
import { Explorer } from "./explorer";

export const dynamic = "force-dynamic";

export default async function TransactionsPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const sp = await searchParams;
  const txnId = initialTxnId(sp.txnId);
  return (
    <>
      <PageHeader
        title="Transactions"
        description={
          <>
            Search any transaction by txnId or MSISDN through mw-admin-api (<code className="font-mono text-[0.9em]">/admin/v1/transactions</code>,
            signed in as the ops test user <code className="font-mono text-[0.9em]">ops-e2e-maker01</code>) and open it: status, amounts, the
            parties (masked), the saga timeline, the partner calls and the audit trail. Every txnId in a result on the other pages links here.
          </>
        }
      />
      <HowToTest page="transactions" />
      <FillScope page="transactions">
        <Explorer initialTxnId={txnId} />
      </FillScope>
    </>
  );
}
