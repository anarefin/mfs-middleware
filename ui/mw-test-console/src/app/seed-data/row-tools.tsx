"use client";
// The account and wallet tools on a seed data row: a button per allowed action, a confirm dialog
// for every change, the result inline (the app's Result view).

import { Loader2, X } from "lucide-react";
import { useState, useTransition } from "react";
import { runUserTool, runWalletTool } from "@/app/actions/accounts";
import { Button } from "@/components/ui/button";
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogTitle } from "@/components/ui/dialog";
import { Field } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { USER_ACTIONS, USER_ACTION_TEXT, WALLET_ACTIONS, WALLET_ACTION_TEXT, type KcRealm, type UserAction, type WalletAction } from "@/lib/account-tools";
import type { Outcome } from "@/lib/types";
import { Result } from "../components/result";

type Pending = { key: string; label: string; confirm: string; withBalance?: boolean; run: (balance: string) => Promise<Outcome> };

function Tools({ id, buttons }: { id: string; buttons: (Pending & { direct?: boolean })[] }) {
  const [ask, setAsk] = useState<Pending | null>(null);
  const [balance, setBalance] = useState("5000");
  const [out, setOut] = useState<Outcome | null>(null);
  const [busy, start] = useTransition();

  const go = (p: Pending, b: string) => {
    setAsk(null);
    start(async () => {
      try {
        setOut(await p.run(b));
      } catch (e) {
        setOut({ ok: false, headline: (e as Error).message || "the console did not answer", steps: [] });
      }
    });
  };

  return (
    <div className="flex min-w-0 flex-col gap-2">
      <div className="flex flex-wrap gap-1">
        {buttons.map((b) => (
          <Button key={b.key} type="button" size="sm" variant="outline" disabled={busy} data-testid={`tool-${b.key}-${id}`}
            onClick={() => (b.direct ? go(b, "") : setAsk(b))}>
            {b.label}
          </Button>
        ))}
        {busy ? <Loader2 className="size-4 animate-spin self-center text-info" aria-label="working" /> : null}
      </div>
      {out ? (
        <div className="relative max-w-xl rounded-md border p-2" data-testid={`tool-result-${id}`}>
          <Button type="button" variant="ghost" size="icon-sm" className="absolute top-1 right-1" aria-label="Close result" onClick={() => setOut(null)}><X /></Button>
          <Result state={out} />
        </div>
      ) : null}
      <Dialog open={!!ask} onOpenChange={(o) => !o && setAsk(null)}>
        <DialogContent>
          <div className="flex flex-col gap-3 p-5">
            <DialogTitle className="m-0 text-base font-semibold">{ask?.label}: {id}?</DialogTitle>
            <DialogDescription className="m-0 text-sm text-muted-foreground">{ask?.confirm}</DialogDescription>
            {ask?.withBalance ? (
              <Field label="Balance (Tk)"><Input value={balance} onChange={(e) => setBalance(e.target.value)} className="font-mono" data-testid="tool-balance" /></Field>
            ) : null}
            <div className="flex justify-end gap-2">
              <DialogClose asChild><Button type="button" variant="outline">Cancel</Button></DialogClose>
              <Button type="button" data-testid="tool-confirm" onClick={() => ask && go(ask, balance)}>Confirm</Button>
            </div>
          </div>
        </DialogContent>
      </Dialog>
    </div>
  );
}

/** mw-auth status / unlock / reset PIN / unbind, core-mock balance: for one wallet. */
export function WalletTools({ walletId, actions = WALLET_ACTIONS }: { walletId: string; actions?: readonly WalletAction[] }) {
  return (
    <Tools
      id={walletId}
      buttons={actions.map((a) => ({
        key: a, label: WALLET_ACTION_TEXT[a].label, confirm: WALLET_ACTION_TEXT[a].confirm ?? "", direct: WALLET_ACTION_TEXT[a].confirm === null,
        withBalance: a === "setBalance", run: (b: string) => runWalletTool(a, walletId, b),
      }))}
    />
  );
}

/** Keycloak unlock / reset password / reset TOTP: for one ops or portals user. */
export function UserTools({ realm, username, actions = USER_ACTIONS }: { realm: KcRealm; username: string; actions?: readonly UserAction[] }) {
  return (
    <Tools
      id={username}
      buttons={actions.map((a) => ({ key: a, label: USER_ACTION_TEXT[a].label, confirm: USER_ACTION_TEXT[a].confirm, run: () => runUserTool(a, realm, username) }))}
    />
  );
}
