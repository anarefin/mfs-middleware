"use client";
// A form posting to a server action (src/app/(console)/actions.ts); shows the refusal envelope
// next to it and disables its buttons while the call runs (no double submit).

import { useActionState, type ReactNode } from "react";
import type { ActionState } from "./actions";
import { ApiError } from "./api-error";

type Props = {
  action: (prev: ActionState, form: FormData) => Promise<ActionState>;
  className?: string;
  label?: string;
  children: ReactNode;
};

export function ActionForm({ action, className, label, children }: Props) {
  const [state, formAction, pending] = useActionState(action, null);
  return (
    <form action={formAction} className={className} aria-label={label} aria-busy={pending}>
      <fieldset disabled={pending}>{children}</fieldset>
      {state ? <ApiError status={state.status} error={state.error} /> : null}
    </form>
  );
}
