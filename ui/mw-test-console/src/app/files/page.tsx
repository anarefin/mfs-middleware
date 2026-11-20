import { FillScope } from "@/components/fill-scope";
import { HowToTest } from "@/components/how-to-test";
import { PageHeader } from "@/components/page-header";
import { FileForms } from "./forms";

export default function Files() {
  return (
    <>
      <PageHeader
        title="Files"
        description={
          <>
            The file-based partners of mw-simulators on partner-sftp (ticket 123): the AML vendor answers transaction files, the
            trust bank writes MT940 / MT942 statements from the core ledger, the Data Warehouse checks each daily drop. Run one
            now, add a file fault (missing, late, duplicate, corrupt, mismatch), then read the journal: every file with its sha256.
          </>
        }
      />
      <HowToTest page="files" />
      <FillScope page="files">
        <FileForms />
      </FillScope>
    </>
  );
}
