import { CircleAlert } from "lucide-react";
import Link from "next/link";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import type { ErrorEnvelope } from "@/lib/types";

/** The 5.3 envelope as an operator reads it: code, message, correlation id for support. */
export function ApiError({ status, error }: { status: number; error: ErrorEnvelope }) {
  return (
    <Alert variant="destructive" className="error" role="alert">
      <CircleAlert />
      <AlertTitle>
        <span className="font-mono">{error.code}</span> <span className="font-normal">{error.message}</span>
        {status === 401 ? <> - <Link href="/login">sign in again</Link></> : null}
        {error.retryable ? <span className="font-normal opacity-80"> (try again)</span> : null}
      </AlertTitle>
      <AlertDescription>
        {error.details.length > 0 ? (
          <ul>{error.details.map((d, i) => <li key={i}>{d.field}: {d.issue}</li>)}</ul>
        ) : null}
        <div className="font-mono text-xs opacity-80">correlationId {error.correlationId}</div>
      </AlertDescription>
    </Alert>
  );
}
