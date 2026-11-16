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
            A handset dialling the Bits USSD service. The USSD gateway simulator plays the MNO: robi talks to mw-ussd over HTTP
            (APISIX <code className="font-mono text-[0.9em]">/ussd/robi</code>, basic auth), gp over SMPP (mw-ussd is bound to the simulator). Same menus, same engine;
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
