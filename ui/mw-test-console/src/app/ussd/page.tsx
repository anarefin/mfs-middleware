import { FillScope } from "@/components/fill-scope";
import { HowToTest } from "@/components/how-to-test";
import { PageHeader } from "@/components/page-header";
import { Phone } from "./phone";

export default function UssdPage() {
  return (
    <>
      <PageHeader
        title="USSD phone"
        description={
          <>
            A handset dialling the Bits USSD service. The USSD gateway simulator plays the MNO: robi, bl and teletalk talk to mw-ussd over HTTP
            (APISIX <code className="font-mono text-[0.9em]">/ussd/&lt;mno&gt;</code>, basic auth), gp over SMPP (mw-ussd is bound to the simulator). Each MNO has its own
            code (gp *268#, robi *269#, bl *270#, teletalk *271#); mw-ussd ends a dial with another MNO&apos;s code at once. Same menus, same engine;
            only the transport differs. Money moved by a USSD flow shows on the Home page balances and in the ops console.
          </>
        }
      />
      <HowToTest page="ussd" />
      <FillScope page="ussd">
        <Phone />
      </FillScope>
    </>
  );
}
