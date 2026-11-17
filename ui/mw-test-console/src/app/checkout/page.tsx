import { FillScope } from "@/components/fill-scope";
import { HowToTest } from "@/components/how-to-test";
import { PageHeader } from "@/components/page-header";
import { CheckoutTester } from "./checkout-tester";

export default function CheckoutPage() {
  return (
    <>
      <PageHeader
        title="Web checkout"
        description={
          <>
            A web shop takes a Bits payment (mw-checkout). Pick one of the three dev merchants (Karim Traders, Lalbagh Pharmacy, Gulshan
            Fashion House); the shop signs its API calls with that merchant&apos;s key (APISIX hmac-auth); the buyer pays with an SMS OTP and the PIN; the shop verifies the signed redirect and the signed IPN its
            server receives (the simulator plays the shop server and retries until it answers 200).
          </>
        }
      />
      <HowToTest page="checkout" />
      <FillScope page="checkout">
        <CheckoutTester />
      </FillScope>
    </>
  );
}
