import { FillScope } from "@/components/fill-scope";
import { HowToTest } from "@/components/how-to-test";
import { PageHeader } from "@/components/page-header";
import { AppTester } from "./app-tester";

export default function AppPage() {
  return (
    <>
      <PageHeader
        title="App flows"
        description={
          <>
            Plays the Bits mobile app through the app API (<code className="font-mono text-[0.9em]">/app/v1</code>): the mw-auth login (MSISDN + PIN), device binding with an SMS OTP,
            every request signed with the device key, the PIN encrypted for mw-auth, which signs the assertion the core checks. Each submit notes the core balances, follows the
            transaction to its final status, compares the balances and lists the SMS. Click its txnId for the timeline, partner calls and audit trail
            (Transactions), or open it in the ops console.
          </>
        }
      />
      <HowToTest page="app" />
      <FillScope page="app">
        <AppTester />
      </FillScope>
    </>
  );
}
