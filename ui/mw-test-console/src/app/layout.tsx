import { GeistMono } from "geist/font/mono";
import { GeistSans } from "geist/font/sans";
import type { Metadata } from "next";
import { cookies } from "next/headers";
import type { ReactNode } from "react";
import { AppShell } from "@/components/shell/app-shell";
import { SIDEBAR_COOKIE, THEME_COOKIE, parseTheme } from "@/lib/theme";
import "./globals.css";

// Every page renders per request: Next.js puts the CSP nonce (src/proxy.ts) on its scripts only then;
// a prerendered page would carry none, the CSP would block hydration and forms would post natively.
export const dynamic = "force-dynamic";

export const metadata: Metadata = {
  title: "Bits MFS test console",
  description: "Dev/test only: plays the app, USSD handset, partners, merchant and org portal against the local stack",
  robots: { index: false, follow: false },
};

export default async function RootLayout({ children }: { children: ReactNode }) {
  const jar = await cookies();
  const theme = parseTheme(jar.get(THEME_COOKIE)?.value);
  return (
    <html lang="en" className={`${theme} ${GeistSans.variable} ${GeistMono.variable}`} suppressHydrationWarning>
      <body>
        <AppShell theme={theme} collapsed={jar.get(SIDEBAR_COOKIE)?.value === "collapsed"}>{children}</AppShell>
      </body>
    </html>
  );
}
