// Home: is the stack ready for testing (edge, test-only routes, core-mock), the seeded wallets'
// balances, and the reset of every simulator and fault.

import Link from "next/link";
import { formatMinor } from "@/lib/format";
import { SEEDED_WALLETS } from "@/lib/personas";
import { edge } from "@/lib/server/edge";
import { core } from "@/lib/server/test-apis";
import { PresetButton } from "./simulators/preset-button";

export const dynamic = "force-dynamic";

async function check(label: string, fn: () => Promise<boolean>, fix: string) {
  try {
    return { label, ok: await fn(), fix };
  } catch {
    return { label, ok: false, fix };
  }
}

export default async function Home() {
  const checks = await Promise.all([
    check("APISIX edge answers /healthz", async () => (await edge("GET", "/healthz")).status === 200, "make up"),
    check("test-only routes are on (core-mock PIN key)", async () => (await edge("GET", "/test/core-mock/v1/pin-public-key", { apikey: true })).status === 200, "make tester (re-applies the test routes)"),
    check("simulators answer (SMPP binds of mw-ussd)", async () => (await edge("GET", "/test/sim/ussdgw/smpp/binds", { apikey: true })).status === 200, "make up; docker compose logs mw-simulators"),
  ]);
  const wallets = await Promise.all(SEEDED_WALLETS.map(async (w) => ({ ...w, wallet: await core.wallet(w.msisdn).catch(() => null) })));
  return (
    <>
      <h1>Test console</h1>
      <p className="lead">
        Plays every outside party of the middleware against the local stack, through APISIX like the real clients: the mobile app
        (customer, agent, merchant), a USSD handset (robi over HTTP, gp over SMPP), the partners (OCS, bill aggregator, bank),
        the checkout merchant and payer, and the org portal. Ops features (search, timeline, reversal, approvals, partners,
        menus, templates, wallet unlock) are in the <a href="/console" target="_blank" rel="noreferrer">ops console</a>.
        The step-by-step guide is <code>docs/testing-guide.md</code>.
      </p>
      <div className="grid">
        <section className="panel">
          <h2>Stack</h2>
          <table data-testid="checks">
            <tbody>
              {checks.map((c) => (
                <tr key={c.label}>
                  <td>{c.ok ? "✔" : "✖"}</td>
                  <td>{c.label}</td>
                  <td className="hint">{c.ok ? "" : `fix: ${c.fix}`}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <h3>Before a failure test, and after it</h3>
          <PresetButton id="reset-all" label="Reset every simulator and core fault" />
        </section>
        <section className="panel">
          <h2>Seeded wallets (core-mock)</h2>
          <table data-testid="wallets">
            <thead><tr><th>Wallet</th><th>Who</th><th>Balance</th><th>Held</th></tr></thead>
            <tbody>
              {wallets.map((w) => (
                <tr key={w.msisdn}>
                  <td className="mono">{w.msisdn}</td>
                  <td>{w.who}</td>
                  <td>{w.wallet ? formatMinor(w.wallet.balanceMinor) : "-"}</td>
                  <td>{w.wallet ? formatMinor(w.wallet.heldMinor) : "-"}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="hint">Every seeded wallet's PIN is 1234. A wallet may debit Tk 15,000 per UTC day (core-mock limit).</p>
        </section>
        <section className="panel">
          <h2>Where to test what</h2>
          <ul>
            <li><Link href="/app">App flows</Link>: device binding, P2P, cash in / out, merchant pay, top-up, bill pay, add money from bank, idempotent resend</li>
            <li><Link href="/ussd">USSD phone</Link>: *268# menus in Bangla / English over HTTP and SMPP</li>
            <li><Link href="/simulators">Simulators & faults</Link>: timeouts, refusals, callbacks, lost core replies (TSQ, UNKNOWN, MANUAL_REVIEW)</li>
            <li><Link href="/inbox">SMS & email</Link>: every notification</li>
            <li><Link href="/checkout">Web checkout</Link>: merchant session, OTP + PIN payment, signed redirect and IPN</li>
            <li><Link href="/bulk">Bulk portal</Link>: CSV upload, maker-checker, execution, result file, SP query</li>
          </ul>
        </section>
      </div>
    </>
  );
}
