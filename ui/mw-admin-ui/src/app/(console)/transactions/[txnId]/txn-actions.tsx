// The transaction detail's operator actions (ticket 41): the open request, the note when the check
// for one failed, or the Actions panel with the buttons the status allows.
import { Clock, TriangleAlert, Wrench } from "lucide-react";
import Link from "next/link";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { CardContent, CardDescription, CardHeader, CardSection, CardTitle } from "@/components/ui/card";
import { Field } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
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
        <Alert variant="info">
          <Clock />
          <AlertDescription>
            <p data-testid="pending-request">
              {open.action} requested by {open.makerId} on {formatTime(open.createdAt)}, waiting for approval in{" "}
              <Link href="/approvals" className="font-medium text-info underline">Approvals</Link>.
            </p>
          </AlertDescription>
        </Alert>
      ) : null}
      {checkFailed ? (
        <Alert variant="warning">
          <TriangleAlert />
          <AlertDescription>
            <p data-testid="pending-check-failed">
              Could not check for open requests, so no action can be requested now. Reload the page to try again.
            </p>
          </AlertDescription>
        </Alert>
      ) : null}
      {actions.length > 0 ? (
        <CardSection aria-label="Actions">
          <CardHeader className="flex-col items-start gap-1">
            <CardTitle><Wrench />Actions</CardTitle>
            <CardDescription>A request is applied only after a different operator (ops-checker) approves it.</CardDescription>
          </CardHeader>
          <CardContent>
            <ActionForm action={action} label="Request an action" className="flex flex-col gap-3">
              <Field label="Reason" className="max-w-xl"><Input name="reason" required maxLength={500} /></Field>
              <span className="flex flex-wrap gap-2">
                {actions.map((a) => (
                  <Button key={a} type="submit" name="action" value={a} variant={a === "REVERSE" || a === "FORCE_RELEASE" ? "outline-destructive" : "default"}>{ACTION_LABELS[a]}</Button>
                ))}
              </span>
            </ActionForm>
          </CardContent>
        </CardSection>
      ) : null}
    </>
  );
}
