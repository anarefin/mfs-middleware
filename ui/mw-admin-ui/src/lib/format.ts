import type { Amount, Party } from "./types";

export function formatAmount(a: Amount | undefined): string {
  if (!a) return "";
  const major = Math.trunc(a.valueMinor / 100);
  const minor = String(a.valueMinor % 100).padStart(2, "0");
  return `${a.currency} ${major.toLocaleString("en-US")}.${minor}`;
}

export function formatTime(iso: string | undefined): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return `${d.toISOString().slice(0, 19).replace("T", " ")} UTC`;
}

/** Party ids and names arrive masked from mw-admin-api (spec 18.3) unless revealed. */
export function partyLabel(p: Party | undefined): string {
  if (!p) return "";
  const base = [p.type, p.id].filter(Boolean).join(" ");
  return p.name ? `${base} (${p.name})` : base;
}
