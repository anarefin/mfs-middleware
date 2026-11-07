import { AppTester } from "./app-tester";

export default function AppPage() {
  return (
    <>
      <h1>App flows</h1>
      <p className="lead">
        Plays the Bits mobile app through the app API (<code>/app/v1</code>): Keycloak sign-in, device binding with an SMS OTP,
        every request signed with the device key, the PIN encrypted for the core. Each submit notes the core balances, follows the
        transaction to its final status, compares the balances and lists the SMS. Open the transaction in the ops console for its
        timeline, partner calls and audit trail.
      </p>
      <AppTester />
    </>
  );
}
