"use client";
// The latest OTP an MSISDN received (SMSC simulator) and the PIN -> ciphertext helper.

import { KeySquare, Search } from "lucide-react";
import { useActionState } from "react";
import { lookupOtp, pinCiphertext } from "@/app/actions/credentials";
import { CopyButton } from "@/components/copy-button";
import { Hint, Row } from "@/components/section";
import { Button } from "@/components/ui/button";
import { Field } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

const age = (s: number | null) => (s === null ? "-" : s < 90 ? `${s} s ago` : s < 5400 ? `${Math.round(s / 60)} min ago` : `${Math.round(s / 3600)} h ago`);

export function OtpLookup() {
  const [s, run, pending] = useActionState(lookupOtp, null);
  return (
    <div className="flex flex-col gap-3">
      <form action={run}>
        <Row>
          <Field label="MSISDN"><Input name="msisdn" placeholder="01711000501" className="font-mono" data-testid="otp-msisdn" /></Field>
          <Button type="submit" variant="outline" disabled={pending} data-testid="otp-lookup"><Search />Latest codes</Button>
        </Row>
      </form>
      {s && !s.ok ? <p className="m-0 text-sm text-bad">{s.error}</p> : null}
      {s && s.ok ? (
        <div className="overflow-hidden rounded-lg border">
          <Table data-testid="otp-result">
            <TableHeader className="bg-muted/50"><TableRow><TableHead>Purpose</TableHead><TableHead>Code</TableHead><TableHead>Received</TableHead></TableRow></TableHeader>
            <TableBody>
              {s.otps.map((o) => (
                <TableRow key={o.purpose}>
                  <TableCell>{o.purpose}</TableCell>
                  <TableCell>
                    {o.code ? <span className="inline-flex items-center gap-1"><code className="font-mono text-sm font-semibold" data-testid={`otp-${o.purpose}`}>{o.code}</code><CopyButton value={o.code} /></span> : <span className="text-muted-foreground">none</span>}
                  </TableCell>
                  <TableCell className="text-xs text-muted-foreground">{o.receivedAt ? `${age(o.ageS)} (${o.receivedAt})` : "-"}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      ) : null}
      {s && s.ok ? <Hint>{s.smsCount} SMS to {s.msisdn} at the SMSC simulator (all of them: SMS & email).</Hint> : null}
    </div>
  );
}

export function PinCipher({ defaultPin }: { defaultPin?: string }) {
  const [s, run, pending] = useActionState(pinCiphertext, null);
  const json = s && s.ok ? JSON.stringify({ keyId: s.keyId, ciphertext: s.ciphertext }) : "";
  return (
    <div className="flex flex-col gap-3">
      <form action={run}>
        <Row>
          <Field label="PIN"><Input name="pin" type="password" defaultValue={defaultPin} autoComplete="off" className="font-mono" data-testid="cipher-pin" /></Field>
          <Button type="submit" variant="outline" disabled={pending} data-testid="cipher-go"><KeySquare />Encrypt</Button>
        </Row>
      </form>
      {s && !s.ok ? <p className="m-0 text-sm text-bad">{s.error}</p> : null}
      {s && s.ok ? (
        <div className="flex items-start gap-1">
          <pre className="m-0 min-w-0 flex-1 overflow-auto rounded-md bg-screen px-3 py-2 font-mono text-xs break-all whitespace-pre-wrap text-screen-foreground" data-testid="cipher-result">
            {JSON.stringify({ encryptedPin: { keyId: s.keyId, ciphertext: s.ciphertext } }, null, 2)}
          </pre>
          <CopyButton value={json} label="Copy {keyId, ciphertext}" />
        </div>
      ) : null}
      <Hint>RSA-OAEP-SHA256 under mw-crypto&apos;s PIN transport key (GET /auth/v1/pin-key), as the app sends it. A new ciphertext each time (OAEP is randomised).</Hint>
    </div>
  );
}
