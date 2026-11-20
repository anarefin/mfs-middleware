// Home: is the stack ready for testing (edge, test-only routes, core-mock), the seeded wallets'
// balances, and the reset of every simulator and fault.

import { CircleCheck, CircleX, Compass, Server, Wallet } from "lucide-react";
import Link from "next/link";
import { HowToTest } from "@/components/how-to-test";
import { PageHeader } from "@/components/page-header";
import { Hint, Panel, SubHeading } from "@/components/section";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { formatMinor } from "@/lib/format";
import { SEEDED_WALLETS } from "@/lib/personas";
import { edge } from "@/lib/server/edge";
import { core } from "@/lib/server/test-apis";
import { PresetButton } from "./simulators/preset-button";

export const dynamic = "force-dynamic";

const WHERE = [
  { href: "/app", label: "App flows", what: "device binding, P2P, cash in / out, merchant pay, top-up, bill pay, add money from bank, idempotent resend" },
  { href: "/preferences", label: "Saved & favourites", what: "saved billers, favourite numbers and the notification inbox of one wallet" },
  { href: "/ussd", label: "USSD phone", what: "Bangla / English menus on gp, robi, bl and teletalk, each with its own code (HTTP and SMPP)" },
  { href: "/simulators", label: "Simulators & faults", what: "timeouts, refusals, callbacks, lost core replies (TSQ, UNKNOWN, MANUAL_REVIEW)" },
  { href: "/inbox", label: "SMS & email", what: "every notification" },
  { href: "/checkout", label: "Web checkout", what: "merchant session, OTP + PIN payment, signed redirect and IPN" },
  { href: "/bulk", label: "Bulk portal", what: "CSV upload, maker-checker, execution, result file, SP query" },
  { href: "/sp", label: "SP portal", what: "a service provider's own transactions and CSV report (role sp-viewer)" },
  { href: "/transactions", label: "Transactions", what: "search by txnId or MSISDN: status, saga timeline, partner calls, audit trail" },
];

async function check(label: string, fn: () => Promise<boolean>, fix: string) {
  try {
    return { label, ok: await fn(), fix };
  } catch {
    return { label, ok: false, fix };
  }
}

export default async function Home() {
  const checks = await Promise.all([
    check("APISIX edge answers /healthz", async () => (await edge("GET", "/healthz")).status === 200, "start the stack (deploy/RUNNING.html)"),
    check("test-only routes are on (core-mock wallets)", async () => (await edge("GET", "/test/core-mock/v1/wallets/01711000001", { apikey: true })).status === 200, "scripts/tester.sh (re-applies the test routes)"),
    check("simulators answer (SMPP binds of mw-ussd)", async () => (await edge("GET", "/test/sim/ussdgw/smpp/binds", { apikey: true })).status === 200, "start the stack; docker compose logs mw-simulators"),
  ]);
  const wallets = await Promise.all(SEEDED_WALLETS.map(async (w) => ({ ...w, wallet: await core.wallet(w.msisdn).catch(() => null) })));
  return (
    <>
      <PageHeader
        title="Test console"
        description={
          <>
            Plays every outside party of the middleware against the local stack, through APISIX like the real clients: the mobile app
            (customer, agent, merchant), a USSD handset (robi, bl, teletalk over HTTP, gp over SMPP), the partners (OCS, bill aggregator, bank),
            the checkout merchant and payer, and the org portal. Ops features (search, timeline, reversal, approvals, partners,
            menus, templates, wallet unlock) are in the <a href="/console" target="_blank" rel="noreferrer" className="text-link">ops console</a>.
            The step-by-step guide is <code className="font-mono text-[0.9em]">docs/testing-guide.html</code>.
          </>
        }
      />
      <HowToTest page="home" />
      <div className="grid items-start gap-5 lg:grid-cols-2">
        <Panel title="Stack" icon={Server}>
          <div className="overflow-hidden rounded-lg border">
            <Table data-testid="checks">
              <TableBody>
                {checks.map((c) => (
                  <TableRow key={c.label}>
                    <TableCell className="w-8">
                      {c.ok ? <CircleCheck className="size-4 text-good" /> : <CircleX className="size-4 text-bad" />}
                      <span className="sr-only">{c.ok ? "✔" : "✖"}</span>
                    </TableCell>
                    <TableCell>{c.label}</TableCell>
                    <TableCell className="text-sm text-bad">{c.ok ? "" : `fix: ${c.fix}`}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <SubHeading className="mt-2">Before a failure test, and after it</SubHeading>
          <PresetButton id="reset-all" label="Reset every simulator and core fault" />
        </Panel>
        <Panel title="Seeded wallets (core-mock)" icon={Wallet}>
          <div className="overflow-hidden rounded-lg border">
            <Table data-testid="wallets">
              <TableHeader className="bg-muted/50"><TableRow><TableHead>Wallet</TableHead><TableHead>Who</TableHead><TableHead className="text-right">Balance</TableHead><TableHead className="text-right">Held</TableHead></TableRow></TableHeader>
              <TableBody>
                {wallets.map((w) => (
                  <TableRow key={w.msisdn}>
                    <TableCell className="font-mono text-sm">{w.msisdn}</TableCell>
                    <TableCell>{w.who}</TableCell>
                    <TableCell className="text-right whitespace-nowrap tabular-nums">{w.wallet ? formatMinor(w.wallet.balanceMinor) : "-"}</TableCell>
                    <TableCell className="text-right whitespace-nowrap tabular-nums">{w.wallet ? formatMinor(w.wallet.heldMinor) : "-"}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <Hint>Every seeded app wallet&apos;s PIN is 13579 (MW_SEED_APP_PIN, checked by mw-auth for app, USSD and checkout). A wallet may debit Tk 15,000 per UTC day (core-mock limit).</Hint>
        </Panel>
        <Panel title="Where to test what" icon={Compass} className="lg:col-span-2">
          <ul className="m-0 grid list-none gap-2 p-0 text-sm sm:grid-cols-2">
            {WHERE.map((w) => (
              <li key={w.href} className="rounded-md border px-3 py-2">
                <Link href={w.href} className="font-medium text-link">{w.label}</Link>
                <span className="text-muted-foreground">: {w.what}</span>
              </li>
            ))}
          </ul>
        </Panel>
      </div>
    </>
  );
}
