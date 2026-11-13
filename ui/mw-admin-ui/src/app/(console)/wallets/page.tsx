import { LockOpen } from "lucide-react";
import { auth } from "@/auth";
import { PageHeader } from "@/components/page-header";
import { Button } from "@/components/ui/button";
import { CardContent, CardDescription, CardHeader, CardSection, CardTitle } from "@/components/ui/card";
import { Field } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
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
      <PageHeader title="Wallets" />
      <CardSection aria-label="Unlock wallet" className="max-w-3xl">
        <CardHeader className="flex-col items-start gap-1">
          <CardTitle><LockOpen />Unlock a PIN-locked wallet</CardTitle>
          <CardDescription>
            Clears the lockout after 5 wrong PINs. Nothing changes until a different operator
            (ops-checker) approves the request in Approvals.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {maker ? (
            <ActionForm action={requestWalletUnlock} className="flex flex-col gap-3" label="Unlock request">
              <div className="grid grid-cols-[repeat(auto-fill,minmax(220px,1fr))] items-end gap-3">
                <Field label="Wallet (MSISDN)"><Input name="walletId" required placeholder="01XXXXXXXXX" inputMode="tel" autoComplete="off" className="font-mono" /></Field>
                <Field label="Reason"><Input name="reason" required maxLength={500} /></Field>
                <Button type="submit" className="self-end"><LockOpen />Request unlock</Button>
              </div>
            </ActionForm>
          ) : (
            <p className="m-0 text-sm text-muted-foreground">Only an ops-maker files unlock requests.</p>
          )}
        </CardContent>
      </CardSection>
    </>
  );
}
