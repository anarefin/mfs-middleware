// Logins & secrets: every seeded login with its password and live TOTP code, the app PIN, the
// latest OTP of a number, a PIN encrypted for hand-made calls, and the platform's dev secrets.
// Every value is a fixed fake dev value of deploy/.env (ADR D9 amendment); the console runs only
// in the tester profile. Missing values say so instead of failing the page. Nothing is logged.

import { Building2, KeyRound, MessageSquareCode, Server, ShieldCheck, Smartphone } from "lucide-react";
import Link from "next/link";
import { Username } from "@/components/copyable-text";
import { NoPresetCode } from "@/components/no-preset-code";
import { PageHeader } from "@/components/page-header";
import { Hint, Panel, SubHeading } from "@/components/section";
import { SecretValue } from "@/components/secret-value";
import { TotpCode } from "@/components/totp-code";
import { Badge } from "@/components/ui/badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { PLATFORM_SECRETS, WRONG_PIN_MAGIC } from "@/lib/secrets";
import { PORTALS_IMPORT, personasOf, type Persona } from "@/lib/seed-manifest";
import { envValue, loadManifest } from "@/lib/server/seed";
import { OtpLookup, PinCipher } from "./tools";

export const dynamic = "force-dynamic";

function Logins({ id, people }: { id: string; people: Persona[] }) {
  if (!people.length) return <Hint>No login of this realm in the manifest.</Hint>;
  return (
    <div className="overflow-hidden rounded-lg border">
      <Table data-testid={`logins-${id}`}>
        <TableHeader className="bg-muted/50">
          <TableRow><TableHead>Name</TableHead><TableHead>Username</TableHead><TableHead>Roles</TableHead><TableHead>Password</TableHead><TableHead>One-time code</TableHead><TableHead>TOTP secret</TableHead></TableRow>
        </TableHeader>
        <TableBody>
          {people.map((p) => (
            <TableRow key={p.username} data-testid={`login-${p.username}`}>
              <TableCell>{p.displayName ?? "-"}</TableCell>
              <TableCell><Username name={p.username} /></TableCell>
              <TableCell>
                <span className="flex flex-wrap gap-1">{p.roles.map((r) => <Badge key={r} tone="outline">{r}</Badge>)}{p.attributes.orgId ? <Badge tone="info">{p.attributes.orgId}</Badge> : null}</span>
              </TableCell>
              <TableCell><SecretValue value={envValue(p.passwordEnv)} envName={p.passwordEnv} masked={false} testId={`password-${p.username}`} /></TableCell>
              <TableCell>{p.totpSecretEnv ? <TotpCode username={p.username} testId={`totp-${p.username}`} /> : <NoPresetCode />}</TableCell>
              <TableCell>{p.totpSecretEnv ? <SecretValue value={envValue(p.totpSecretEnv)} envName={p.totpSecretEnv} /> : "-"}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}


export default async function CredentialsPage() {
  const { manifest, error } = await loadManifest();
  const pinEnv = manifest?.appPins?.pinEnv ?? "MW_SEED_APP_PIN";
  const pin = envValue(pinEnv);
  return (
    <>
      <PageHeader
        title="Logins & secrets"
        description={
          <>
            Every dev login and key of the local stack, with every user&apos;s live one-time code (no
            <code className="mx-1 font-mono text-xs">scripts/totp.sh</code> needed). Fixed fake values from <code className="font-mono text-xs">deploy/.env</code>:
            never real secrets. Per-row credentials and account tools are on <Link href="/seed-data" className="text-link">Seed data</Link>.
          </>
        }
      />
      {error ? <p className="m-0 rounded-lg border border-warn/40 bg-warn-bg px-3 py-2 text-sm text-warn" data-testid="manifest-missing">{error}. The portal users of the realm import are shown without it.</p> : null}
      <div className="grid items-start gap-5">
        <Panel title="Ops console (realm ops, /console)" icon={ShieldCheck}>
          <Logins id="ops" people={personasOf(manifest, "ops")} />
          <Hint>
            Every ops user has the pre-set secret: copy the username, password and live code, no authenticator app. A code works once per
            user per 30 s step.
          </Hint>
        </Panel>
        <Panel title="Org portal (realm portals, client mw-portal)" icon={Building2}>
          <Logins id="portals" people={personasOf(manifest, "portals", PORTALS_IMPORT)} />
        </Panel>
        <div className="grid items-start gap-5 lg:grid-cols-2">
          <Panel title="App wallets (mw-auth)" icon={Smartphone}>
            <dl className="m-0 grid grid-cols-[max-content_1fr] items-center gap-x-4 gap-y-2 text-sm">
              <dt className="text-muted-foreground">Seeded login and transaction PIN</dt>
              <dd className="m-0"><SecretValue value={pin} envName={pinEnv} masked={false} testId="app-pin" /></dd>
              <dt className="text-muted-foreground">Wrong-PIN magic value</dt>
              <dd className="m-0"><code className="font-mono text-xs">{WRONG_PIN_MAGIC}</code> <span className="text-xs text-muted-foreground">(refused: REJECTED; three wrong PINs lock the wallet)</span></dd>
            </dl>
            <Hint>Every seeded app wallet (customer01 01711000001, agent01, merchant01, seed-*) has this PIN; the wallets are listed on Seed data.</Hint>
            <SubHeading className="mt-2">PIN → ciphertext</SubHeading>
            <PinCipher defaultPin={pin} />
          </Panel>
          <Panel title="Latest OTP of a number" icon={MessageSquareCode}>
            <OtpLookup />
            <Hint>The newest BIND, LOGIN and RESET codes mw-auth sent to the number (read from the SMSC simulator).</Hint>
          </Panel>
        </div>
        <Panel title="Platform secrets" icon={Server}>
          <div className="grid gap-4 md:grid-cols-2">
            {PLATFORM_SECRETS.map((g) => (
              <div key={g.title} className="flex flex-col gap-2">
                <SubHeading className="flex items-center gap-1.5"><KeyRound className="size-3.5" />{g.title}</SubHeading>
                <dl className="m-0 grid grid-cols-[max-content_1fr] items-center gap-x-4 gap-y-1.5 text-sm">
                  {g.entries.map((e) => (
                    <div key={e.env} className="contents">
                      <dt className="text-muted-foreground">{e.label}{e.hint ? <span className="block text-xs">{e.hint}</span> : null}</dt>
                      <dd className="m-0 min-w-0"><SecretValue value={envValue(e.env)} envName={e.env} testId={`secret-${e.env}`} /></dd>
                    </div>
                  ))}
                </dl>
              </div>
            ))}
          </div>
        </Panel>
      </div>
    </>
  );
}
