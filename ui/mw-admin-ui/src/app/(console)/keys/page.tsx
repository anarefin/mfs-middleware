import { KeyRound, RotateCw, ShieldCheck } from "lucide-react";
import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/status-badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardSection, CardTitle } from "@/components/ui/card";
import { Field } from "@/components/ui/field";
import { Input, NativeSelect } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { formatTime } from "@/lib/format";
import { activeVersion, canAdminKeys, canReadKeys, retirable } from "@/lib/keys";
import { adminApi, operatorToken } from "@/lib/server/admin";
import type { Key } from "@/lib/types";
import { ActionForm } from "../action-form";
import { ApiError } from "../api-error";
import { requestKeyChange, startKeyCeremony } from "../actions";

// Keys (ticket 75, spec 18.8, D23, OWN-08): the mw-crypto keys and their versions (metadata only),
// for security-admin and auditor. A security-admin files a rotation, a retirement or a generation,
// or starts a key ceremony; another security-admin approves it in Approvals. Every key action needs
// a sign-in (password + TOTP) of the last 5 minutes (mw-admin-api answers MW-AUTH-031 otherwise).

function KeyActions({ k }: { k: Key }) {
  const old = retirable(k);
  return (
    <div className="flex min-w-72 flex-col gap-2">
      <ActionForm action={requestKeyChange} label={`Rotate ${k.keyId}`} className="flex items-end gap-2">
        <input type="hidden" name="action" value="KEY_ROTATE" />
        <input type="hidden" name="keyId" value={k.keyId} />
        <Field label="Reason"><Input name="reason" required maxLength={500} className="h-8" /></Field>
        <Button type="submit" size="sm"><RotateCw />Request rotation</Button>
      </ActionForm>
      {old.length > 0 ? (
        <ActionForm action={requestKeyChange} label={`Retire ${k.keyId}`} className="flex items-end gap-2 border-t pt-2">
          <input type="hidden" name="action" value="KEY_RETIRE" />
          <input type="hidden" name="keyId" value={k.keyId} />
          <Field label="Version">
            <NativeSelect name="version" className="h-8">
              {old.map((v) => <option key={v.version} value={v.version}>{v.kid}</option>)}
            </NativeSelect>
          </Field>
          <Field label="Reason"><Input name="reason" required maxLength={500} className="h-8" /></Field>
          <Button type="submit" size="sm" variant="outline-destructive">Request retirement</Button>
        </ActionForm>
      ) : null}
    </div>
  );
}

export default async function KeysPage() {
  const session = await auth();
  const roles = session?.roles ?? [];
  if (!canReadKeys(roles)) {
    return (
      <>
        <PageHeader title="Keys" />
        <p className="text-sm text-muted-foreground">Only a security-admin or an auditor sees the keys.</p>
      </>
    );
  }
  const admin = canAdminKeys(roles);
  const token = await operatorToken();
  if (!token) redirect("/login?error=SessionExpired");
  const result = await adminApi().listKeys(token);
  return (
    <>
      <PageHeader
        title="Keys"
        description="HSM keys and their versions (metadata only). Changes need a second security-admin's approval and a fresh sign-in."
      />
      {!result.ok ? <ApiError status={result.status} error={result.error} /> : (
        <Card className="overflow-hidden">
          <Table data-testid="keys">
            <TableHeader>
              <TableRow>
                <TableHead>Key</TableHead><TableHead>Purpose</TableHead><TableHead>Algorithm</TableHead>
                <TableHead>Versions</TableHead>{admin ? <TableHead>Request</TableHead> : null}
              </TableRow>
            </TableHeader>
            <TableBody>
              {result.data.items.map((k) => (
                <TableRow key={k.keyId} data-key={k.keyId}>
                  <TableCell className="font-mono text-xs">{k.keyId}</TableCell>
                  <TableCell className="font-mono text-xs">{k.purpose}</TableCell>
                  <TableCell className="text-xs">{k.algorithm} ({k.provider})</TableCell>
                  <TableCell>
                    <ul className="m-0 flex list-none flex-col gap-1 p-0 text-xs">
                      {k.versions.map((v) => (
                        <li key={v.version} className="flex items-center gap-2" data-kid={v.kid}>
                          <span className="font-mono">{v.kid}</span>
                          <StatusBadge status={v.state} />
                          <span className="text-muted-foreground tabular-nums">{formatTime(v.createdAt)}</span>
                        </li>
                      ))}
                    </ul>
                    {activeVersion(k) ? null : <span className="text-xs text-bad">no ACTIVE version</span>}
                  </TableCell>
                  {admin ? <TableCell><KeyActions k={k} /></TableCell> : null}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Card>
      )}

      {admin ? (
        <>
          <CardSection aria-label="Key ceremony" className="max-w-3xl">
            <CardHeader className="flex-col items-start gap-1">
              <CardTitle><ShieldCheck />Key ceremony</CardTitle>
              <CardDescription>
                For a top-level key (PIN_HMAC, TOKENISE_*, HMAC_*): two custodians each enter a
                component in their own session; the HSM combines them and shows only the check value
                (KCV), which the approving security-admin compares with the expected one.
              </CardDescription>
            </CardHeader>
            <CardContent>
              <ActionForm action={startKeyCeremony} label="Start key ceremony" className="grid grid-cols-[repeat(auto-fill,minmax(200px,1fr))] items-end gap-3">
                <Field label="Key id"><Input name="keyId" required placeholder="pin-hmac" className="font-mono" /></Field>
                <Field label="Purpose"><Input name="purpose" required placeholder="PIN_HMAC" className="font-mono" /></Field>
                <Field label="Reason"><Input name="reason" required maxLength={500} /></Field>
                <Button type="submit"><ShieldCheck />Start ceremony</Button>
              </ActionForm>
            </CardContent>
          </CardSection>
          <CardSection aria-label="Generate key" className="max-w-3xl">
            <CardHeader className="flex-col items-start gap-1">
              <CardTitle><KeyRound />Generate a key in the HSM</CardTitle>
              <CardDescription>A new key, version 1, generated inside the HSM once another security-admin approves.</CardDescription>
            </CardHeader>
            <CardContent>
              <ActionForm action={requestKeyChange} label="Generate key" className="grid grid-cols-[repeat(auto-fill,minmax(200px,1fr))] items-end gap-3">
                <input type="hidden" name="action" value="KEY_GENERATE" />
                <Field label="Key id"><Input name="keyId" required placeholder="hmac-webhook" className="font-mono" /></Field>
                <Field label="Purpose"><Input name="purpose" required placeholder="HMAC_WEBHOOK" className="font-mono" /></Field>
                <Field label="Reason"><Input name="reason" required maxLength={500} /></Field>
                <Button type="submit"><KeyRound />Request generation</Button>
              </ActionForm>
            </CardContent>
          </CardSection>
        </>
      ) : null}
    </>
  );
}
