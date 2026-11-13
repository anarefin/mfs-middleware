"use client";
// USSD menu tree editor with a preview walk (ticket 42). Edits stay in the browser until the
// operator files them: the proposal posts the whole tree to the server action (mw-admin-api
// checks it, MW-VAL-040). The preview runs mw-ussd's screen rules on the edited tree; no flow,
// lookup or PIN leaves the browser (the PIN shows as ****).

import { Braces, CircleCheck, Eye, ListTree, MessageSquareText, Phone, Plus, Send, Trash2, TriangleAlert } from "lucide-react";
import { useActionState, useMemo, useState } from "react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { CardContent, CardDescription, CardHeader, CardSection, CardTitle } from "@/components/ui/card";
import { Field, InlineField } from "@/components/ui/field";
import { Input, NativeSelect, Textarea } from "@/components/ui/input";
import { cn } from "@/lib/utils";
import {
  ENGINE_MESSAGES, MNOS, NODE_TYPES, menuProblems, parseMenu, startPreview, stepPreview, tree,
  type ActionOutcome, type Lang, type Menu, type MenuNode, type MenuOption, type Preview, type Text,
} from "@/lib/ussd-menu";
import type { ActionState } from "../actions";
import { ApiError } from "../api-error";

type Props = {
  initial: Menu | null;
  baseLabel: string | null;
  action: ((prev: ActionState, form: FormData) => Promise<ActionState>) | null;
};

const EMPTY: Text = { bn: "", en: "" };
const STARTER: Menu = {
  start: "main",
  messages: Object.fromEntries(ENGINE_MESSAGES.map((m) => [m, { ...EMPTY }])),
  nodes: { main: { type: "END", text: { ...EMPTY } } },
};

function TextPair({ label, value, onChange }: { label: string; value: Text | undefined; onChange: (t: Text) => void }) {
  const v = value ?? EMPTY;
  return (
    <div className="grid gap-3 sm:grid-cols-2">
      <Field label={`${label} (bn)`}><Textarea lang="bn" dir="auto" rows={2} className="min-h-16" value={v.bn ?? ""} onChange={(e) => onChange({ ...v, bn: e.target.value })} /></Field>
      <Field label={`${label} (en)`}><Textarea lang="en" rows={2} className="min-h-16" value={v.en ?? ""} onChange={(e) => onChange({ ...v, en: e.target.value })} /></Field>
    </div>
  );
}

function NodeSelect({ label, value, ids, onChange }: { label: string; value: string | undefined; ids: string[]; onChange: (id: string) => void }) {
  return (
    <Field label={label}>
      <NativeSelect className="font-mono text-xs" value={value ?? ""} onChange={(e) => onChange(e.target.value)}>
        <option value="">(none)</option>
        {ids.map((id) => <option key={id} value={id}>{id}</option>)}
      </NativeSelect>
    </Field>
  );
}

export function MenuEditor({ initial, baseLabel, action }: Props) {
  const [menu, setMenu] = useState<Menu>(initial ?? STARTER);
  const [selected, setSelected] = useState<string>((initial ?? STARTER).start);
  const [json, setJson] = useState<string | null>(null);
  const [jsonError, setJsonError] = useState<string | null>(null);
  const [newId, setNewId] = useState("");
  const [newType, setNewType] = useState<MenuNode["type"]>("MENU");

  const ids = useMemo(() => Object.keys(menu.nodes).sort(), [menu]);
  const problems = useMemo(() => menuProblems(menu), [menu]);
  const node = menu.nodes[selected];

  const setNode = (id: string, n: MenuNode) => setMenu((m) => ({ ...m, nodes: { ...m.nodes, [id]: n } }));
  const setOption = (i: number, o: MenuOption) => node && setNode(selected, { ...node, options: (node.options ?? []).map((x, j) => (j === i ? o : x)) });

  function addNode() {
    const id = newId.trim();
    if (!/^[A-Za-z0-9._-]{1,64}$/.test(id) || menu.nodes[id]) return;
    setNode(id, newType === "ACTION" ? { type: "ACTION" } : { type: newType, text: { ...EMPTY } });
    setSelected(id);
    setNewId("");
  }

  function removeNode() {
    if (selected === menu.start) return;
    setMenu((m) => {
      const { [selected]: _gone, ...rest } = m.nodes;
      return { ...m, nodes: rest };
    });
    setSelected(menu.start);
  }

  function applyJson() {
    const m = json === null ? null : parseMenu(json);
    if (!m) {
      setJsonError("Not a menu tree: JSON with start, messages and nodes");
      return;
    }
    setMenu(m);
    setSelected(m.start);
    setJson(null);
    setJsonError(null);
  }

  return (
    <div className="grid items-start gap-5 xl:grid-cols-[minmax(0,1fr)_24rem]">
      <CardSection aria-label="Menu tree" className="xl:row-span-2">
        <CardHeader>
          <CardTitle><ListTree />Tree</CardTitle>
          {baseLabel ? <span className="text-sm text-muted-foreground">edited from {baseLabel}</span> : <span className="text-sm text-muted-foreground">new menu</span>}
        </CardHeader>
        <div className="grid md:grid-cols-[minmax(12rem,16rem)_minmax(0,1fr)]">
          <ul className="m-0 flex list-none flex-col gap-0.5 border-b p-2 md:border-r md:border-b-0" data-testid="menu-tree">
            {tree(menu).map(({ id, depth, unreachable }) => (
              <li key={id} style={{ paddingLeft: depth * 14 }}>
                <button
                  type="button"
                  className={cn(
                    "flex w-full min-w-0 items-baseline gap-2 rounded-md px-2 py-1.5 text-left font-mono text-xs text-foreground transition-colors hover:bg-accent",
                    id === selected && "bg-accent font-semibold",
                    unreachable && "text-muted-foreground",
                  )}
                  aria-pressed={id === selected}
                  onClick={() => setSelected(id)}
                >
                  <span className="truncate">{id}</span> <span className="ml-auto shrink-0 font-sans text-[11px] font-normal text-muted-foreground">{menu.nodes[id]?.type}{unreachable ? ", unreachable" : ""}</span>
                </button>
              </li>
            ))}
          </ul>
          <div className="flex min-w-0 flex-col gap-4 p-4" aria-label="Node">
            {node ? (
              <>
                <div className="flex flex-wrap items-center gap-2">
                  <h3 className="m-0 font-mono text-sm font-semibold">{selected}{selected === menu.start ? " (start)" : ""}</h3>
                  <Badge tone="outline">{node.type}</Badge>
                </div>
                <Field label="Type" className="max-w-56">
                  <NativeSelect value={node.type} onChange={(e) => setNode(selected, { ...node, type: e.target.value as MenuNode["type"] })}>
                    {NODE_TYPES.map((t) => <option key={t}>{t}</option>)}
                  </NativeSelect>
                </Field>
                {node.type !== "ACTION" ? <TextPair label="Text" value={node.text} onChange={(text) => setNode(selected, { ...node, text })} /> : null}
                {node.type === "MENU" || node.type === "CONFIRM" ? (
                  <fieldset className="flex flex-col gap-3">
                    {(node.options ?? []).map((o, i) => (
                      <div key={i} className="flex flex-col gap-3 rounded-md border bg-muted/40 p-3" aria-label={`Option ${o.key}`}>
                        <div className="flex flex-wrap items-end gap-3">
                          <Field label="Key" className="w-20"><Input value={o.key} size={2} className="font-mono" onChange={(e) => setOption(i, { ...o, key: e.target.value })} /></Field>
                          <div className="min-w-48 flex-1"><NodeSelect label="Next" value={o.next} ids={ids} onChange={(next) => setOption(i, { ...o, next })} /></div>
                          <Button type="button" variant="ghost" size="sm" className="text-muted-foreground" onClick={() => setNode(selected, { ...node, options: (node.options ?? []).filter((_, j) => j !== i) })}><Trash2 />Remove option</Button>
                        </div>
                        <TextPair label={`Option ${o.key} label`} value={o.label} onChange={(label) => setOption(i, { ...o, label })} />
                      </div>
                    ))}
                    <Button type="button" variant="outline" size="sm" className="self-start" onClick={() => setNode(selected, { ...node, options: [...(node.options ?? []), { key: String((node.options?.length ?? 0) + 1), label: { ...EMPTY } }] })}>
                      <Plus />Add option
                    </Button>
                  </fieldset>
                ) : null}
                {node.type === "INPUT" ? (
                  <div className="grid gap-3 sm:grid-cols-3">
                    <Field label="Validator">
                      <NativeSelect value={node.validator?.type ?? ""} onChange={(e) => setNode(selected, { ...node, validator: { ...node.validator, type: e.target.value } })}>
                        {["msisdn", "amount", "pin", "numeric", "regex"].map((t) => <option key={t}>{t}</option>)}
                      </NativeSelect>
                    </Field>
                    <Field label="Store as"><Input className="font-mono" value={node.store ?? ""} onChange={(e) => setNode(selected, { ...node, store: e.target.value })} /></Field>
                    <NodeSelect label="Next" value={node.next} ids={ids} onChange={(next) => setNode(selected, { ...node, next })} />
                  </div>
                ) : null}
                {node.type === "DYNAMIC" ? (
                  <div className="grid gap-3 sm:grid-cols-3">
                    <Field label="Lookup source"><Input className="font-mono" value={node.source ?? ""} onChange={(e) => setNode(selected, { ...node, source: e.target.value })} /></Field>
                    <Field label="Store as"><Input className="font-mono" value={node.store ?? ""} onChange={(e) => setNode(selected, { ...node, store: e.target.value })} /></Field>
                    <NodeSelect label="Next" value={node.next} ids={ids} onChange={(next) => setNode(selected, { ...node, next })} />
                  </div>
                ) : null}
                {node.type === "ACTION" ? (
                  <div className="grid gap-3 sm:grid-cols-3">
                    <Field label="Flow"><Input className="font-mono" value={node.flow ?? ""} onChange={(e) => setNode(selected, { ...node, flow: e.target.value })} /></Field>
                    <NodeSelect label="On success" value={node.onSuccess} ids={ids} onChange={(onSuccess) => setNode(selected, { ...node, onSuccess })} />
                    <NodeSelect label="On failure" value={node.onFailure} ids={ids} onChange={(onFailure) => setNode(selected, { ...node, onFailure })} />
                  </div>
                ) : null}
                {selected !== menu.start ? <Button type="button" variant="outline-destructive" size="sm" className="self-start" onClick={removeNode}><Trash2 />Delete node</Button> : null}
              </>
            ) : <p className="m-0 text-sm text-muted-foreground">Pick a node.</p>}
            <div className="flex flex-wrap items-end gap-3 border-t pt-4">
              <Field label="New node id" className="min-w-40 flex-1"><Input className="font-mono" value={newId} onChange={(e) => setNewId(e.target.value)} /></Field>
              <Field label="Type" className="w-36"><NativeSelect value={newType} onChange={(e) => setNewType(e.target.value as MenuNode["type"])}>{NODE_TYPES.map((t) => <option key={t}>{t}</option>)}</NativeSelect></Field>
              <Button type="button" variant="outline" onClick={addNode}><Plus />Add node</Button>
            </div>
          </div>
        </div>
        <details className="border-t">
          <summary className="cursor-pointer px-4 py-3 text-sm font-medium select-none hover:bg-muted/50">Engine messages (bn and en)</summary>
          <div className="flex flex-col gap-4 px-4 pb-4">
            {ENGINE_MESSAGES.map((k) => (
              <TextPair key={k} label={k} value={menu.messages[k]} onChange={(t) => setMenu((m) => ({ ...m, messages: { ...m.messages, [k]: t } }))} />
            ))}
          </div>
        </details>
        <details className="border-t">
          <summary className="cursor-pointer px-4 py-3 text-sm font-medium select-none hover:bg-muted/50">JSON</summary>
          <div className="flex flex-col gap-3 px-4 pb-4">
            <Textarea className="font-mono text-xs leading-relaxed" rows={16} aria-label="Menu JSON" value={json ?? JSON.stringify(menu, null, 2)} onChange={(e) => setJson(e.target.value)} spellCheck={false} />
            <Button type="button" variant="outline" size="sm" className="self-start" onClick={applyJson}><Braces />Apply JSON</Button>
            {jsonError ? <p className="error m-0 text-sm text-bad" role="alert">{jsonError}</p> : null}
          </div>
        </details>
        <div className="border-t p-4">
          {problems.length > 0 ? (
            <Alert variant="warning" data-testid="menu-problems">
              <TriangleAlert />
              <AlertTitle>Not ready yet <span className="font-normal">(mw-admin-api would refuse it):</span></AlertTitle>
              <AlertDescription>
                <ul className="font-mono text-xs">{problems.map((p) => <li key={p}>{p}</li>)}</ul>
              </AlertDescription>
            </Alert>
          ) : <p className="m-0 flex items-center gap-2 text-sm text-good" data-testid="menu-problems"><CircleCheck className="size-4" aria-hidden="true" />No problems found (the API checks again when filed).</p>}
        </div>
      </CardSection>

      <PreviewPanel menu={menu} />
      {action ? <ProposeForm menu={menu} action={action} /> : null}
    </div>
  );
}

function PreviewPanel({ menu }: { menu: Menu }) {
  const [msisdn, setMsisdn] = useState("01712345678");
  const [lang, setLang] = useState<Lang>("bn");
  const [outcome, setOutcome] = useState<ActionOutcome>("COMPLETED");
  const [preview, setPreview] = useState<Preview | null>(null);
  const [input, setInput] = useState("");

  const dial = () => setPreview(startPreview(menu, { msisdn, lang }));
  const send = () => {
    if (!preview) return;
    setPreview(stepPreview(preview, input, outcome));
    setInput("");
  };

  return (
    <CardSection aria-label="Preview">
      <CardHeader className="flex-col items-start gap-1">
        <CardTitle><Eye />Preview walk</CardTitle>
        <CardDescription>Runs the edited tree as mw-ussd would show it; no transaction is made.</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div className="grid grid-cols-2 items-end gap-3">
          <Field label="Dialling MSISDN (sample)" className="col-span-2"><Input inputMode="tel" value={msisdn} onChange={(e) => setMsisdn(e.target.value)} /></Field>
          <Field label="Language"><NativeSelect value={lang} onChange={(e) => setLang(e.target.value as Lang)}><option value="bn">bn</option><option value="en">en</option></NativeSelect></Field>
          <Field label="Action outcome"><NativeSelect value={outcome} onChange={(e) => setOutcome(e.target.value as ActionOutcome)}>
            <option value="COMPLETED">completed</option><option value="FAILED">failed</option><option value="PROCESSING">processing</option>
          </NativeSelect></Field>
          <Button type="button" className="col-span-2" onClick={dial}><Phone />Dial</Button>
        </div>
        {preview ? (
          <>
            <div className="w-full max-w-sm self-center rounded-2xl border bg-muted p-2 shadow-xs">
              <pre className="m-0 min-h-36 rounded-xl bg-screen p-4 font-mono text-sm leading-relaxed whitespace-pre-wrap break-words text-screen-foreground" lang={preview.lang} data-testid="ussd-screen">{preview.screen}</pre>
            </div>
            {preview.ended ? <p className="m-0 text-center text-sm text-muted-foreground" data-testid="ussd-ended">Session ended.</p> : (
              <form className="flex items-end gap-2" aria-label="Reply to the screen" onSubmit={(e) => { e.preventDefault(); send(); }}>
                <Field label="Reply" className="flex-1"><Input className="font-mono" value={input} onChange={(e) => setInput(e.target.value)} autoComplete="off" /></Field>
                <Button type="submit" variant="outline"><Send />Send</Button>
              </form>
            )}
            <ol className="trail m-0 flex list-none flex-wrap gap-1.5 p-0 text-xs text-muted-foreground">{preview.trail.map((s, i) => <li key={i} className="rounded border bg-muted px-1.5 py-0.5 font-mono">{s.input ?? "(dial)"}</li>)}</ol>
          </>
        ) : null}
      </CardContent>
    </CardSection>
  );
}

function ProposeForm({ menu, action }: { menu: Menu; action: (prev: ActionState, form: FormData) => Promise<ActionState> }) {
  const [state, formAction, pending] = useActionState(action, null);
  return (
    <CardSection aria-label="Propose menu">
      <CardHeader className="flex-col items-start gap-1">
        <CardTitle><MessageSquareText />Propose this version</CardTitle>
        <CardDescription>Nothing goes live until a different operator (ops-checker) approves it. Go-live times are UTC; empty or past = at approval.</CardDescription>
      </CardHeader>
      <CardContent>
        <form action={formAction} aria-label="Menu proposal" aria-busy={pending} className="flex flex-col gap-3">
          <fieldset disabled={pending}>
            <input type="hidden" name="menu" value={JSON.stringify(menu)} />
            <div className="flex flex-col gap-2">
              {MNOS.map((m) => (
                <div key={m} className="grid grid-cols-[5.5rem_minmax(0,1fr)] items-end gap-3">
                  <InlineField className="h-9 font-mono text-xs"><input type="checkbox" name="mno" value={m} defaultChecked className="size-4 accent-primary" /> {m}</InlineField>
                  <Field label={`Go-live ${m}`}><Input type="datetime-local" name={`goLive.${m}`} /></Field>
                </div>
              ))}
            </div>
            <Field label="Reason"><Input name="reason" required maxLength={500} /></Field>
            <Button type="submit" className="self-start">Request publish</Button>
          </fieldset>
          {state ? <ApiError status={state.status} error={state.error} /> : null}
        </form>
      </CardContent>
    </CardSection>
  );
}
