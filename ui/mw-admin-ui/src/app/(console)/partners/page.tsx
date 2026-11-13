import { Plug } from "lucide-react";
import Link from "next/link";
import { redirect } from "next/navigation";
import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/status-badge";
import { Card } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
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
      <PageHeader title="Partners" description="Every partner config as published: state, timeouts and version. Changes go through approval." />
      {!result.ok ? <ApiError status={result.status} error={result.error} /> : result.data.items.length === 0 ? (
        <div className="flex flex-col items-center gap-2 rounded-lg border border-dashed px-6 py-12 text-center text-muted-foreground">
          <Plug className="size-6" />
          <p className="m-0">No partners.</p>
        </div>
      ) : (
        <Card className="overflow-hidden">
          <Table data-testid="partners">
            <TableHeader>
              <TableRow>
                <TableHead>Partner</TableHead><TableHead>Family</TableHead><TableHead>State</TableHead>
                <TableHead className="text-right">Connect</TableHead><TableHead className="text-right">Timeout</TableHead><TableHead className="text-right">Read</TableHead>
                <TableHead>Version</TableHead><TableHead>Changed by</TableHead><TableHead>Approved by</TableHead><TableHead>Updated</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {result.data.items.map((p) => (
                <TableRow key={p.partnerId}>
                  <TableCell className="font-mono text-xs"><Link href={`/partners/${p.partnerId}`}>{p.partnerId}</Link></TableCell>
                  <TableCell>{p.family}</TableCell>
                  <TableCell><StatusBadge status={p.config.enabled === false ? "disabled" : "enabled"} /></TableCell>
                  <TableCell className="text-right whitespace-nowrap tabular-nums">{ms(p.config.connectTimeoutMs)}</TableCell>
                  <TableCell className="text-right whitespace-nowrap tabular-nums">{ms(p.config.timeoutMs)}</TableCell>
                  <TableCell className="text-right whitespace-nowrap tabular-nums">{ms(p.config.readTimeoutMs)}</TableCell>
                  <TableCell className="tabular-nums">{p.version === 0 ? "seed" : `v${p.version}`}</TableCell>
                  <TableCell className="text-muted-foreground">{p.updatedBy}</TableCell>
                  <TableCell className="text-muted-foreground">{p.approvedBy ?? ""}</TableCell>
                  <TableCell className="whitespace-nowrap text-muted-foreground tabular-nums">{formatTime(p.updatedAt)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Card>
      )}
    </>
  );
}
