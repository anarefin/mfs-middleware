import Link from "next/link";
import type { ErrorEnvelope } from "@/lib/types";

/** The 5.3 envelope as an operator reads it: code, message, correlation id for support. */
export function ApiError({ status, error }: { status: number; error: ErrorEnvelope }) {
  return (
    <div className="error" role="alert">
      <strong>{error.code}</strong> {error.message}
      {status === 401 ? <> - <Link href="/login">sign in again</Link></> : null}
      {error.retryable ? <span className="hint"> (try again)</span> : null}
      {error.details.length > 0 ? (
        <ul>{error.details.map((d, i) => <li key={i}>{d.field}: {d.issue}</li>)}</ul>
      ) : null}
      <div className="hint mono">correlationId {error.correlationId}</div>
    </div>
  );
}
