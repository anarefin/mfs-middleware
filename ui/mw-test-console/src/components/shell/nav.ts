import { Cpu, Database, FlaskConical, House, Inbox, KeyRound, KeySquare, Layers, ScrollText, ShoppingCart, Smartphone, Zap, type LucideIcon } from "lucide-react";

export type NavItem = { href: string; label: string; icon: LucideIcon };

export const NAV: NavItem[] = [
  { href: "/", label: "Home", icon: House },
  { href: "/credentials", label: "Logins & secrets", icon: KeySquare },
  { href: "/seed-data", label: "Seed data", icon: Database },
  { href: "/app", label: "App flows", icon: Cpu },
  { href: "/auth", label: "Wallet auth", icon: KeyRound },
  { href: "/ussd", label: "USSD phone", icon: Smartphone },
  { href: "/simulators", label: "Simulators & faults", icon: Zap },
  { href: "/inbox", label: "SMS & email", icon: Inbox },
  { href: "/checkout", label: "Web checkout", icon: ShoppingCart },
  { href: "/bulk", label: "Bulk portal", icon: Layers },
  { href: "/transactions", label: "Transactions", icon: ScrollText },
];

export const BRAND_ICON = FlaskConical;

export function activeNav(pathname: string): NavItem | undefined {
  if (pathname === "/") return NAV[0];
  return NAV.find((n) => n.href !== "/" && (pathname === n.href || pathname.startsWith(`${n.href}/`)));
}
