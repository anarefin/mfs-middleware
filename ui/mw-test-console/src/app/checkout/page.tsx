import { CheckoutTester } from "./checkout-tester";

export default function CheckoutPage() {
  return (
    <>
      <h1>Web checkout</h1>
      <p className="lead">
        A web shop takes a Bits payment (mw-checkout). The shop signs its API calls (APISIX hmac-auth, merchant <code>merchant-demo</code>,
        wallet 01911000001); the buyer pays with an SMS OTP and the PIN; the shop verifies the signed redirect and the signed IPN its
        server receives (the simulator plays the shop server and retries until it answers 200).
      </p>
      <CheckoutTester />
    </>
  );
}
