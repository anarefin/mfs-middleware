import Link from "next/link";
import { redirect } from "next/navigation";
import { formatTime } from "@/lib/format";
import { adminApi, operatorToken } from "@/lib/server/admin";
import { ApiError } from "../api-error";

// Partners (spec 15.2, tickets 38 / 42): every partner config as published, with its state and
// timeouts. Changes are filed on the partner's page and applied only after a checker approves.

const ms = (v: number | undefined) => (v === undefined ? "-" : `${v} ms`);

export default async function PartnersPage() {
  const token = await operatorToken();
  if (!token) redirect("/login?error=SessionExpired");
  const result = await adminApi().listPartners(token);
  return (
    <>
      <h1>Partners</h1>
      {!result.ok ? <ApiError status={result.status} error={result.error} /> : result.data.items.length === 0 ? (
        <p className="hint">No partners.</p>
      ) : (
        <table data-testid="partners">
          <thead>
            <tr>
              <th>Partner</th><th>Family</th><th>State</th><th>Connect</th><th>Timeout</th><th>Read</th>
              <th>Version</th><th>Changed by</th><th>Approved by</th><th>Updated</th>
            </tr>
          </thead>
          <tbody>
            {result.data.items.map((p) => (
              <tr key={p.partnerId}>
                <td><Link className="mono" href={`/partners/${p.partnerId}`}>{p.partnerId}</Link></td>
                <td>{p.family}</td>
                <td className={`status ${p.config.enabled === false ? "DISABLED" : "ENABLED"}`}>{p.config.enabled === false ? "disabled" : "enabled"}</td>
                <td>{ms(p.config.connectTimeoutMs)}</td>
                <td>{ms(p.config.timeoutMs)}</td>
                <td>{ms(p.config.readTimeoutMs)}</td>
                <td>{p.version === 0 ? "seed" : `v${p.version}`}</td>
                <td>{p.updatedBy}</td>
                <td>{p.approvedBy ?? ""}</td>
                <td>{formatTime(p.updatedAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  );
}
