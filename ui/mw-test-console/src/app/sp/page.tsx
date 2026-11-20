import { FillScope } from "@/components/fill-scope";
import { HowToTest } from "@/components/how-to-test";
import { PageHeader } from "@/components/page-header";
import { SpTester } from "./sp-tester";

export default function SpPage() {
  return (
    <>
      <PageHeader
        title="SP portal"
        description={
          <>
            The service provider portal API (<code className="font-mono text-[0.9em]">/portal/sp/v1</code>, realm portals, role sp-viewer): a
            service provider sees only its own organisation&apos;s transactions, those it initiated or collects as payee (ORG002: DESCO bills,
            Karim Traders payments), and a CSV report of them.
          </>
        }
      />
      <HowToTest page="sp" />
      <FillScope page="sp">
        <SpTester />
      </FillScope>
    </>
  );
}
