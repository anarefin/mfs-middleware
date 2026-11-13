import { Cpu, FlaskConical, House, Inbox, Layers, ShoppingCart, Smartphone, Zap, type LucideIcon } from "lucide-react";

export type NavItem = { href: string; label: string; icon: LucideIcon };

export const NAV: NavItem[] = [
  { href: "/", label: "Home", icon: House },
  { href: "/app", label: "App flows", icon: Cpu },
  { href: "/ussd", label: "USSD phone", icon: Smartphone },
  { href: "/simulators", label: "Simulators & faults", icon: Zap },
  { href: "/inbox", label: "SMS & email", icon: Inbox },
  { href: "/checkout", label: "Web checkout", icon: ShoppingCart },
  { href: "/bulk", label: "Bulk portal", icon: Layers },
];

export const BRAND_ICON = FlaskConical;

export function activeNav(pathname: string): NavItem | undefined {
  if (pathname === "/") return NAV[0];
  return NAV.find((n) => n.href !== "/" && (pathname === n.href || pathname.startsWith(`${n.href}/`)));
}
