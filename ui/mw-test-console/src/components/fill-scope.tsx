"use client";
// Applies a How-to "fill" (lib/how-to.ts FILL_EVENT) to the page's form controls inside it: a key
// "field" sets the controls with data-fill="field", else the visible ones with name="field"; a key
// "scope:field" looks only inside [data-testid="scope"]. Values are set through the native setter
// and an input + change event, so controlled React inputs update their state as when typed. One
// frame between keys lets a re-keyed form (App flows' type) render before the next field is set.

import { useEffect, useRef, type ReactNode } from "react";
import { FILL_EVENT, type FillDetail, type PageKey } from "@/lib/how-to";

type Control = HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement;

function controls(root: HTMLElement, key: string): Control[] {
  const [scopeId, field] = key.includes(":") ? (key.split(":") as [string, string]) : [undefined, key];
  const scope = scopeId ? root.querySelector<HTMLElement>(`[data-testid="${CSS.escape(scopeId)}"]`) : root;
  if (!scope) return [];
  const tagged = scope.querySelectorAll<Control>(`[data-fill="${CSS.escape(field)}"]`);
  if (tagged.length) return [...tagged];
  return [...scope.querySelectorAll<Control>(`[name="${CSS.escape(field)}"]:not([type="hidden"])`)];
}

function setValue(el: Control, value: string): void {
  if (el instanceof HTMLInputElement && el.type === "checkbox") {
    if (el.checked !== (value === "true" || value === "on")) el.click();
    return;
  }
  const proto = el instanceof HTMLSelectElement ? HTMLSelectElement.prototype : el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
  Object.getOwnPropertyDescriptor(proto, "value")?.set?.call(el, value);
  el.dispatchEvent(new Event("input", { bubbles: true }));
  el.dispatchEvent(new Event("change", { bubbles: true }));
}

const frame = () => new Promise<void>((r) => requestAnimationFrame(() => requestAnimationFrame(() => r())));

export function FillScope({ page, children }: { page: PageKey; children: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const onFill = async (e: Event) => {
      const d = (e as CustomEvent<FillDetail>).detail;
      if (!d || d.page !== page || !ref.current) return;
      for (const [key, value] of Object.entries(d.values)) {
        for (const el of controls(ref.current, key)) setValue(el, value);
        await frame();
      }
    };
    window.addEventListener(FILL_EVENT, onFill);
    return () => window.removeEventListener(FILL_EVENT, onFill);
  }, [page]);
  return <div ref={ref} className="contents">{children}</div>;
}
