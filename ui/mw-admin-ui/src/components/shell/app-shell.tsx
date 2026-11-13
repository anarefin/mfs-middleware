"use client";

import { ChevronRight, Menu, PanelLeft } from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useState, type ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { Dialog, DialogDescription, DialogTitle, DialogTrigger, SheetContent } from "@/components/ui/dialog";
import { SIDEBAR_COOKIE, type Theme } from "@/lib/theme";
import { cn } from "@/lib/utils";
import { CommandPalette } from "./command-palette";
import { NAV, activeNav } from "./nav";
import { ThemeToggle } from "./theme-toggle";

type Props = { theme: Theme; collapsed: boolean; account: ReactNode; children: ReactNode };

function Brand({ collapsed }: { collapsed: boolean }) {
  return (
    <div className="flex items-center gap-2.5 px-2 pt-1 pb-3">
      <div className="grid size-7 shrink-0 place-items-center rounded-md bg-primary text-[13px] font-bold text-primary-foreground">B</div>
      <span className={cn("truncate font-semibold text-foreground", collapsed && "sr-only")}>Bits ops console</span>
    </div>
  );
}

function NavLinks({ collapsed, pathname, onNavigate }: { collapsed: boolean; pathname: string; onNavigate?: () => void }) {
  const active = activeNav(pathname);
  return (
    <nav aria-label="Sections" className="flex flex-col gap-0.5">
      <div className={cn("px-2.5 pt-2 pb-1 text-[11px] font-medium tracking-wider text-muted-foreground uppercase", collapsed && "sr-only")}>Operations</div>
      {NAV.map((n) => {
        const Icon = n.icon;
        const on = active?.href === n.href;
        return (
          <Link
            key={n.href}
            href={n.href}
            onClick={onNavigate}
            aria-current={on ? "page" : undefined}
            title={collapsed ? n.label : undefined}
            className={cn(
              "flex items-center gap-2.5 rounded-md px-2.5 py-1.5 text-sidebar-foreground no-underline transition-colors hover:bg-sidebar-accent hover:text-sidebar-accent-foreground hover:no-underline",
              on && "bg-sidebar-accent font-medium text-sidebar-accent-foreground shadow-xs ring-1 ring-sidebar-border",
              collapsed && "justify-center px-0",
            )}
          >
            <Icon className="size-4 shrink-0" />
            <span className={cn("truncate", collapsed && "sr-only")}>{n.label}</span>
          </Link>
        );
      })}
    </nav>
  );
}

export function AppShell({ theme, collapsed: initialCollapsed, account, children }: Props) {
  const pathname = usePathname();
  const [collapsed, setCollapsed] = useState(initialCollapsed);
  const [menuOpen, setMenuOpen] = useState(false);
  const section = activeNav(pathname);
  const detail = section && pathname !== section.href ? decodeURIComponent(pathname.slice(section.href.length + 1)) : undefined;

  function toggle() {
    const next = !collapsed;
    document.cookie = `${SIDEBAR_COOKIE}=${next ? "collapsed" : "open"}; path=/; max-age=31536000; samesite=lax; secure`;
    setCollapsed(next);
  }

  return (
    <div className="flex min-h-svh">
      <aside
        className={cn(
          "sticky top-0 hidden h-svh shrink-0 flex-col border-r border-sidebar-border bg-sidebar p-2.5 transition-[width] duration-200 md:flex",
          collapsed ? "w-[60px]" : "w-60",
        )}
      >
        <Brand collapsed={collapsed} />
        <NavLinks collapsed={collapsed} pathname={pathname} />
        <div className={cn("mt-auto border-t border-sidebar-border pt-3", collapsed && "[&_[data-account-text]]:sr-only [&>div]:flex-col [&>div]:px-0")}>{account}</div>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="sticky top-0 z-30 flex h-13 items-center gap-2 border-b bg-card/90 px-3 backdrop-blur sm:px-4">
          <Dialog open={menuOpen} onOpenChange={setMenuOpen}>
            <DialogTrigger asChild>
              <Button variant="ghost" size="icon-sm" className="md:hidden" aria-label="Open menu"><Menu /></Button>
            </DialogTrigger>
            <SheetContent className="p-3">
              <DialogTitle className="sr-only">Menu</DialogTitle>
              <DialogDescription className="sr-only">Console sections</DialogDescription>
              <Brand collapsed={false} />
              <NavLinks collapsed={false} pathname={pathname} onNavigate={() => setMenuOpen(false)} />
              <div className="mt-auto border-t border-sidebar-border pt-3">{account}</div>
            </SheetContent>
          </Dialog>
          <Button variant="ghost" size="icon-sm" className="hidden md:inline-flex" onClick={toggle} aria-label={collapsed ? "Expand sidebar" : "Collapse sidebar"}>
            <PanelLeft />
          </Button>
          <div className="flex min-w-0 items-center gap-1.5 text-muted-foreground" aria-hidden="true">
            <span className="hidden sm:inline">Ops console</span>
            {section ? <><ChevronRight className="hidden size-3.5 sm:inline" /><span className={cn(!detail && "font-medium text-foreground")}>{section.label}</span></> : null}
            {detail ? <><ChevronRight className="size-3.5" /><span className="truncate font-mono text-xs font-medium text-foreground">{detail}</span></> : null}
          </div>
          <div className="ml-auto flex items-center gap-1.5">
            <CommandPalette />
            <ThemeToggle initial={theme} />
          </div>
        </header>
        <main className="mx-auto flex w-full max-w-[1400px] min-w-0 flex-1 flex-col gap-5 p-4 sm:p-6">{children}</main>
      </div>
    </div>
  );
}
