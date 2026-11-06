// The transaction detail's operator actions (ticket 41): the open request, the note when the check
// for one failed, or the Actions panel with the buttons the status allows.
import Link from "next/link";
import { ACTION_LABELS } from "@/lib/approvals";
import { formatTime } from "@/lib/format";
import type { TxnGate } from "@/lib/txn-view";
import { ActionForm } from "../../action-form";
import type { ActionState } from "../../actions";

type Props = { gate: TxnGate; action: (prev: ActionState, form: FormData) => Promise<ActionState> };

export function TxnActions({ gate: { actions, open, checkFailed }, action }: Props) {
  return (
    <>
      {open ? (
        <p className="notice" data-testid="pending-request">
          {open.action} requested by {open.makerId} on {formatTime(open.createdAt)}, waiting for approval in{" "}
          <Link href="/approvals">Approvals</Link>.
        </p>
      ) : null}
      {checkFailed ? (
        <p className="notice" data-testid="pending-check-failed">
          Could not check for open requests, so no action can be requested now. Reload the page to try again.
        </p>
      ) : null}
      {actions.length > 0 ? (
        <section className="panel actions" aria-label="Actions">
          <h2>Actions</h2>
          <p className="hint">A request is applied only after a different operator (ops-checker) approves it.</p>
          <ActionForm action={action} label="Request an action">
            <label>Reason<input name="reason" required maxLength={500} /></label>
            <span className="buttons">
              {actions.map((a) => (
                <button key={a} className="primary" type="submit" name="action" value={a}>{ACTION_LABELS[a]}</button>
              ))}
            </span>
          </ActionForm>
        </section>
      ) : null}
    </>
  );
}
