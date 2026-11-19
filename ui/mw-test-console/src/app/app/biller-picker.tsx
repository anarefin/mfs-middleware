"use client";
// The bill pay flow's biller: the live list of GET /app/v1/billers (the seed catalogue while it cannot
// be read, with a note saying why), category chips (All + the categories present), a search box over
// code and name, and the select (name="biller", the field the form submits). The REST / SOAP badge
// comes from the seed catalogue by code; an unknown code shows none. Billers outside the filter stay
// in the select as hidden options, so a fill or a scenario can still pick any of them.

import { Database, RefreshCw, Wifi } from "lucide-react";
import { useEffect, useState, useTransition } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Field } from "@/components/ui/field";
import { Input, NativeSelect } from "@/components/ui/input";
import { billerLabel, defaultBiller } from "@/lib/app-flow-options";
import { ALL_CATEGORIES, BILLERS, categoriesOf, categoryLabel, filterBillers, protocolOf, type BillerItem } from "@/lib/seed-catalogue";
import { listBillers, type BillerList } from "../actions/app";

const FALLBACK: readonly BillerItem[] = BILLERS;

export function BillerPicker({ user, refreshKey }: { user: string; refreshKey: number }) {
  const [list, setList] = useState<BillerList | null>(null);
  const [loading, start] = useTransition();
  const [category, setCategory] = useState<string>(ALL_CATEGORIES);
  const [search, setSearch] = useState("");
  const [biller, setBiller] = useState(() => defaultBiller(FALLBACK));

  const load = () => start(async () => setList(await listBillers(user)));
  useEffect(() => {
    start(async () => setList(await listBillers(user)));
  }, [user, refreshKey]);

  const billers: readonly BillerItem[] = list?.live ? list.billers : FALLBACK;
  const shown = filterBillers(billers, category, search);
  const all = filterBillers(billers, ALL_CATEGORIES, "");
  const selected = billers.some((b) => b.code === biller) ? biller : defaultBiller(billers);
  const protocol = protocolOf(selected);

  // a narrower filter moves the selection to its first biller when the selected one falls out of it
  const refilter = (c: string, q: string) => {
    setCategory(c);
    setSearch(q);
    const next = filterBillers(billers, c, q);
    const first = next[0];
    if (first && !next.some((b) => b.code === selected)) setBiller(first.code);
  };
  // a biller picked outside the filter (a fill) clears the filter, so the select shows it
  const pick = (code: string) => {
    setBiller(code);
    if (!shown.some((b) => b.code === code)) {
      setCategory(ALL_CATEGORIES);
      setSearch("");
    }
  };

  return (
    <div className="flex flex-col gap-2" data-testid="biller-picker">
      <div className="flex flex-col gap-1.5">
        <span className="text-sm font-medium text-muted-foreground">Category</span>
        <div className="flex flex-wrap gap-1.5" role="group" aria-label="biller category">
          {[ALL_CATEGORIES, ...categoriesOf(billers)].map((c) => (
            <Button key={c} type="button" size="sm" variant={category === c ? "default" : "outline"} className="rounded-full"
              aria-pressed={category === c} onClick={() => refilter(c, search)} data-testid={`biller-category-${c}`}>
              {c === ALL_CATEGORIES ? "All" : categoryLabel(c)}
            </Button>
          ))}
        </div>
      </div>
      <Field label="Search biller (code or name)">
        <Input value={search} onChange={(e) => refilter(category, e.target.value)} placeholder="e.g. KGDCL or gas" data-testid="biller-search" />
      </Field>
      <Field label={<span className="flex items-center gap-2">Biller {protocol ? <Badge tone={protocol === "SOAP" ? "info" : "outline"} data-testid="biller-protocol">{protocol}</Badge> : null}</span>}>
        <NativeSelect name="biller" value={selected} onChange={(e) => pick(e.target.value)} data-testid="biller">
          {all.map((b) => {
            const visible = shown.some((s) => s.code === b.code);
            return <option key={b.code} value={b.code} hidden={!visible}>{billerLabel(b)}</option>;
          })}
        </NativeSelect>
      </Field>
      {shown.length === 0 ? <p className="m-0 text-sm text-warn" data-testid="biller-none">No biller matches the filter; {selected} stays selected.</p> : null}
      <p className="m-0 flex items-center gap-2 text-sm text-muted-foreground" data-testid="biller-source">
        {list?.live ? <Wifi className="size-4 shrink-0" /> : <Database className="size-4 shrink-0" />}
        <span className="min-w-0 flex-1">
          {loading && !list ? "Loading the billers…" : list?.live
            ? `Live list from GET /app/v1/billers (${list.billers.length} billers).`
            : `Seed catalogue shown (${FALLBACK.length} billers): ${list?.reason ?? "the live list is not loaded yet"}.`}
        </span>
        <Button type="button" variant="ghost" size="icon-sm" onClick={load} disabled={loading || !user} aria-label="reload the billers" data-testid="biller-reload">
          <RefreshCw className={loading ? "animate-spin" : undefined} />
        </Button>
      </p>
    </div>
  );
}
