import { BulkTester } from "./bulk-tester";

export default function BulkPage() {
  return (
    <>
      <h1>Bulk portal</h1>
      <p className="lead">
        The org portal API (<code>/portal/org/v1</code>, realm portals): an organisation&apos;s maker uploads a disbursement file, a
        checker of the same org approves it (never the maker), mw-portal-api pays each row as a throttled child transaction and writes
        the result file. A service provider (sp-viewer01) sees the transactions it initiated or received.
      </p>
      <BulkTester />
    </>
  );
}
