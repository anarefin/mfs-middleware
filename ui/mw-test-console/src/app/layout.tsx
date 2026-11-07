import type { Metadata } from "next";
import Link from "next/link";
import type { ReactNode } from "react";
import "./globals.css";

// Every page renders per request: Next.js puts the CSP nonce (src/proxy.ts) on its scripts only then;
// a prerendered page would carry none, the CSP would block hydration and forms would post natively.
export const dynamic = "force-dynamic";

export const metadata: Metadata = {
  title: "Bits MFS test console",
  description: "Dev/test only: plays the app, USSD handset, partners, merchant and org portal against the local stack",
  robots: { index: false, follow: false },
};

const NAV: [string, string][] = [
  ["/", "Home"],
  ["/app", "App flows"],
  ["/ussd", "USSD phone"],
  ["/simulators", "Simulators & faults"],
  ["/inbox", "SMS & email"],
  ["/checkout", "Web checkout"],
  ["/bulk", "Bulk portal"],
];

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <body>
        <header className="topbar">
          <span className="brand">Bits MFS · test console</span>
          <nav>
            {NAV.map(([href, label]) => (
              <Link key={href} href={href}>{label}</Link>
            ))}
            <a href="/console" target="_blank" rel="noreferrer">Ops console ↗</a>
          </nav>
          <span className="spacer" />
          <span className="badge">DEV / TEST ONLY</span>
        </header>
        <main>{children}</main>
      </body>
    </html>
  );
}
