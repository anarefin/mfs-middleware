"use client";
// A form posting to a server action (src/app/(console)/actions.ts); shows the refusal envelope
// next to it and disables its buttons while the call runs (no double submit).
//
// React 19 resets a form after its `action` prop runs, which put every uncontrolled field back to
// its default after a refusal (the operator lost a partner route, its mappings and the reason). So
// the submit goes through onSubmit: preventDefault stops React's form action (and its reset), and
// the FormData (with the clicked button's name/value) is dispatched in a transition. A refused
// submit keeps what was typed; a successful one still redirects (the action's `redirect`). `action`
// stays on the form so a submit before hydration still posts (progressive enhancement).

import { startTransition, useActionState, type FormEvent, type ReactNode } from "react";
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
  function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const submitter = (event.nativeEvent as SubmitEvent).submitter;
    const data = new FormData(event.currentTarget, submitter);
    startTransition(() => formAction(data));
  }
  return (
    <form action={formAction} onSubmit={onSubmit} className={className} aria-label={label} aria-busy={pending}>
      <fieldset disabled={pending}>{children}</fieldset>
      {state ? <ApiError status={state.status} error={state.error} /> : null}
    </form>
  );
}
