// USSD menu trees as the console edits and previews them (spec 9.2, ticket 42). The shape is
// mw-ussd's menu JSON (`start`, `messages`, `nodes`), sent as is to mw-admin-api, which checks it
// fully (its MenuValidator, 400 MW-VAL-040). `menuProblems` is a light early check only. The
// preview walks the tree by mw-ussd's UssdEngine rules without running anything: an ACTION's
// outcome is chosen by the operator, a DYNAMIC list and an INPUT lookup show sample items.

import { parseWalletId } from "./approvals";

export type Text = { bn?: string; en?: string };
export type NodeType = "MENU" | "INPUT" | "CONFIRM" | "ACTION" | "DYNAMIC" | "END";
export type MenuOption = { key: string; label?: Text; next?: string; switchLanguage?: boolean; set?: Record<string, string> };
export type Validator = { type: string; pattern?: string; min?: string; max?: string; notSelf?: boolean; error?: Text };
export type MenuNode = {
  type: NodeType;
  text?: Text;
  options?: MenuOption[];
  validator?: Validator;
  store?: string;
  next?: string;
  flow?: string;
  mapping?: Record<string, string>;
  onSuccess?: string;
  onFailure?: string;
  source?: string;
  lookup?: string;
};
export type Menu = { start: string; messages: Record<string, Text>; nodes: Record<string, MenuNode> };
export type Lang = "bn" | "en";

export const ENGINE_MESSAGES = ["invalid", "tooManyAttempts", "expired", "processing", "unavailable", "more"] as const;
export const NODE_TYPES: readonly NodeType[] = ["MENU", "INPUT", "CONFIRM", "ACTION", "DYNAMIC", "END"];
/** mw-admin-api `mw.content.mnos`. */
export const MNOS = ["gp", "robi", "bl", "teletalk"] as const;
export const MAX_INVALID_ATTEMPTS = 3; // mw-ussd mw.ussd.max-invalid-attempts
const MENU_ID = /^[a-z0-9][a-z0-9_-]{0,31}$/;

export function parseMenuId(raw: unknown): string | null {
  return typeof raw === "string" && MENU_ID.test(raw) ? raw : null;
}

const isObject = (v: unknown): v is Record<string, unknown> => typeof v === "object" && v !== null && !Array.isArray(v);

/** A tree pasted or posted as JSON -> the menu without `menuId` / `version` (the API sets them). */
export function parseMenu(json: string): Menu | null {
  let v: unknown;
  try {
    v = JSON.parse(json);
  } catch {
    return null;
  }
  if (!isObject(v) || typeof v.start !== "string" || !v.start || !isObject(v.messages) || !isObject(v.nodes)) return null;
  if (Object.keys(v.nodes).length === 0) return null;
  return { start: v.start, messages: v.messages as Menu["messages"], nodes: v.nodes as Menu["nodes"] };
}

const both = (t: Text | undefined) => !!t && !!t.bn?.trim() && !!t.en?.trim();

/** Early problems (sorted): dangling references, a text without bn or en. */
export function menuProblems(menu: Menu): string[] {
  const p: string[] = [];
  const ref = (where: string, id: string | undefined) => {
    if (id === undefined || id === "") p.push(`${where}: missing`);
    else if (!menu.nodes[id]) p.push(`${where}: no node ${id}`);
  };
  ref("start", menu.start);
  for (const m of ENGINE_MESSAGES) if (!both(menu.messages[m])) p.push(`messages.${m}: needs bn and en`);
  for (const [id, n] of Object.entries(menu.nodes)) {
    const at = `nodes.${id}`;
    if (!NODE_TYPES.includes(n.type)) {
      p.push(`${at}.type: unknown`);
      continue;
    }
    if (n.type !== "ACTION" && !both(n.text)) p.push(`${at}.text: needs bn and en`);
    if (n.type === "MENU" || n.type === "CONFIRM") {
      if (!n.options?.length) p.push(`${at}.options: missing`);
      n.options?.forEach((o, i) => {
        if (!both(o.label)) p.push(`${at}.options[${i}].label: needs bn and en`);
        ref(`${at}.options[${i}].next`, o.next);
      });
    }
    if (n.type === "INPUT" || n.type === "DYNAMIC") ref(`${at}.next`, n.next);
    if (n.validator?.error && !both(n.validator.error)) p.push(`${at}.validator.error: needs bn and en`);
    if (n.type === "ACTION") {
      ref(`${at}.onSuccess`, n.onSuccess);
      ref(`${at}.onFailure`, n.onFailure);
    }
  }
  return p.sort();
}

function children(n: MenuNode): string[] {
  const out: string[] = [];
  for (const o of n.options ?? []) if (o.next) out.push(o.next);
  for (const id of [n.next, n.onSuccess, n.onFailure]) if (id) out.push(id);
  return out;
}

/** The nodes depth-first from `start` (each once), then the ones nothing leads to. */
export function tree(menu: Menu): { id: string; depth: number; unreachable?: true }[] {
  const seen = new Set<string>();
  const out: { id: string; depth: number; unreachable?: true }[] = [];
  const visit = (id: string, depth: number) => {
    if (seen.has(id) || !menu.nodes[id]) return;
    seen.add(id);
    out.push({ id, depth });
    for (const c of children(menu.nodes[id]!)) visit(c, depth + 1);
  };
  visit(menu.start, 0);
  for (const id of Object.keys(menu.nodes)) if (!seen.has(id)) out.push({ id, depth: 0, unreachable: true });
  return out;
}

/** Activations of a proposal: MNOs chosen once each (known ones only), go-live as UTC. */
export function parseActivations(mnos: readonly string[], goLive: Record<string, string>): { mno: string; goLiveAt?: string }[] | null {
  if (mnos.length === 0 || new Set(mnos).size !== mnos.length) return null;
  const out: { mno: string; goLiveAt?: string }[] = [];
  for (const mno of mnos) {
    if (!(MNOS as readonly string[]).includes(mno)) return null;
    const raw = goLive[mno]?.trim();
    if (!raw) {
      out.push({ mno });
      continue;
    }
    // a datetime-local value, read as UTC (the console shows every time in UTC)
    const d = new Date(/[zZ]|[+-]\d\d:\d\d$/.test(raw) ? raw : `${raw}Z`);
    if (Number.isNaN(d.getTime())) return null;
    out.push({ mno, goLiveAt: d.toISOString() });
  }
  return out;
}

// --- preview -----------------------------------------------------------------------------------

export type ActionOutcome = "COMPLETED" | "FAILED" | "PROCESSING";
export type Preview = {
  menu: Menu;
  msisdn: string;
  lang: Lang;
  nodeId: string;
  inputs: Record<string, string>;
  attempts: number;
  screen: string;
  ended: boolean;
  /** What the subscriber saw and typed (a PIN shown as ****). */
  trail: { input: string | null; screen: string }[];
};

const PREVIEW_TXN = "PREVIEW-TXN";
const PREVIEW_ERROR: Record<Lang, string> = { bn: "প্রিভিউ: কোর প্রত্যাখ্যান করেছে", en: "Preview: the core refused" };
const AMOUNT = /^[0-9]{1,9}(\.[0-9]{1,2})?$/;

const say = (t: Text | undefined, lang: Lang) => (lang === "en" ? t?.en ?? t?.bn : t?.bn ?? t?.en) ?? "";
const message = (menu: Menu, key: string, lang: Lang) => say(menu.messages[key], lang);

function interpolate(s: string, inputs: Record<string, string>, vars: Record<string, string>) {
  return s.replace(/\{([A-Za-z0-9_]+)\}/g, (_w, k: string) => vars[k] ?? inputs[k] ?? "");
}

/** The DYNAMIC list a lookup would give; the preview has no lookup data, so two samples. */
function sampleItems(source: string | undefined): { label: string; value: string }[] {
  return [1, 2].map((i) => ({ label: `${source ?? "list"} ${i}`, value: `${source ?? "item"}-${i}` }));
}

function render(menu: Menu, p: Preview, vars: Record<string, string>): string {
  const n = menu.nodes[p.nodeId]!;
  let t = interpolate(say(n.text, p.lang), p.inputs, vars);
  if (n.type === "MENU" || n.type === "CONFIRM") for (const o of n.options ?? []) t += `\n${o.key} ${say(o.label, p.lang)}`;
  if (n.type === "DYNAMIC") sampleItems(n.source).forEach((it, i) => (t += `\n${i + 1} ${it.label}`));
  return t;
}

function validate(v: Validator | undefined, raw: string, own: string): string | null {
  const s = raw.trim();
  switch (v?.type) {
    case "msisdn": {
      const c = parseWalletId(s);
      return !c || (v.notSelf && c === parseWalletId(own)) ? null : c;
    }
    case "amount": {
      if (!AMOUNT.test(s)) return null;
      const a = Number(s);
      if (a <= 0 || (v.min !== undefined && a < Number(v.min)) || (v.max !== undefined && a > Number(v.max))) return null;
      return a.toFixed(2);
    }
    case "pin": return safeMatch(v.pattern ?? "[0-9]{4}", s);
    case "numeric":
      if (!/^[0-9]+$/.test(s) || (v.min !== undefined && s.length < Number(v.min)) || (v.max !== undefined && s.length > Number(v.max))) return null;
      return s;
    case "regex": return safeMatch(v.pattern ?? "", s);
    default: return null;
  }
}

function safeMatch(pattern: string, s: string): string | null {
  try {
    return new RegExp(`^(?:${pattern})$`).test(s) ? s : null;
  } catch {
    return null;
  }
}

/** Arrives at a node: an ACTION resolves by `outcome`, anything else is shown. */
function enter(p: Preview, nodeId: string, vars: Record<string, string>, outcome: ActionOutcome, input: string | null): Preview {
  const n = p.menu.nodes[nodeId];
  if (!n) return end(p, `(no node ${nodeId})`, input);
  if (n.type === "ACTION") {
    if (outcome === "PROCESSING") return end(p, message(p.menu, "processing", p.lang), input);
    const next = outcome === "COMPLETED" ? n.onSuccess : n.onFailure;
    const v: Record<string, string> = outcome === "COMPLETED" ? { txnId: PREVIEW_TXN } : { error: PREVIEW_ERROR[p.lang] };
    return enter({ ...p, nodeId: next ?? "" }, next ?? "", v, outcome, input);
  }
  const at = { ...p, nodeId, attempts: 0 };
  const screen = render(p.menu, at, vars);
  return { ...at, screen, ended: n.type === "END", trail: [...p.trail, { input, screen }] };
}

function end(p: Preview, screen: string, input: string | null): Preview {
  return { ...p, screen, ended: true, trail: [...p.trail, { input, screen }] };
}

function invalid(p: Preview, n: MenuNode, input: string): Preview {
  const attempts = p.attempts + 1;
  if (attempts >= MAX_INVALID_ATTEMPTS) return end(p, message(p.menu, "tooManyAttempts", p.lang), input);
  const prefix = n.validator?.error ? say(n.validator.error, p.lang) : message(p.menu, "invalid", p.lang);
  const screen = `${prefix}\n${render(p.menu, p, {})}`;
  return { ...p, attempts, screen, trail: [...p.trail, { input, screen }] };
}

/** A new session dialled by `msisdn`, in Bangla (mw-ussd's default language). */
export function startPreview(menu: Menu, { msisdn, lang = "bn" }: { msisdn: string; lang?: Lang }): Preview {
  const p: Preview = { menu, msisdn, lang, nodeId: menu.start, inputs: {}, attempts: 0, screen: "", ended: false, trail: [] };
  return enter(p, menu.start, {}, "COMPLETED", null);
}

/** The subscriber answers the current screen; `outcome` is what an ACTION reached next returns. */
export function stepPreview(p: Preview, raw: string, outcome: ActionOutcome = "COMPLETED"): Preview {
  if (p.ended) return p;
  const input = raw.trim();
  const n = p.menu.nodes[p.nodeId]!;
  switch (n.type) {
    case "MENU":
    case "CONFIRM": {
      const o = (n.options ?? []).find((x) => x.key === input);
      if (!o) return invalid(p, n, input);
      const lang: Lang = o.switchLanguage ? (p.lang === "bn" ? "en" : "bn") : p.lang;
      const own = { msisdn: p.msisdn, mno: "gp" };
      const inputs = { ...p.inputs };
      for (const [k, v] of Object.entries(o.set ?? {})) inputs[k] = interpolate(v, p.inputs, own);
      return enter({ ...p, lang, inputs }, o.next ?? "", {}, outcome, input);
    }
    case "DYNAMIC": {
      const items = sampleItems(n.source);
      const i = Number.parseInt(input, 10) - 1;
      if (!(i >= 0 && i < items.length)) return invalid(p, n, input);
      return enter({ ...p, inputs: { ...p.inputs, [n.store ?? ""]: items[i]!.value } }, n.next ?? "", {}, outcome, input);
    }
    case "INPUT": {
      const valid = validate(n.validator, input, p.msisdn);
      if (valid === null) return invalid(p, n, n.validator?.type === "pin" ? "****" : input);
      if (n.validator?.type === "pin") return enter(p, n.next ?? "", {}, outcome, "****"); // never stored
      return enter({ ...p, inputs: { ...p.inputs, [n.store ?? ""]: valid } }, n.next ?? "", {}, outcome, input);
    }
    default:
      return end(p, message(p.menu, "expired", p.lang), input);
  }
}
