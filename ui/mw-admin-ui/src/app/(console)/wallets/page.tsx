import { auth } from "@/auth";
import { canMake } from "@/lib/approvals";
import { ActionForm } from "../action-form";
import { requestWalletUnlock } from "../actions";

// Wallet unlock (ticket 52): a wallet locked after 5 wrong PINs is unlocked with maker-checker.
// The ops-maker files the request here; an ops-checker approves it in the approvals inbox, and the
// approval sends UnlockWallet to the core (which refuses it when the wallet is not locked).

export default async function WalletsPage() {
  const session = await auth();
  const maker = canMake(session?.roles ?? []);
  return (
    <>
      <h1>Wallets</h1>
      <section className="panel" aria-label="Unlock wallet">
        <h2>Unlock a PIN-locked wallet</h2>
        <p className="hint">
          Clears the lockout after 5 wrong PINs. Nothing changes until a different operator
          (ops-checker) approves the request in Approvals.
        </p>
        {maker ? (
          <ActionForm action={requestWalletUnlock} className="search" label="Unlock request">
            <label>Wallet (MSISDN)<input name="walletId" required placeholder="01XXXXXXXXX" inputMode="tel" autoComplete="off" /></label>
            <label>Reason<input name="reason" required maxLength={500} /></label>
            <button className="primary" type="submit">Request unlock</button>
          </ActionForm>
        ) : (
          <p className="hint">Only an ops-maker files unlock requests.</p>
        )}
      </section>
    </>
  );
}
