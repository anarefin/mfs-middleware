import Link from "next/link";
import { KeyRound } from "lucide-react";
import { ApiError } from "../api-error";
import { CardContent, CardDescription, CardHeader, CardSection, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { formatTime } from "@/lib/format";
import type { ApiResult, CredentialPage } from "@/lib/types";

/** The partners page card fed by GET /admin/v1/credentials/expiring (ticket 131c). Metadata only. */
export function ExpiringCredentialsCard({ result }: { result: ApiResult<CredentialPage> }) {
  return (
    <CardSection aria-label="Credentials expiring" data-testid="credentials-expiring">
      <CardHeader className="flex-col items-start gap-1">
        <CardTitle><KeyRound />Credentials expiring</CardTitle>
        <CardDescription>Within 30 days, expired ones included, soonest first. Metadata only; a secret is never shown.</CardDescription>
      </CardHeader>
      <CardContent>
        {!result.ok ? <ApiError status={result.status} error={result.error} /> : result.data.items.length === 0 ? (
          <p className="m-0 text-sm text-muted-foreground">No credentials expire within 30 days.</p>
        ) : (
          <Table data-testid="expiring-credentials">
            <TableHeader>
              <TableRow>
                <TableHead>Partner</TableHead><TableHead>Name</TableHead><TableHead>Type</TableHead>
                <TableHead>Expires</TableHead><TableHead className="text-right">Days left</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {result.data.items.map((c) => (
                <TableRow key={c.credentialId}>
                  <TableCell className="font-mono text-xs">
                    <Link href={`/partners/${c.partnerId}?tab=credentials`}>{c.partnerId}</Link>
                  </TableCell>
                  <TableCell className="font-mono text-xs">{c.name}</TableCell>
                  <TableCell>{c.type}</TableCell>
                  <TableCell className="whitespace-nowrap tabular-nums">{formatTime(c.notAfter)}</TableCell>
                  <TableCell className="text-right tabular-nums" data-testid="days-left">{c.daysLeft ?? ""}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </CardContent>
    </CardSection>
  );
}
