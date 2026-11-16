"use client";
// Who is signed in, at a glance: holder name, MSISDN, the live core-mock balance and held amount,
// and this console's device and mw-auth session for the user. Reloaded on a user change, after
// every submit (refreshKey) and with the refresh button; reading it never binds or logs in.

import { RefreshCw, Smartphone } from "lucide-react";
import { useEffect, useState, useTransition } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { formatMinor } from "@/lib/format";
import { walletCard, type WalletCard } from "../actions/app";

function ago(ms: number): string {
  const s = Math.max(0, Math.round(ms / 1000));
  return s < 60 ? `${s} s` : `${Math.floor(s / 60)} min ${s % 60} s`;
}

export function SignedInCard({ user, name, refreshKey }: { user: string; name?: string; refreshKey: number }) {
  const [card, setCard] = useState<WalletCard | null>(null);
  const [loading, start] = useTransition();
  const load = () => start(async () => setCard(await walletCard(user)));
  useEffect(() => {
    if (!user) {
      setCard(null);
      return;
    }
    start(async () => setCard(await walletCard(user)));
  }, [user, refreshKey]);

  const d = card?.ok && card.user === user ? card.device : undefined;
  return (
    <div className="flex flex-col gap-2 rounded-lg border bg-muted/40 px-3 py-2.5" data-testid="signed-in-card">
      <div className="flex items-start gap-2">
        <div className="min-w-0 flex-1">
          <p className="m-0 truncate font-medium" data-testid="card-name">{name ?? "Unnamed wallet"}</p>
          <p className="m-0 font-mono text-sm text-muted-foreground" data-testid="card-msisdn">{(card?.user === user ? card.msisdn : undefined) ?? (user || "no user")}</p>
        </div>
        <Button type="button" variant="ghost" size="icon-sm" onClick={load} disabled={loading || !user} aria-label="refresh the card" data-testid="card-refresh">
          <RefreshCw className={loading ? "animate-spin" : undefined} />
        </Button>
      </div>
      {card && !card.ok ? <p className="m-0 text-sm text-bad">{card.error}</p> : null}
      <dl className="m-0 grid grid-cols-2 gap-x-3 gap-y-1 text-sm">
        <dt className="text-muted-foreground">Balance</dt>
        <dd className="m-0 text-right font-medium tabular-nums" data-testid="card-balance">
          {card?.ok ? (card.balanceMinor == null ? "not in core-mock" : formatMinor(card.balanceMinor)) : "…"}
        </dd>
        <dt className="text-muted-foreground">Held</dt>
        <dd className="m-0 text-right tabular-nums" data-testid="card-held">{card?.ok && card.heldMinor != null ? formatMinor(card.heldMinor) : "-"}</dd>
      </dl>
      <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
        <Smartphone className="size-4" />
        {d?.bound ? <Badge tone="good" data-testid="card-device">device bound</Badge> : <Badge tone="neutral" data-testid="card-device">no device yet</Badge>}
        {d?.session && d.loggedInAt && card ? (
          <span data-testid="card-session">logged in {ago(card.at - d.loggedInAt)} ago</span>
        ) : (
          <span data-testid="card-session">no live session (the next call logs in)</span>
        )}
      </div>
    </div>
  );
}
