import Link from "next/link";
import { redirect } from "next/navigation";
import type { ReactNode } from "react";
import { auth, signOut } from "@/auth";
import { BASE_PATH } from "@/lib/server/env";

export const dynamic = "force-dynamic";

export default async function ConsoleLayout({ children }: { children: ReactNode }) {
  const session = await auth();
  if (!session || session.error) redirect("/login");

  async function logout() {
    "use server";
    await signOut({ redirectTo: `${BASE_PATH}/login` });
  }

  return (
    <>
      <header className="topbar">
        <Link className="brand" href="/transactions">Bits ops console</Link>
        <nav>
          <Link href="/transactions">Transactions</Link>
          <Link href="/approvals">Approvals</Link>
          <Link href="/wallets">Wallets</Link>
          <Link href="/partners">Partners</Link>
          <Link href="/ussd">USSD menus</Link>
          <Link href="/templates">Templates</Link>
        </nav>
        <span className="spacer" />
        <span className="who" data-testid="operator">
          {session.user?.name ?? "operator"} ({session.roles.join(", ") || "no role"})
        </span>
        <form action={logout}>
          <button type="submit">Sign out</button>
        </form>
      </header>
      <main>{children}</main>
    </>
  );
}
