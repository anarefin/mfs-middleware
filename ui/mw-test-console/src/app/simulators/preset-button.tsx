"use client";

import { Loader2 } from "lucide-react";
import { useActionState } from "react";
import { Button } from "@/components/ui/button";
import { applyPreset } from "../actions/sim";
import { Result } from "../components/result";

export function PresetButton({ id, label, danger }: { id: string; label: string; danger?: boolean }) {
  const [state, action, pending] = useActionState(applyPreset, null);
  return (
    <form action={action} className="flex flex-col items-start gap-3">
      <input type="hidden" name="preset" value={id} />
      <Button type="submit" variant={danger ? "outline-destructive" : "default"} disabled={pending} data-testid={`preset-${id}`}>
        {pending ? <Loader2 className="animate-spin" /> : null}
        {pending ? "applying…" : label}
      </Button>
      <div className="w-full empty:hidden"><Result state={state} /></div>
    </form>
  );
}
