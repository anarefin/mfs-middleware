"use client";
// The live TOTP code of a seeded login: the server action computes it from the persona's pre-set
// secret; it is fetched again at each 30 s boundary, with a countdown in between.

import { useCallback, useEffect, useState } from "react";
import { currentTotp } from "@/app/actions/credentials";
import { CopyButton } from "@/components/copy-button";
import { totpWindow, type TotpNow } from "@/lib/totp-window";

export function TotpCode({ username, testId }: { username: string; testId?: string }) {
  const [now, setNow] = useState<TotpNow | null>(null);
  const [left, setLeft] = useState<number>(() => totpWindow().remainingS);

  const load = useCallback(async () => {
    try {
      setNow(await currentTotp(username));
    } catch {
      setNow({ ok: false, error: "the console did not answer" });
    }
  }, [username]);

  useEffect(() => {
    void load();
    let step = totpWindow().step;
    const id = setInterval(() => {
      const w = totpWindow();
      setLeft(w.remainingS);
      if (w.step !== step) {
        step = w.step;
        void load();
      }
    }, 500);
    return () => clearInterval(id);
  }, [load]);

  if (!now) return <span className="text-xs text-muted-foreground">…</span>;
  if (!now.ok) return <span className="text-xs text-muted-foreground" data-testid={testId ? `${testId}-none` : undefined}>{now.error}</span>;
  return (
    <span className="inline-flex items-center gap-1.5">
      <code className="rounded bg-muted px-1.5 py-0.5 font-mono text-sm font-semibold tracking-widest tabular-nums" data-testid={testId}>{now.code}</code>
      <span className={left <= 5 ? "text-xs tabular-nums text-warn" : "text-xs tabular-nums text-muted-foreground"} aria-label="seconds left">{left}s</span>
      <CopyButton value={now.code} label="Copy code" />
    </span>
  );
}
