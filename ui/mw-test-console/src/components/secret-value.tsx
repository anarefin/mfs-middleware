"use client";
// A dev secret from deploy/.env, masked by default, with show and copy. A missing value says so.

import { Eye, EyeOff } from "lucide-react";
import { useState } from "react";
import { CopyButton } from "@/components/copy-button";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

export function SecretValue({ value, envName, masked = true, testId, className }: { value?: string; envName?: string; masked?: boolean; testId?: string; className?: string }) {
  const [shown, setShown] = useState(!masked);
  if (!value) {
    return <span className="text-xs text-muted-foreground" data-testid={testId}>{envName ? `${envName} ` : ""}not set in deploy/.env</span>;
  }
  return (
    <span className={cn("inline-flex min-w-0 items-center gap-0.5", className)}>
      <code className="truncate rounded bg-muted px-1.5 py-0.5 font-mono text-xs" data-testid={testId} data-shown={shown ? "true" : "false"}>
        {shown ? value : "••••••••"}
      </code>
      {masked ? (
        <Button type="button" variant="ghost" size="icon-sm" onClick={() => setShown((s) => !s)} aria-label={shown ? "Hide" : "Show"} title={shown ? "Hide" : "Show"}>
          {shown ? <EyeOff /> : <Eye />}
        </Button>
      ) : null}
      <CopyButton value={value} />
    </span>
  );
}
