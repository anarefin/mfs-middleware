"use client";

import { Search } from "lucide-react";
import { useActionState } from "react";
import { lookupWallet } from "@/app/actions/accounts";
import { Row } from "@/components/section";
import { Button } from "@/components/ui/button";
import { Field } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { formatMinor } from "@/lib/format";
import { WalletTools } from "./row-tools";

/** MSISDN -> core-mock holder name, type and balance; the wallet tools for any number typed in. */
export function WalletLookup() {
  const [s, run, pending] = useActionState(lookupWallet, null);
  return (
    <div className="flex flex-col gap-3">
      <form action={run}>
        <Row>
          <Field label="Wallet (MSISDN)"><Input name="msisdn" placeholder="01711000123" className="font-mono" data-testid="lookup-msisdn" /></Field>
          <Button type="submit" variant="outline" disabled={pending} data-testid="lookup-go"><Search />Look up</Button>
        </Row>
      </form>
      {s && !s.ok ? <p className="m-0 text-sm text-bad" data-testid="lookup-result">{s.error}</p> : null}
      {s && s.ok ? (
        <div className="flex flex-col gap-2 rounded-md border p-3 text-sm" data-testid="lookup-result">
          <p className="m-0">
            <code className="font-mono">{s.msisdn}</code>: <span className="font-medium">{s.holderName ?? "no holder name"}</span>
            {" · "}{s.type ?? "?"}{" · "}<span className="tabular-nums">{s.balanceMinor === undefined ? "-" : formatMinor(s.balanceMinor)}</span>
            {s.range ? <span className="text-muted-foreground"> ({s.range})</span> : null}
          </p>
          <WalletTools walletId={s.msisdn} />
        </div>
      ) : null}
    </div>
  );
}
