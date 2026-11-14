import { KeyRound, LockOpen } from "lucide-react";
import { auth } from "@/auth";
import { PageHeader } from "@/components/page-header";
import { Button } from "@/components/ui/button";
import { CardContent, CardDescription, CardHeader, CardSection, CardTitle } from "@/components/ui/card";
import { Field } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { canMake } from "@/lib/approvals";
import { ActionForm } from "../action-form";
import { requestPinReset, requestWalletUnlock } from "../actions";

// Wallet unlock (ticket 52) and care PIN reset (ticket 70), both with maker-checker. The ops-maker
// files the request here; an ops-checker approves it in the approvals inbox. An unlock lifts
// mw-auth's PIN lock (auth.wallet-lock.v1) and the core's own lock (UnlockWallet); a PIN reset asks
// mw-auth to open a reset, and the customer sets a new PIN in the app with a reset OTP.

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
            Clears the PIN lockout (every level) and the core&apos;s lock. Nothing changes until a
            different operator (ops-checker) approves the request in Approvals.
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
      <CardSection aria-label="PIN reset" className="max-w-3xl">
        <CardHeader className="flex-col items-start gap-1">
          <CardTitle><KeyRound />Care PIN reset</CardTitle>
          <CardDescription>
            For a PIN locked until a reset or a lost phone, after the customer&apos;s identity was
            checked. Once a different operator (ops-checker) approves it, the customer gets an SMS and
            sets a new PIN in the app with a reset code (72 h).
          </CardDescription>
        </CardHeader>
        <CardContent>
          {maker ? (
            <ActionForm action={requestPinReset} className="flex flex-col gap-3" label="PIN reset request">
              <div className="grid grid-cols-[repeat(auto-fill,minmax(220px,1fr))] items-end gap-3">
                <Field label="Wallet (MSISDN)"><Input name="walletId" required placeholder="01XXXXXXXXX" inputMode="tel" autoComplete="off" className="font-mono" /></Field>
                <Field label="Reason"><Input name="reason" required maxLength={500} /></Field>
                <Button type="submit" className="self-end"><KeyRound />Request PIN reset</Button>
              </div>
            </ActionForm>
          ) : (
            <p className="m-0 text-sm text-muted-foreground">Only an ops-maker files PIN reset requests.</p>
          )}
        </CardContent>
      </CardSection>
    </>
  );
}
