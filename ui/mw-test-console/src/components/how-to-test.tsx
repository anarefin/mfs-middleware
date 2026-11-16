"use client";
// The collapsible "How to test this page" panel under each PageHeader: the goal, the steps, the
// magic values, what to expect (lib/how-to.ts, from docs/testing-guide.html) and fill buttons that
// pre-fill the page's form (components/fill-scope.tsx listens).

import { BookOpenCheck, ChevronRight, Wand2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { FILL_EVENT, HOW_TO, type FillDetail, type PageKey } from "@/lib/how-to";

function Label({ children }: { children: React.ReactNode }) {
  return <p className="m-0 text-sm font-semibold uppercase tracking-wide text-muted-foreground">{children}</p>;
}

export function HowToTest({ page }: { page: PageKey }) {
  const h = HOW_TO[page];
  const fill = (values: Record<string, string>) =>
    window.dispatchEvent(new CustomEvent<FillDetail>(FILL_EVENT, { detail: { page, values } }));
  return (
    <details className="group rounded-lg border bg-card" data-testid="how-to-test">
      <summary className="flex cursor-pointer list-none items-start gap-2 px-4 py-3 [&::-webkit-details-marker]:hidden">
        <ChevronRight className="mt-0.5 size-4 shrink-0 text-muted-foreground transition-transform group-open:rotate-90" />
        <BookOpenCheck className="mt-0.5 size-4 shrink-0 text-info" />
        <span className="min-w-0">
          <span className="font-medium">How to test this page</span>
          <span className="text-muted-foreground"> · {h.goal}</span>
        </span>
      </summary>
      <div className="grid gap-5 border-t px-4 py-4 lg:grid-cols-2">
        <div className="flex min-w-0 flex-col gap-2">
          <Label>Steps</Label>
          <ol className="m-0 flex list-decimal flex-col gap-1 pl-5">
            {h.steps.map((s) => <li key={s}>{s}</li>)}
          </ol>
        </div>
        <div className="flex min-w-0 flex-col gap-2">
          <Label>Expect</Label>
          <ul className="m-0 flex list-disc flex-col gap-1 pl-5">
            {h.expect.map((s) => <li key={s}>{s}</li>)}
          </ul>
        </div>
        {h.magic?.length ? (
          <div className="flex min-w-0 flex-col gap-2 lg:col-span-2">
            <Label>Magic values</Label>
            <dl className="m-0 grid gap-x-4 gap-y-1 sm:grid-cols-[minmax(0,16rem)_minmax(0,1fr)]">
              {h.magic.map((m) => (
                <div key={m.value} className="contents">
                  <dt className="font-mono text-sm">{m.value}</dt>
                  <dd className="m-0 text-muted-foreground">{m.meaning}</dd>
                </div>
              ))}
            </dl>
          </div>
        ) : null}
        {h.fills?.length ? (
          <div className="flex min-w-0 flex-col gap-2 lg:col-span-2">
            <Label>Fill the form</Label>
            <div className="flex flex-wrap gap-2">
              {h.fills.map((f) => (
                <Button key={f.label} type="button" variant="outline" size="sm" onClick={() => fill(f.values)} data-testid="how-to-fill">
                  <Wand2 />{f.label}
                </Button>
              ))}
            </div>
          </div>
        ) : null}
      </div>
    </details>
  );
}
