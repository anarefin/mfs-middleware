import { Clock } from "lucide-react";
import Link from "next/link";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { formatTime } from "@/lib/format";
import type { ChangeRequest } from "@/lib/types";

/** The open-request note (no server import: the partner tabs render it from tests too). */
export function PendingNotice({ cr }: { cr: ChangeRequest }) {
  return (
    <Alert variant="info">
      <Clock />
      <AlertDescription>
        <p data-testid="pending-request">
          {cr.action} requested by {cr.makerId} on {formatTime(cr.createdAt)}, waiting for approval in{" "}
          <Link href="/approvals" className="font-medium text-info underline">Approvals</Link>.
        </p>
      </AlertDescription>
    </Alert>
  );
}
