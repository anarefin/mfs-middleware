"use client";

import { Check, Copy } from "lucide-react";
import { useState } from "react";
import { Button } from "@/components/ui/button";

/** Copies `value` to the clipboard (a ✓ for 1.5 s). */
export function CopyButton({ value, label = "Copy", testId }: { value: string; label?: string; testId?: string }) {
  const [done, setDone] = useState(false);
  async function copy() {
    try {
      await navigator.clipboard.writeText(value);
    } catch {
      // no clipboard (plain http): select-and-copy by hand
      return;
    }
    setDone(true);
    setTimeout(() => setDone(false), 1500);
  }
  return (
    <Button type="button" variant="ghost" size="icon-sm" onClick={copy} aria-label={label} title={label} data-testid={testId}>
      {done ? <Check className="text-good" /> : <Copy />}
    </Button>
  );
}
