// Seed data that is not in mw-seeder's manifest, copied from the services' own config (dev values).
// Keep in step with the sources named on each list.

/** mw-portal-api application.yml mw.portal.bulk.org-wallets (orgId claim -> the core wallet it pays from). */
export const ORGS: readonly { orgId: string; walletId: string; name: string }[] = [
  { orgId: "ORG001", walletId: "01311000001", name: "Meghna Garments Ltd" },
  { orgId: "ORG002", walletId: "01311000002", name: "Padma Foods Ltd" },
];

/** mw-adapter-billpay application.yml mw.billers (all served by the billagg simulator). */
export const BILLERS: readonly { code: string; name: string; category: string; partnerId: string }[] = [
  { code: "DESCO", name: "Dhaka Electric Supply Company", category: "ELECTRICITY", partnerId: "billagg" },
  { code: "DPDC", name: "Dhaka Power Distribution Company", category: "ELECTRICITY", partnerId: "billagg" },
  { code: "WASA", name: "Dhaka WASA", category: "WATER", partnerId: "billagg" },
  { code: "TITAS", name: "Titas Gas", category: "GAS", partnerId: "billagg" },
];

/** mw-checkout application.yml mw.checkout.merchants (APISIX hmac-auth consumer -> wallet). */
export const CHECKOUT_MERCHANT = { id: "merchant_demo", name: "Karim Traders", walletId: "01911000001", secretEnv: "CHECKOUT_DEMO_MERCHANT_SECRET" } as const;

export type WalletType = "CUSTOMER" | "AGENT" | "MERCHANT" | "ORG";

/** mw-core-mock WalletSeeder: the ranges it inserts on start (prefix + 4 digits from 0001). */
export const GENERATED_RANGES: readonly { type: WalletType; prefix: string; count: number }[] = [
  { type: "CUSTOMER", prefix: "0171100", count: 1000 },
  { type: "AGENT", prefix: "0181100", count: 50 },
  { type: "MERCHANT", prefix: "0191100", count: 20 },
  { type: "ORG", prefix: "0131100", count: 2 },
];

export const rangeLabel = (r: { prefix: string; count: number }): string => `${r.prefix}0001 .. ${r.prefix}${String(r.count).padStart(4, "0")}`;

/** The generated range a number is in, if any. */
export function rangeOf(msisdn: string): (typeof GENERATED_RANGES)[number] | undefined {
  if (!/^01[3-9]\d{8}$/.test(msisdn)) return undefined;
  return GENERATED_RANGES.find((r) => {
    if (!msisdn.startsWith(r.prefix)) return false;
    const n = Number(msisdn.slice(r.prefix.length));
    return n >= 1 && n <= r.count;
  });
}
