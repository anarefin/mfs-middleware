import { LogOut } from "lucide-react";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import type { ReactNode } from "react";
import { auth, signOut } from "@/auth";
import { AppShell } from "@/components/shell/app-shell";
import { Button } from "@/components/ui/button";
import { BASE_PATH } from "@/lib/server/env";
import { SIDEBAR_COOKIE, THEME_COOKIE, parseTheme } from "@/lib/theme";

export const dynamic = "force-dynamic";

function initials(name: string): string {
  return name.split(/[-_.\s]+/).filter(Boolean).slice(-2).map((p) => p[0]!.toUpperCase()).join("") || "?";
}

export default async function ConsoleLayout({ children }: { children: ReactNode }) {
  const session = await auth();
  if (!session || session.error) redirect("/login");
  const jar = await cookies();

  async function logout() {
    "use server";
    await signOut({ redirectTo: `${BASE_PATH}/login` });
  }

  const name = session.user?.name ?? "operator";
  const account = (
    <div className="flex items-center gap-2.5 px-1">
      <div className="grid size-8 shrink-0 place-items-center rounded-full border bg-card text-[11px] font-semibold text-foreground" aria-hidden="true">{initials(name)}</div>
      <div data-account-text className="min-w-0 flex-1 leading-tight">
        <div className="truncate text-sm font-medium text-foreground" data-testid="operator">
          {name} <span className="font-normal text-muted-foreground">({session.roles.join(", ") || "no role"})</span>
        </div>
      </div>
      <form action={logout}>
        <Button type="submit" variant="ghost" size="icon-sm" title="Sign out" aria-label="Sign out"><LogOut /></Button>
      </form>
    </div>
  );

  return (
    <AppShell theme={parseTheme(jar.get(THEME_COOKIE)?.value)} collapsed={jar.get(SIDEBAR_COOKIE)?.value === "collapsed"} account={account}>
      {children}
    </AppShell>
  );
}
