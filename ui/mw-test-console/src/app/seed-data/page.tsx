// Seed data: everything the seed profile (mw-seeder's manifest), core-mock and the services' dev
// config put in place, with each row's credentials inline (password, live TOTP, PIN, HMAC secret)
// and the account and wallet tools. The manifest names env variables, never values: the server
// resolves them (lib/server/seed.ts). Nothing here is logged.

import { Building2, Layers, Menu, MessageSquareText, Receipt, Search, ShieldCheck, Smartphone, Store, Users, Wallet } from "lucide-react";
import { Username } from "@/components/copyable-text";
import { NoPresetCode } from "@/components/no-preset-code";
import { PageHeader } from "@/components/page-header";
import { Hint, Panel } from "@/components/section";
import { SecretValue } from "@/components/secret-value";
import { TotpCode } from "@/components/totp-code";
import { Badge } from "@/components/ui/badge";
import { formatMinor } from "@/lib/format";
import { BILLERS, CHECKOUT_MERCHANTS, GENERATED_RANGES, ORGS, groupBillers, rangeLabel } from "@/lib/seed-catalogue";
import { PORTALS_IMPORT, appWalletRows, personasOf, runCustomers, type Persona } from "@/lib/seed-manifest";
import { envValue, loadManifest } from "@/lib/server/seed";
import { core } from "@/lib/server/test-apis";
import { FilterTable, type FilterRow } from "./filter-table";
import { UserTools, WalletTools } from "./row-tools";
import { WalletLookup } from "./wallet-lookup";

export const dynamic = "force-dynamic";

type Live = { balanceMinor?: number; holderName?: string; type?: string } | null;

async function liveWallets(ids: string[]): Promise<Map<string, Live>> {
  const uniq = [...new Set(ids)];
  const got = await Promise.all(uniq.map(async (id) => [id, (await core.wallet(id).catch(() => null)) as Live] as const));
  return new Map(got);
}

const roles = (p: Persona) => (
  <span className="flex flex-wrap gap-1">{p.roles.map((r) => <Badge key={r} tone="outline">{r}</Badge>)}</span>
);
const totpCell = (p: Persona) => (p.totpSecretEnv ? <TotpCode username={p.username} testId={`totp-${p.username}`} /> : <NoPresetCode />);
const balance = (w: Live | undefined) => <span className="whitespace-nowrap tabular-nums">{w?.balanceMinor === undefined ? "-" : formatMinor(w.balanceMinor)}</span>;
const orgUsers = (people: Persona[]) =>
  people.length ? <span key="u" className="flex flex-wrap gap-1">{people.map((p) => <Username key={p.username} name={p.username} />)}</span> : "-";
const mono = (s: string) => <code className="font-mono text-xs">{s}</code>;

function loginRows(people: Persona[], realm: "ops" | "portals"): FilterRow[] {
  return people.map((p) => ({
    key: p.username,
    search: [p.username, p.displayName, ...p.roles, p.attributes.orgId].filter(Boolean).join(" "),
    cells: [
      <span key="n" className="font-medium">{p.displayName ?? "-"}</span>,
      <Username key="u" name={p.username} />,
      ...(realm === "portals" ? [p.attributes.orgId ? <Badge key="o" tone="info">{p.attributes.orgId}</Badge> : <span key="o" className="text-muted-foreground">none</span>] : []),
      roles(p),
      <SecretValue key="p" value={envValue(p.passwordEnv)} envName={p.passwordEnv} masked={false} testId={`password-${p.username}`} />,
      totpCell(p),
      <UserTools key="t" realm={realm} username={p.username} />,
    ],
  }));
}


export default async function SeedDataPage() {
  const { manifest, error } = await loadManifest();
  const ops = personasOf(manifest, "ops");
  const portals = personasOf(manifest, "portals", PORTALS_IMPORT);
  const wallets = appWalletRows(manifest);
  const runs = manifest ? runCustomers(manifest) : [];
  const pinEnv = manifest?.appPins?.pinEnv ?? "MW_SEED_APP_PIN";
  const pin = envValue(pinEnv);
  const live = await liveWallets([...wallets.map((w) => w.walletId), ...runs.map((r) => r.walletId), ...ORGS.map((o) => o.walletId)]);
  const pinCell = (has: boolean, id: string) => (has ? <SecretValue value={pin} envName={pinEnv} masked={false} testId={`pin-${id}`} /> : <span className="text-xs text-muted-foreground">no app PIN</span>);

  const walletRows: FilterRow[] = wallets.map((w) => {
    const l = live.get(w.walletId);
    const name = l?.holderName ?? w.name;
    return {
      key: w.walletId,
      search: [w.walletId, name, w.user, w.role].filter(Boolean).join(" "),
      cells: [
        <span key="n" className="font-medium" data-testid={`holder-${w.walletId}`}>{name ?? "-"}</span>,
        mono(w.walletId),
        w.user ? <Username key="u" name={w.user} /> : "-",
        <Badge key="r" tone="outline">{w.role}</Badge>,
        <span key="b" data-testid={`balance-${w.walletId}`}>{balance(l)}</span>,
        pinCell(w.hasPin, w.walletId),
        <WalletTools key="t" walletId={w.walletId} />,
      ],
    };
  });

  const runRows: FilterRow[] = runs.map((r) => {
    const l = live.get(r.walletId);
    return {
      key: r.walletId,
      search: [r.username, r.walletId, l?.holderName ?? r.name].filter(Boolean).join(" "),
      cells: [<span key="n">{l?.holderName ?? r.name ?? "-"}</span>, <Username key="u" name={r.username} />, mono(r.walletId), balance(l), pinCell(true, r.walletId),
        <WalletTools key="t" walletId={r.walletId} actions={["status", "unlock", "resetPin"]} />],
    };
  });

  const orgRows: FilterRow[] = ORGS.map((o) => ({
    key: o.orgId,
    search: [o.orgId, o.name, o.walletId].join(" "),
    cells: [<Badge key="o" tone="info">{o.orgId}</Badge>, <span key="n">{live.get(o.walletId)?.holderName ?? o.name}</span>, mono(o.walletId), balance(live.get(o.walletId)),
      orgUsers(portals.filter((p) => p.attributes.orgId === o.orgId))],
  }));

  return (
    <>
      <PageHeader
        title="Seed data"
        description={
          <>
            What the seed profile (mw-seeder&apos;s manifest), core-mock and the services&apos; dev config put in place, with every row&apos;s
            credentials (copy buttons) and the account and wallet tools. Balances are live from core-mock.
          </>
        }
      />
      {error ? <p className="m-0 rounded-lg border border-warn/40 bg-warn-bg px-3 py-2 text-sm text-warn" data-testid="manifest-missing">{error}</p> : null}
      <div className="grid items-start gap-5">
        <Panel title="Ops personas (realm ops)" icon={ShieldCheck}>
          <FilterTable id="ops" head={["Name", "Username", "Roles", "Password", "One-time code", "Tools"]} rows={loginRows(ops, "ops")} empty="No manifest: run the seed profile." />
        </Panel>
        <Panel title="Portal personas (realm portals)" icon={Building2}>
          <FilterTable id="portals" head={["Name", "Username", "Org", "Roles", "Password", "One-time code", "Tools"]} rows={loginRows(portals, "portals")} />
        </Panel>
        <Panel title="App wallets" icon={Smartphone}>
          <FilterTable id="wallets" head={["Holder", "Wallet (MSISDN)", "App user", "Role", "Balance", "PIN", "Tools"]} rows={walletRows} />
          <Hint>Wallet tools act on mw-auth (status, unlock, PIN back to the seeded one, unbind) and core-mock (set balance: creates a missing wallet only).</Hint>
        </Panel>
        <Panel title="API suite run customers" icon={Users}>
          <FilterTable id="runs" head={["Holder", "Username", "Wallet", "Balance", "PIN", "Tools"]} rows={runRows} empty="No manifest: run the seed profile." />
        </Panel>
        <div className="grid items-start gap-5 lg:grid-cols-2">
          <Panel title="Orgs (bulk payers)" icon={Wallet}>
            <FilterTable id="orgs" head={["Org", "Name", "Wallet", "Balance", "Portal users"]} rows={orgRows} />
            <Hint>mw-portal-api org-wallets: the orgId claim picks the wallet a batch pays from.</Hint>
          </Panel>
          <Panel title="Checkout merchants" icon={Store}>
            <FilterTable id="merchant" head={["Consumer", "Name", "Wallet", "HMAC secret"]} rows={CHECKOUT_MERCHANTS.map((m, i) => ({
              key: m.id, search: `${m.id} ${m.name}`,
              cells: [mono(m.id), m.name, mono(m.walletId),
                <SecretValue key="s" value={envValue(m.secretEnv)} envName={m.secretEnv} testId={i === 0 ? "merchant-secret" : `merchant-secret-${m.id}`} />],
            }))} />
          </Panel>
          <Panel title="Partners" icon={Layers}>
            <FilterTable id="partners" head={["Partner", "Family", "Version", "Enabled", "Timeout", "HMAC secret"]} rows={(manifest?.partners ?? []).map((p) => ({
              key: p.partnerId, search: `${p.partnerId} ${p.family}`,
              cells: [mono(p.partnerId), p.family || "-", String(p.version), p.enabled ? <Badge key="e" tone="good">enabled</Badge> : <Badge key="e" tone="bad">disabled</Badge>,
                p.timeoutMs === null ? "-" : `${p.timeoutMs} ms`,
                p.partnerId === "billagg" || p.partnerId === "c2wbank"
                  ? <SecretValue key="s" value={envValue(`${p.partnerId.toUpperCase()}_HMAC_SECRET`)} envName={`${p.partnerId.toUpperCase()}_HMAC_SECRET`} />
                  : <span key="s" className="text-xs text-muted-foreground">none</span>],
            }))} empty="No manifest: run the seed profile." />
            <Hint>
              Partner callback secrets: bill aggregator <SecretValue value={envValue("BILLAGG_HMAC_SECRET")} envName="BILLAGG_HMAC_SECRET" />, bank C2W{" "}
              <SecretValue value={envValue("C2WBANK_HMAC_SECRET")} envName="C2WBANK_HMAC_SECRET" />.
            </Hint>
          </Panel>
          <Panel title="Billers" icon={Receipt}>
            <FilterTable id="billers" head={["Code", "Name", "Category", "Protocol", "Partner"]} rows={groupBillers(BILLERS).flatMap((g) => g.billers.map((b) => ({
              key: b.code, group: g.label, search: `${b.code} ${b.name} ${b.category} ${g.label} ${b.protocol}`,
              cells: [mono(b.code), b.name, b.category, <Badge key="p" tone={b.protocol === "SOAP" ? "info" : "outline"}>{b.protocol}</Badge>, mono(b.partnerId)],
            })))} />
          </Panel>
          <Panel title="USSD menus" icon={Menu}>
            <FilterTable id="menus" head={["Menu", "Version", "Live for"]} rows={(manifest?.ussdMenus ?? []).map((m) => ({
              key: `${m.menuId}-${m.version}`, search: `${m.menuId} ${m.mnos.join(" ")}`, cells: [mono(m.menuId), String(m.version), m.mnos.join(", ")],
            }))} empty="No manifest: run the seed profile." />
          </Panel>
          <Panel title="Notification templates" icon={MessageSquareText}>
            <FilterTable id="templates" head={["Code", "Channel", "Locale", "Version"]} rows={(manifest?.templates ?? []).map((t) => ({
              key: `${t.code}-${t.channel}-${t.locale}`, search: `${t.code} ${t.channel} ${t.locale}`, cells: [mono(t.code), t.channel, t.locale, String(t.version)],
            }))} empty="No manifest: run the seed profile." />
          </Panel>
        </div>
        <Panel title="core-mock generated wallets" icon={Search}>
          <FilterTable id="ranges" head={["Type", "Range", "Count"]} rows={GENERATED_RANGES.map((r) => ({
            key: r.prefix, search: `${r.type} ${r.prefix}`, cells: [<Badge key="t" tone="outline">{r.type}</Badge>, mono(rangeLabel(r)), String(r.count)],
          }))} />
          <Hint>
            core-mock seeds these on start (WalletSeeder). 01711000500..999 have no app PIN: the Wallet auth page makes its fresh wallets from them.
            Look up any number, with the same wallet tools:
          </Hint>
          <WalletLookup />
        </Panel>
      </div>
    </>
  );
}
