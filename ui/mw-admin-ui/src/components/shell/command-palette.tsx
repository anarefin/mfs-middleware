"use client";

import { ArrowRight, Search } from "lucide-react";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import { Dialog, DialogContent, DialogDescription, DialogTitle } from "@/components/ui/dialog";
import { Kbd } from "@/components/ui/kbd";
import { cn } from "@/lib/utils";
import { NAV, paletteTargets } from "./nav";

/** ⌘K / Ctrl+K: jump to a page, open a transaction by id, or search by MSISDN / partner ref. */
export function CommandPalette() {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState("");
  const [index, setIndex] = useState(0);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setOpen((o) => !o);
      }
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const items = useMemo(() => {
    const term = q.trim().toLowerCase();
    const pages = NAV.filter((n) => !term || n.label.toLowerCase().includes(term)).map((n) => ({ label: n.label, hint: n.hint, href: n.href, icon: n.icon }));
    const found = paletteTargets(q).map((t) => ({ ...t, hint: "Transactions", icon: Search }));
    return [...found, ...pages];
  }, [q]);

  function go(href: string) {
    setOpen(false);
    setQ("");
    router.push(href);
  }

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        aria-label="Command palette"
        className="flex h-8 min-w-0 items-center gap-2 rounded-md border bg-muted/60 pr-1.5 pl-2.5 text-sm text-muted-foreground transition-colors hover:bg-muted sm:w-72"
      >
        <Search className="size-4 shrink-0" />
        <span className="hidden min-w-0 flex-1 truncate text-left sm:inline">Txn ID, MSISDN or page…</span>
        <Kbd className="hidden sm:inline-flex">⌘K</Kbd>
      </button>
      <Dialog open={open} onOpenChange={(o) => { setOpen(o); if (!o) setQ(""); }}>
        <DialogContent onKeyDown={(e) => {
          if (e.key === "ArrowDown") { e.preventDefault(); setIndex((i) => Math.min(i + 1, items.length - 1)); }
          if (e.key === "ArrowUp") { e.preventDefault(); setIndex((i) => Math.max(i - 1, 0)); }
          if (e.key === "Enter" && items[index]) { e.preventDefault(); go(items[index].href); }
        }}>
          <DialogTitle className="sr-only">Command palette</DialogTitle>
          <DialogDescription className="sr-only">Type a transaction ID, an MSISDN, a partner reference or a page name.</DialogDescription>
          <div className="flex items-center gap-2 border-b px-4">
            <Search className="size-4 text-muted-foreground" />
            <input
              autoFocus
              value={q}
              onChange={(e) => { setQ(e.target.value); setIndex(0); }}
              placeholder="Transaction ID, MSISDN, partner ref or page"
              aria-label="Command"
              className="h-12 w-full bg-transparent text-[15px] outline-none placeholder:text-muted-foreground"
            />
          </div>
          <ul role="listbox" aria-label="Destinations" className="m-0 max-h-80 list-none overflow-y-auto p-1.5">
            {items.length === 0 ? <li className="px-3 py-6 text-center text-muted-foreground">Nothing matches.</li> : null}
            {items.map((it, i) => {
              const Icon = it.icon;
              return (
                <li
                  key={it.href + it.label}
                  role="option"
                  aria-selected={i === index}
                  onMouseEnter={() => setIndex(i)}
                  onClick={() => go(it.href)}
                  className={cn("flex cursor-pointer items-center gap-3 rounded-md px-3 py-2", i === index && "bg-accent text-accent-foreground")}
                >
                  <Icon className="size-4 text-muted-foreground" />
                  <span className="min-w-0 flex-1 truncate">{it.label}</span>
                  <span className="text-xs text-muted-foreground">{it.hint}</span>
                  {i === index ? <ArrowRight className="size-3.5 text-muted-foreground" /> : null}
                </li>
              );
            })}
          </ul>
          <div className="flex gap-4 border-t px-4 py-2 text-xs text-muted-foreground">
            <span><Kbd>↑</Kbd> <Kbd>↓</Kbd> move</span><span><Kbd>↵</Kbd> open</span><span><Kbd>esc</Kbd> close</span>
          </div>
        </DialogContent>
      </Dialog>
    </>
  );
}
