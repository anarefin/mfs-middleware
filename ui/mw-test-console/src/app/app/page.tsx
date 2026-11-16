import { FillScope } from "@/components/fill-scope";
import { HowToTest } from "@/components/how-to-test";
import { PageHeader } from "@/components/page-header";
import { knownAppUsers } from "@/lib/server/tokens";
import { AppTester } from "./app-tester";

export const dynamic = "force-dynamic";

export default async function AppPage() {
  // the PIN-ready app wallets of mw-seeder's manifest (the core-mock personas only without one)
  const users = await knownAppUsers();
  return (
    <>
      <PageHeader
        title="App flows"
        description={
          <>
            Plays the Bits mobile app through the app API (<code className="font-mono text-[0.9em]">/app/v1</code>): the mw-auth login (MSISDN + PIN), device binding with an SMS OTP,
            every request signed with the device key, the PIN encrypted for mw-auth, which signs the assertion the core checks. Each submit notes the core balances, follows the
            transaction to its final status, compares the balances and lists the SMS. Click its txnId for the timeline, partner calls and audit trail
            (Transactions), or open it in the ops console. History lists the signed-in wallet's transactions, sent and received.
          </>
        }
      />
      <HowToTest page="app" />
      <FillScope page="app">
        <AppTester users={users} />
      </FillScope>
    </>
  );
}
