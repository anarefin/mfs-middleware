// USSD menu trees as the console edits and previews them (spec 9.2, ticket 42). The shape is
// mw-ussd's menu JSON (`start`, `messages`, `nodes`), sent as is to mw-admin-api, which checks it
// fully (its MenuValidator, 400 MW-VAL-040). `menuProblems` is a light early check only. The
// preview walks the tree by mw-ussd's UssdEngine rules without running anything: an ACTION's
// outcome is chosen by the operator, a DYNAMIC list and an INPUT lookup show sample items.

import { parseWalletId } from "./approvals";
import type { ApiResult, MenuPreview } from "./types";

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

// --- tree editing (ticket 133a): pure, the input menu is never changed --------------------------

const NODE_ID = /^[A-Za-z0-9._-]{1,64}$/;
const EMPTY_TEXT = (): Text => ({ bn: "", en: "" });

function freeKey(options: MenuOption[]): string {
  let k = options.length + 1;
  while (options.some((o) => o.key === String(k))) k++;
  return String(k);
}

/** Links `child` under `parent`: a new option of a MENU / CONFIRM, the `next` of an INPUT / DYNAMIC. */
function link(menu: Menu, parent: string, child: string, label: Text): Menu | null {
  const p = menu.nodes[parent];
  if (!p) return null;
  if (p.type === "MENU" || p.type === "CONFIRM") {
    const options = p.options ?? [];
    return { ...menu, nodes: { ...menu.nodes, [parent]: { ...p, options: [...options, { key: freeKey(options), label, next: child }] } } };
  }
  if (p.type === "INPUT" || p.type === "DYNAMIC") return { ...menu, nodes: { ...menu.nodes, [parent]: { ...p, next: child } } };
  return null;
}

/** Drops every reference to `id` (options leading to it, next / onSuccess / onFailure). */
function unlink(menu: Menu, id: string): Menu {
  const nodes: Record<string, MenuNode> = {};
  for (const [k, n] of Object.entries(menu.nodes)) {
    const c: MenuNode = { ...n };
    if (n.options) c.options = n.options.filter((o) => o.next !== id);
    for (const f of ["next", "onSuccess", "onFailure"] as const) if (c[f] === id) delete c[f];
    nodes[k] = c;
  }
  return { ...menu, nodes };
}

/** A new node `id` of `type` under `parent`; the menu as it was when the id is bad or taken. */
export function addChild(menu: Menu, parent: string, id: string, type: NodeType): Menu {
  if (!NODE_ID.test(id) || menu.nodes[id]) return menu;
  const node: MenuNode = type === "ACTION" ? { type } : { type, text: EMPTY_TEXT() };
  return link({ ...menu, nodes: { ...menu.nodes, [id]: node } }, parent, id, EMPTY_TEXT()) ?? menu;
}

/** Moves `id` under `parent` (re-parent): its old option label goes with it. Never the start node. */
export function moveNode(menu: Menu, id: string, parent: string): Menu {
  if (id === menu.start || id === parent || !menu.nodes[id] || !menu.nodes[parent]) return menu;
  let label: Text = EMPTY_TEXT();
  for (const n of Object.values(menu.nodes)) {
    const o = n.options?.find((x) => x.next === id);
    if (o?.label) {
      label = o.label;
      break;
    }
  }
  return link(unlink(menu, id), parent, id, label) ?? menu;
}

/** Swaps option `index` of `nodeId` with its neighbour (`by` -1 up, +1 down). */
export function moveOption(menu: Menu, nodeId: string, index: number, by: -1 | 1): Menu {
  const n = menu.nodes[nodeId];
  const options = n?.options;
  const to = index + by;
  if (!n || !options || to < 0 || to >= options.length || index < 0 || index >= options.length) return menu;
  const copy = [...options];
  [copy[index], copy[to]] = [copy[to]!, copy[index]!];
  return { ...menu, nodes: { ...menu.nodes, [nodeId]: { ...n, options: copy } } };
}

/** Removes `id` and every reference to it. Never the start node. */
export function deleteNode(menu: Menu, id: string): Menu {
  if (id === menu.start || !menu.nodes[id]) return menu;
  const { [id]: _gone, ...rest } = unlink(menu, id).nodes;
  return { ...menu, nodes: rest };
}

// --- screen size (mw-ussd ScreenFormatter, spec 9.2) ----------------------------------------------

/** The GSM 03.38 basic character set mw-ussd checks (no extension table). */
const GSM7 = "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";
const isGsm7 = (s: string) => [...s].every((c) => GSM7.includes(c));

export type ScreenLimits = { gsm: number; ucs2: number; overflow: "PAGE" | "TRUNCATE" };
export type Mno = (typeof MNOS)[number];
/** mw-ussd `mw.ussd.mno.*` (dev config): limits, overflow and the short code dialled. */
export const MNO_SCREENS: Record<Mno, ScreenLimits & { shortCode: string }> = {
  gp: { gsm: 182, ucs2: 70, overflow: "PAGE", shortCode: "*268#" },
  robi: { gsm: 182, ucs2: 70, overflow: "PAGE", shortCode: "*269#" },
  bl: { gsm: 182, ucs2: 70, overflow: "PAGE", shortCode: "*270#" },
  teletalk: { gsm: 182, ucs2: 70, overflow: "TRUNCATE", shortCode: "*271#" },
};
export const DEFAULT_MORE: Text = { bn: "0. পরবর্তী", en: "0. Next" };
export const DEFAULT_BACK: Text = { bn: "00. পেছনে", en: "00. Back" }; // mw-ussd UssdEngine BACK_TEXT

const limitOf = (s: string, l: ScreenLimits) => (isGsm7(s) ? l.gsm : l.ucs2);

/** One text's size against an MNO screen (GSM-7 182 / UCS-2 70 by default). */
export function textCounter(text: string, l: ScreenLimits = MNO_SCREENS.gp) {
  const gsm = isGsm7(text);
  const limit = gsm ? l.gsm : l.ucs2;
  return { length: text.length, encoding: gsm ? "GSM-7" : "UCS-2", limit, over: text.length > limit } as const;
}

function breakAt(text: string, room: number): number {
  const nl = text.lastIndexOf("\n", room);
  if (nl > 0) return nl;
  const sp = text.lastIndexOf(" ", room);
  return sp > 0 ? sp : room;
}

/** mw-ussd `ScreenFormatter.pages`: a screen cut to the limit, or pages ending with `more` and `back`. */
export function screenPages(text: string, l: ScreenLimits, more: string, back: string): string[] {
  const limit = limitOf(text, l);
  if (text.length <= limit) return [text];
  if (l.overflow === "TRUNCATE") return [text.substring(0, limit)];
  const nextSuffix = `\n${more}`;
  const backSuffix = back.trim() ? `\n${back}` : "";
  const fullSuffix = nextSuffix + backSuffix;
  const pageLimit = Math.min(limit, limitOf(text + more + backSuffix, l));
  const room = pageLimit - fullSuffix.length;
  if (room < 1) return [text.substring(0, limit)];
  const pages: string[] = [];
  let rest = text;
  while (rest.length + backSuffix.length > pageLimit) {
    const cut = breakAt(rest, room);
    pages.push(rest.substring(0, cut) + fullSuffix);
    rest = rest.substring(cut);
    if (rest.startsWith("\n") || rest.startsWith(" ")) rest = rest.substring(1);
    if (!rest) break;
  }
  pages.push(rest + backSuffix);
  return pages;
}

/** A node's screen in one language as the editor sizes it (text and options, placeholders as typed). */
export function nodeScreen(menu: Menu, id: string, lang: Lang, l: ScreenLimits = MNO_SCREENS.gp) {
  const n = menu.nodes[id];
  const pick = (x: Text | undefined) => (lang === "en" ? x?.en : x?.bn) ?? "";
  let text = pick(n?.text);
  for (const o of n?.options ?? []) text += `\n${o.key} ${pick(o.label)}`;
  const more = pick(menu.messages.more) || pick(DEFAULT_MORE);
  const back = pick(menu.messages.back) || pick(DEFAULT_BACK);
  return { ...textCounter(text, l), pages: screenPages(text, l, more, back).length };
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

// --- server preview (ticket 133b) ---------------------------------------------------------------

export type ServerVerdict =
  | { ok: true; shortCode: string; rootMenuId: string; screens: string[] }
  | { ok: false; problems: string[] };

/** mw-admin-api's preview answer as a verdict: MenuValidator's problems (MW-VAL-040) one per line. */
export function serverVerdict(r: ApiResult<MenuPreview>): ServerVerdict {
  if (r.ok) return { ok: true, shortCode: r.data.shortCode, rootMenuId: r.data.rootMenuId, screens: r.data.screens.map((s) => s.screen) };
  const issues = r.error.code === "MW-VAL-040" ? (r.error.details ?? []).flatMap((d) => (d.issue ? [d.issue] : [])) : [];
  return { ok: false, problems: issues.length > 0 ? issues : [`${r.error.code}: ${r.error.message}`] };
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
  /** The MNO whose screen limits page the text (mw-ussd `mw.ussd.mno.*`). */
  mno: Mno;
  /** The current screen's pages (one when it fits) and the one showing. */
  pages: string[];
  page: number;
  /** The nodes `00` returns to (mw-ussd's back stack; a PIN input is never on it). */
  back: string[];
  /** A paged end screen: the session ends on its last page. */
  endAfterPages: boolean;
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
  return show(at, render(p.menu, at, vars), input, n.type === "END");
}

/** Shows a screen as mw-ussd would: paged at the MNO's limit with the more / back lines. */
function show(p: Preview, text: string, input: string | null, end: boolean): Preview {
  const more = say(p.menu.messages.more, p.lang) || say(DEFAULT_MORE, p.lang);
  const back = say(p.menu.messages.back, p.lang) || say(DEFAULT_BACK, p.lang);
  const pages = screenPages(text, MNO_SCREENS[p.mno], more, back);
  const paged = pages.length > 1;
  const screen = pages[0]!;
  return { ...p, pages, page: 0, screen, ended: end && !paged, endAfterPages: end && paged, trail: [...p.trail, { input, screen }] };
}

function end(p: Preview, screen: string, input: string | null): Preview {
  return show(p, screen, input, true);
}

function invalid(p: Preview, n: MenuNode, input: string): Preview {
  const attempts = p.attempts + 1;
  if (attempts >= MAX_INVALID_ATTEMPTS) return end(p, message(p.menu, "tooManyAttempts", p.lang), input);
  const prefix = n.validator?.error ? say(n.validator.error, p.lang) : message(p.menu, "invalid", p.lang);
  return show({ ...p, attempts }, `${prefix}\n${render(p.menu, p, {})}`, input, false);
}

/** The key that shows the next page: the first word of the `more` line, without a trailing dot. */
function moreKey(p: Preview): string {
  const more = (say(p.menu.messages.more, p.lang) || say(DEFAULT_MORE, p.lang)).trim();
  const key = more.split(" ")[0] ?? "";
  return key.endsWith(".") ? key.slice(0, -1) : key;
}

const BACK_INPUT = "00";

function atPage(p: Preview, page: number, input: string): Preview {
  const screen = p.pages[page]!;
  const ended = p.endAfterPages && page === p.pages.length - 1;
  return { ...p, page, screen, ended, trail: [...p.trail, { input, screen }] };
}

/** A new session dialled by `msisdn` on `mno` (default gp), in Bangla (mw-ussd's default language). */
export function startPreview(menu: Menu, { msisdn, lang = "bn", mno = "gp" }: { msisdn: string; lang?: Lang; mno?: Mno }): Preview {
  const p: Preview = {
    menu, msisdn, lang, mno, nodeId: menu.start, inputs: {}, attempts: 0, pages: [], page: 0, back: [], endAfterPages: false,
    screen: "", ended: false, trail: [],
  };
  return enter(p, menu.start, {}, "COMPLETED", null);
}

/** The subscriber answers the current screen; `outcome` is what an ACTION reached next returns. */
export function stepPreview(p: Preview, raw: string, outcome: ActionOutcome = "COMPLETED"): Preview {
  if (p.ended) return p;
  const input = raw.trim();
  if (input === BACK_INPUT) {
    if (p.page > 0) return atPage(p, p.page - 1, input);
    if (p.back.length > 0) {
      const prev = { ...p, nodeId: p.back.at(-1)!, back: p.back.slice(0, -1), attempts: 0 };
      return show(prev, render(p.menu, prev, {}), input, false);
    }
  }
  if (p.page + 1 < p.pages.length && (p.endAfterPages || input === moreKey(p))) return atPage(p, p.page + 1, input);
  const n = p.menu.nodes[p.nodeId]!;
  // mw-ussd pushes the node it leaves, except a PIN input (00 from the next screen skips it)
  const pin = n.type === "INPUT" && n.validator?.type === "pin";
  const left: Preview = pin ? p : { ...p, back: [...p.back, p.nodeId] };
  switch (n.type) {
    case "MENU":
    case "CONFIRM": {
      const o = (n.options ?? []).find((x) => x.key === input);
      if (!o) return invalid(p, n, input);
      const lang: Lang = o.switchLanguage ? (p.lang === "bn" ? "en" : "bn") : p.lang;
      const own = { msisdn: p.msisdn, mno: "gp" };
      const inputs = { ...p.inputs };
      for (const [k, v] of Object.entries(o.set ?? {})) inputs[k] = interpolate(v, p.inputs, own);
      return enter({ ...left, lang, inputs }, o.next ?? "", {}, outcome, input);
    }
    case "DYNAMIC": {
      const items = sampleItems(n.source);
      const i = Number.parseInt(input, 10) - 1;
      if (!(i >= 0 && i < items.length)) return invalid(p, n, input);
      return enter({ ...left, inputs: { ...p.inputs, [n.store ?? ""]: items[i]!.value } }, n.next ?? "", {}, outcome, input);
    }
    case "INPUT": {
      const valid = validate(n.validator, input, p.msisdn);
      if (valid === null) return invalid(p, n, n.validator?.type === "pin" ? "****" : input);
      if (n.validator?.type === "pin") return enter(left, n.next ?? "", {}, outcome, "****"); // never stored
      return enter({ ...left, inputs: { ...p.inputs, [n.store ?? ""]: valid } }, n.next ?? "", {}, outcome, input);
    }
    default:
      return end(p, message(p.menu, "expired", p.lang), input);
  }
}
