import { ArrowLeftRight, CircleCheck, FileText, Plug, Smartphone, Wallet, type LucideIcon } from "lucide-react";

export type NavItem = { href: string; label: string; icon: LucideIcon; hint: string };

// The link names are what operators and the e2e suite click (getByRole("link", { name: "Wallets" })).
export const NAV: NavItem[] = [
  { href: "/transactions", label: "Transactions", icon: ArrowLeftRight, hint: "Search and inspect transactions" },
  { href: "/approvals", label: "Approvals", icon: CircleCheck, hint: "Maker-checker inbox" },
  { href: "/wallets", label: "Wallets", icon: Wallet, hint: "Request a wallet unlock" },
  { href: "/partners", label: "Partners", icon: Plug, hint: "Partner state and timeouts" },
  { href: "/ussd", label: "USSD menus", icon: Smartphone, hint: "Edit and preview USSD menus" },
  { href: "/templates", label: "Templates", icon: FileText, hint: "SMS and email templates" },
];

export function activeNav(pathname: string): NavItem | undefined {
  return NAV.find((n) => pathname === n.href || pathname.startsWith(`${n.href}/`));
}

/** Where the command palette sends a typed value: a txn id opens it, an MSISDN or reference searches. */
export function paletteTargets(q: string): { label: string; href: string }[] {
  const v = q.trim();
  if (!v) return [];
  if (/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v)) {
    return [{ label: `Open transaction ${v}`, href: `/transactions/${v}` }];
  }
  const digits = v.replace(/[\s-]/g, "");
  if (/^\+?\d{10,13}$/.test(digits)) {
    return [{ label: `Find transactions of MSISDN ${v}`, href: `/transactions?msisdn=${encodeURIComponent(digits)}` }];
  }
  return [{ label: `Find transactions with partner ref ${v}`, href: `/transactions?partnerRef=${encodeURIComponent(v)}` }];
}
