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
        <Link className="brand" href="/transactions">upay ops console</Link>
        <nav>
          <Link href="/transactions">Transactions</Link>
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
