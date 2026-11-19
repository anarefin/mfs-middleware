"use client";
// A table with a filter box: the server renders the cells, the client hides the rows whose
// search text does not contain the filter (case-insensitive). Rows with a group get a group header
// row before the first shown row of each group (rows come in group order).

import { Fragment, type ReactNode } from "react";
import { useState } from "react";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

export type FilterRow = { key: string; search: string; cells: ReactNode[]; group?: string };

export function FilterTable({ id, head, rows, empty = "Nothing seeded." }: { id: string; head: string[]; rows: FilterRow[]; empty?: string }) {
  const [q, setQ] = useState("");
  const f = q.trim().toLowerCase();
  const shown = f ? rows.filter((r) => r.search.toLowerCase().includes(f)) : rows;
  return (
    <div className="flex flex-col gap-2">
      {rows.length > 4 ? (
        <Input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Filter (name, username, number, role…)" aria-label={`Filter ${id}`} className="max-w-sm" data-testid={`filter-${id}`} />
      ) : null}
      <div className="overflow-hidden rounded-lg border">
        <Table data-testid={`table-${id}`}>
          <TableHeader className="bg-muted/50"><TableRow>{head.map((h) => <TableHead key={h}>{h}</TableHead>)}</TableRow></TableHeader>
          <TableBody>
            {shown.length ? shown.map((r, n) => (
              <Fragment key={r.key}>
                {r.group !== undefined && r.group !== shown[n - 1]?.group ? (
                  <TableRow className="bg-muted/30 hover:bg-muted/30" data-testid={`group-${id}-${r.group}`}>
                    <TableCell colSpan={head.length} className="text-sm font-semibold text-foreground">{r.group}</TableCell>
                  </TableRow>
                ) : null}
                <TableRow data-testid={`row-${r.key}`}>{r.cells.map((c, i) => <TableCell key={i}>{c}</TableCell>)}</TableRow>
              </Fragment>
            )) : <TableRow><TableCell colSpan={head.length} className="text-muted-foreground">{f ? "No row matches the filter." : empty}</TableCell></TableRow>}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}
