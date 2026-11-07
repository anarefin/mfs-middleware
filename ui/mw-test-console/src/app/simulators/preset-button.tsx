"use client";

import { useActionState } from "react";
import { applyPreset } from "../actions/sim";
import { Result } from "../components/result";

export function PresetButton({ id, label, danger }: { id: string; label: string; danger?: boolean }) {
  const [state, action, pending] = useActionState(applyPreset, null);
  return (
    <form action={action} className="stack">
      <input type="hidden" name="preset" value={id} />
      <button type="submit" className={danger ? "danger" : "primary"} disabled={pending} data-testid={`preset-${id}`}>
        {pending ? "applying…" : label}
      </button>
      <Result state={state} />
    </form>
  );
}
