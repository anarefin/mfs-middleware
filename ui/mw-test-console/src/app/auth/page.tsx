import { PageHeader } from "@/components/page-header";
import { AuthTester } from "./auth-tester";

export default function AuthPage() {
  return (
    <>
      <PageHeader
        title="Wallet auth"
        description={
          <>
            mw-auth&apos;s public API (<code className="font-mono text-xs">/auth/v1</code>) on a fresh wallet of its own: OTP by SMS, device binding,
            the first PIN, the PIN login, a second device, the PIN change, the self-service and the care reset, the lockout. Every PIN is
            encrypted under the mw-crypto transport key; mw-auth never sees it in clear.
          </>
        }
      />
      <AuthTester />
    </>
  );
}
