import { PageHeader } from "@/components/page-header";
import { BulkTester } from "./bulk-tester";

export default function BulkPage() {
  return (
    <>
      <PageHeader
        title="Bulk portal"
        description={
          <>
            The org portal API (<code className="font-mono text-xs">/portal/org/v1</code>, realm portals): an organisation&apos;s maker uploads a disbursement file, a
            checker of the same org approves it (never the maker), mw-portal-api pays each row as a throttled child transaction and writes
            the result file. A service provider is an organisation: its users see the transactions it initiated (bulk) or collects as payee (org-checker02: DESCO bills, Demo Shop payments).
          </>
        }
      />
      <BulkTester />
    </>
  );
}
