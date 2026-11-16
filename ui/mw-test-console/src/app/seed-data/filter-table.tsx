"use client";
// A table with a filter box: the server renders the cells, the client hides the rows whose
// search text does not contain the filter (case-insensitive).

import type { ReactNode } from "react";
import { useState } from "react";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

export type FilterRow = { key: string; search: string; cells: ReactNode[] };

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
            {shown.length ? shown.map((r) => (
              <TableRow key={r.key} data-testid={`row-${r.key}`}>{r.cells.map((c, i) => <TableCell key={i}>{c}</TableCell>)}</TableRow>
            )) : <TableRow><TableCell colSpan={head.length} className="text-muted-foreground">{f ? "No row matches the filter." : empty}</TableCell></TableRow>}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}
