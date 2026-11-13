import { PageHeader } from "@/components/page-header";
import { CheckoutTester } from "./checkout-tester";

export default function CheckoutPage() {
  return (
    <>
      <PageHeader
        title="Web checkout"
        description={
          <>
            A web shop takes a Bits payment (mw-checkout). The shop signs its API calls (APISIX hmac-auth, merchant <code className="font-mono text-xs">merchant-demo</code>,
            wallet 01911000001); the buyer pays with an SMS OTP and the PIN; the shop verifies the signed redirect and the signed IPN its
            server receives (the simulator plays the shop server and retries until it answers 200).
          </>
        }
      />
      <CheckoutTester />
    </>
  );
}
