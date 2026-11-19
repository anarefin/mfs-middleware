// Seed data that is not in mw-seeder's manifest, copied from the services' own config (dev values).
// Keep in step with the sources named on each list.

/** mw-portal-api application.yml mw.portal.bulk.org-wallets (orgId claim -> the core wallet it pays from). */
export const ORGS: readonly { orgId: string; walletId: string; name: string }[] = [
  { orgId: "ORG001", walletId: "01311000001", name: "Meghna Garments Ltd" },
  { orgId: "ORG002", walletId: "01311000002", name: "Padma Foods Ltd" },
];

export type BillerProtocol = "REST" | "SOAP";
export type Biller = { code: string; name: string; category: string; partnerId: string; protocol: BillerProtocol };

/**
 * mw-adapter-billpay application.yml mw.billers (all served by the billagg simulator), sorted by code; the protocol is the
 * one the billagg route speaks for the biller (SOAP for KGDCL, JGTDSL, BTCL, LINK3, REST for the rest).
 */
export const BILLERS: readonly Biller[] = [
  { code: "BREB", name: "Bangladesh Rural Electrification Board (Palli Bidyut)", category: "ELECTRICITY", partnerId: "billagg", protocol: "REST" },
  { code: "BTCL", name: "Bangladesh Telecommunications Company", category: "TELECOM", partnerId: "billagg", protocol: "SOAP" },
  { code: "CWASA", name: "Chattogram WASA", category: "WATER", partnerId: "billagg", protocol: "REST" },
  { code: "DESCO", name: "Dhaka Electric Supply Company", category: "ELECTRICITY", partnerId: "billagg", protocol: "REST" },
  { code: "DPDC", name: "Dhaka Power Distribution Company", category: "ELECTRICITY", partnerId: "billagg", protocol: "REST" },
  { code: "JGTDSL", name: "Jalalabad Gas Transmission and Distribution System", category: "GAS", partnerId: "billagg", protocol: "SOAP" },
  { code: "KGDCL", name: "Karnaphuli Gas Distribution Company", category: "GAS", partnerId: "billagg", protocol: "SOAP" },
  { code: "LINK3", name: "Link3 Technologies", category: "INTERNET", partnerId: "billagg", protocol: "SOAP" },
  { code: "TITAS", name: "Titas Gas", category: "GAS", partnerId: "billagg", protocol: "REST" },
  { code: "WASA", name: "Dhaka WASA", category: "WATER", partnerId: "billagg", protocol: "REST" },
];

/** A biller as the app API lists it (GET /app/v1/billers: code, name, category?). */
export type BillerItem = { code: string; name: string; category?: string };

/** The fixed order of the biller categories, everywhere a list of them is shown (USSD menu, app, seed data). */
export const BILLER_CATEGORIES = ["ELECTRICITY", "WATER", "GAS", "TELECOM", "INTERNET"] as const;
/** The category of a biller that names none. */
export const OTHER_CATEGORY = "OTHER";
/** The category filter value that shows every biller. */
export const ALL_CATEGORIES = "ALL";

const CATEGORY_LABELS: Record<string, { en: string; bn: string }> = {
  ELECTRICITY: { en: "Electricity", bn: "বিদ্যুৎ" },
  WATER: { en: "Water", bn: "পানি" },
  GAS: { en: "Gas", bn: "গ্যাস" },
  TELECOM: { en: "Telecom", bn: "টেলিফোন" },
  INTERNET: { en: "Internet", bn: "ইন্টারনেট" },
  [OTHER_CATEGORY]: { en: "Other", bn: "অন্যান্য" },
};

const own = (o: object, k: string) => Object.prototype.hasOwnProperty.call(o, k);

/** A category's label (en or bn); an unknown category shows as its code. */
export function categoryLabel(category: string | undefined, locale: "en" | "bn" = "en"): string {
  const c = category || OTHER_CATEGORY;
  return (own(CATEGORY_LABELS, c) ? CATEGORY_LABELS[c]?.[locale] : undefined) ?? c;
}

const categoryOf = (b: BillerItem): string => b.category || OTHER_CATEGORY;

/** The protocol the catalogue names for a biller code; none for a code it does not know. */
export function protocolOf(code: string): BillerProtocol | undefined {
  return BILLERS.find((b) => b.code === code)?.protocol;
}

const rank = (c: string): number => {
  const i = (BILLER_CATEGORIES as readonly string[]).indexOf(c);
  return i >= 0 ? i : c === OTHER_CATEGORY ? BILLER_CATEGORIES.length + 1 : BILLER_CATEGORIES.length;
};
const byCategory = (a: string, b: string): number => rank(a) - rank(b) || a.localeCompare(b);
const byCode = (a: BillerItem, b: BillerItem): number => a.code.localeCompare(b.code);

/** The categories that have at least one biller: the fixed order first, unknown ones after (by code), "no category" last. */
export function categoriesOf(list: readonly BillerItem[]): string[] {
  return [...new Set(list.map(categoryOf))].sort(byCategory);
}

/** The billers of a category (ALL = every one) whose code or name contains the search text (case-insensitive), sorted by code. */
export function filterBillers<T extends BillerItem>(list: readonly T[], category: string, search: string): T[] {
  const q = search.trim().toLowerCase();
  return list
    .filter((b) => category === ALL_CATEGORIES || categoryOf(b) === category)
    .filter((b) => !q || b.code.toLowerCase().includes(q) || b.name.toLowerCase().includes(q))
    .sort(byCode);
}

/** The billers grouped by category, in the category order, each group sorted by code. */
export function groupBillers<T extends BillerItem>(list: readonly T[]): { category: string; label: string; billers: T[] }[] {
  return categoriesOf(list).map((category) => ({ category, label: categoryLabel(category), billers: filterBillers(list, category, "") }));
}

/** The billers of a GET /app/v1/billers answer; null when it is not a biller list. Items without a code are skipped. */
export function parseBillerList(body: unknown): BillerItem[] | null {
  const items = (body as { billers?: unknown } | null)?.billers;
  if (!Array.isArray(items)) return null;
  const out: BillerItem[] = [];
  for (const x of items) {
    if (!x || typeof x !== "object") continue;
    const { code, name, category } = x as Record<string, unknown>;
    if (typeof code !== "string" || !code) continue;
    out.push({ code, name: typeof name === "string" && name ? name : code, ...(typeof category === "string" && category ? { category } : {}) });
  }
  return out;
}

export type CheckoutMerchant = { id: string; keyId: string; name: string; walletId: string; secretEnv: string };

/**
 * mw-checkout application.yml mw.checkout.merchants (APISIX hmac-auth consumer -> wallet), with the
 * consumers' key ids (deploy/apisix/consumers-test) and the deploy/.env secret each signs with.
 */
export const DEFAULT_CHECKOUT_MERCHANT: CheckoutMerchant =
  { id: "merchant_demo", keyId: "merchant-demo", name: "Karim Traders", walletId: "01911000001", secretEnv: "CHECKOUT_DEMO_MERCHANT_SECRET" };

export const CHECKOUT_MERCHANTS: readonly CheckoutMerchant[] = [
  DEFAULT_CHECKOUT_MERCHANT,
  { id: "merchant_pharmacy", keyId: "merchant-pharmacy", name: "Lalbagh Pharmacy", walletId: "01911000002", secretEnv: "CHECKOUT_PHARMACY_MERCHANT_SECRET" },
  { id: "merchant_fashion", keyId: "merchant-fashion", name: "Gulshan Fashion House", walletId: "01911000003", secretEnv: "CHECKOUT_FASHION_MERCHANT_SECRET" },
];

/** The catalogue merchant with this id; anything else is the default (a form never names an env var). */
export const checkoutMerchant = (id: string | undefined | null): CheckoutMerchant =>
  CHECKOUT_MERCHANTS.find((m) => m.id === id) ?? DEFAULT_CHECKOUT_MERCHANT;

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
