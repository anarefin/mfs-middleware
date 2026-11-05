"use client";
// USSD menu tree editor with a preview walk (ticket 42). Edits stay in the browser until the
// operator files them: the proposal posts the whole tree to the server action (mw-admin-api
// checks it, MW-VAL-040). The preview runs mw-ussd's screen rules on the edited tree; no flow,
// lookup or PIN leaves the browser (the PIN shows as ****).

import { useActionState, useMemo, useState } from "react";
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
    <div className="textpair">
      <label>{label} (bn)<textarea lang="bn" dir="auto" rows={2} value={v.bn ?? ""} onChange={(e) => onChange({ ...v, bn: e.target.value })} /></label>
      <label>{label} (en)<textarea lang="en" rows={2} value={v.en ?? ""} onChange={(e) => onChange({ ...v, en: e.target.value })} /></label>
    </div>
  );
}

function NodeSelect({ label, value, ids, onChange }: { label: string; value: string | undefined; ids: string[]; onChange: (id: string) => void }) {
  return (
    <label>{label}
      <select value={value ?? ""} onChange={(e) => onChange(e.target.value)}>
        <option value="">(none)</option>
        {ids.map((id) => <option key={id} value={id}>{id}</option>)}
      </select>
    </label>
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
    <div className="menu-editor">
      <section className="panel" aria-label="Menu tree">
        <h2>Tree {baseLabel ? <span className="hint">edited from {baseLabel}</span> : <span className="hint">new menu</span>}</h2>
        <div className="editor-grid">
          <ul className="tree" data-testid="menu-tree">
            {tree(menu).map(({ id, depth, unreachable }) => (
              <li key={id} style={{ paddingLeft: depth * 14 }}>
                <button type="button" className={id === selected ? "node selected" : "node"} aria-pressed={id === selected} onClick={() => setSelected(id)}>
                  {id} <span className="hint">{menu.nodes[id]?.type}{unreachable ? ", unreachable" : ""}</span>
                </button>
              </li>
            ))}
          </ul>
          <div className="node-form" aria-label="Node">
            {node ? (
              <>
                <h3 className="mono">{selected}{selected === menu.start ? " (start)" : ""}</h3>
                <label>Type
                  <select value={node.type} onChange={(e) => setNode(selected, { ...node, type: e.target.value as MenuNode["type"] })}>
                    {NODE_TYPES.map((t) => <option key={t}>{t}</option>)}
                  </select>
                </label>
                {node.type !== "ACTION" ? <TextPair label="Text" value={node.text} onChange={(text) => setNode(selected, { ...node, text })} /> : null}
                {node.type === "MENU" || node.type === "CONFIRM" ? (
                  <fieldset className="options">
                    {(node.options ?? []).map((o, i) => (
                      <div key={i} className="option" aria-label={`Option ${o.key}`}>
                        <label>Key<input value={o.key} size={2} onChange={(e) => setOption(i, { ...o, key: e.target.value })} /></label>
                        <TextPair label={`Option ${o.key} label`} value={o.label} onChange={(label) => setOption(i, { ...o, label })} />
                        <NodeSelect label="Next" value={o.next} ids={ids} onChange={(next) => setOption(i, { ...o, next })} />
                        <button type="button" onClick={() => setNode(selected, { ...node, options: (node.options ?? []).filter((_, j) => j !== i) })}>Remove option</button>
                      </div>
                    ))}
                    <button type="button" onClick={() => setNode(selected, { ...node, options: [...(node.options ?? []), { key: String((node.options?.length ?? 0) + 1), label: { ...EMPTY } }] })}>
                      Add option
                    </button>
                  </fieldset>
                ) : null}
                {node.type === "INPUT" ? (
                  <>
                    <label>Validator
                      <select value={node.validator?.type ?? ""} onChange={(e) => setNode(selected, { ...node, validator: { ...node.validator, type: e.target.value } })}>
                        {["msisdn", "amount", "pin", "numeric", "regex"].map((t) => <option key={t}>{t}</option>)}
                      </select>
                    </label>
                    <label>Store as<input value={node.store ?? ""} onChange={(e) => setNode(selected, { ...node, store: e.target.value })} /></label>
                    <NodeSelect label="Next" value={node.next} ids={ids} onChange={(next) => setNode(selected, { ...node, next })} />
                  </>
                ) : null}
                {node.type === "DYNAMIC" ? (
                  <>
                    <label>Lookup source<input value={node.source ?? ""} onChange={(e) => setNode(selected, { ...node, source: e.target.value })} /></label>
                    <label>Store as<input value={node.store ?? ""} onChange={(e) => setNode(selected, { ...node, store: e.target.value })} /></label>
                    <NodeSelect label="Next" value={node.next} ids={ids} onChange={(next) => setNode(selected, { ...node, next })} />
                  </>
                ) : null}
                {node.type === "ACTION" ? (
                  <>
                    <label>Flow<input value={node.flow ?? ""} onChange={(e) => setNode(selected, { ...node, flow: e.target.value })} /></label>
                    <NodeSelect label="On success" value={node.onSuccess} ids={ids} onChange={(onSuccess) => setNode(selected, { ...node, onSuccess })} />
                    <NodeSelect label="On failure" value={node.onFailure} ids={ids} onChange={(onFailure) => setNode(selected, { ...node, onFailure })} />
                  </>
                ) : null}
                {selected !== menu.start ? <button type="button" onClick={removeNode}>Delete node</button> : null}
              </>
            ) : <p className="hint">Pick a node.</p>}
            <div className="add-node">
              <label>New node id<input value={newId} onChange={(e) => setNewId(e.target.value)} /></label>
              <label>Type<select value={newType} onChange={(e) => setNewType(e.target.value as MenuNode["type"])}>{NODE_TYPES.map((t) => <option key={t}>{t}</option>)}</select></label>
              <button type="button" onClick={addNode}>Add node</button>
            </div>
          </div>
        </div>
        <details>
          <summary>Engine messages (bn and en)</summary>
          {ENGINE_MESSAGES.map((k) => (
            <TextPair key={k} label={k} value={menu.messages[k]} onChange={(t) => setMenu((m) => ({ ...m, messages: { ...m.messages, [k]: t } }))} />
          ))}
        </details>
        <details>
          <summary>JSON</summary>
          <textarea className="json" rows={16} aria-label="Menu JSON" value={json ?? JSON.stringify(menu, null, 2)} onChange={(e) => setJson(e.target.value)} spellCheck={false} />
          <button type="button" onClick={applyJson}>Apply JSON</button>
          {jsonError ? <p className="error" role="alert">{jsonError}</p> : null}
        </details>
        {problems.length > 0 ? (
          <div className="warnings" data-testid="menu-problems">
            <strong>Not ready yet</strong> (mw-admin-api would refuse it):
            <ul>{problems.map((p) => <li key={p}>{p}</li>)}</ul>
          </div>
        ) : <p className="hint" data-testid="menu-problems">No problems found (the API checks again when filed).</p>}
      </section>

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
    <section className="panel" aria-label="Preview">
      <h2>Preview walk</h2>
      <p className="hint">Runs the edited tree as mw-ussd would show it; no transaction is made.</p>
      <div className="search">
        <label>Dialling MSISDN (sample)<input value={msisdn} onChange={(e) => setMsisdn(e.target.value)} /></label>
        <label>Language<select value={lang} onChange={(e) => setLang(e.target.value as Lang)}><option value="bn">bn</option><option value="en">en</option></select></label>
        <label>Action outcome<select value={outcome} onChange={(e) => setOutcome(e.target.value as ActionOutcome)}>
          <option value="COMPLETED">completed</option><option value="FAILED">failed</option><option value="PROCESSING">processing</option>
        </select></label>
        <button type="button" className="primary" onClick={dial}>Dial</button>
      </div>
      {preview ? (
        <>
          <pre className="ussd-screen" lang={preview.lang} data-testid="ussd-screen">{preview.screen}</pre>
          {preview.ended ? <p className="hint" data-testid="ussd-ended">Session ended.</p> : (
            <form className="search" aria-label="Reply to the screen" onSubmit={(e) => { e.preventDefault(); send(); }}>
              <label>Reply<input value={input} onChange={(e) => setInput(e.target.value)} autoComplete="off" /></label>
              <button type="submit">Send</button>
            </form>
          )}
          <ol className="hint trail">{preview.trail.map((s, i) => <li key={i}>{s.input ?? "(dial)"}</li>)}</ol>
        </>
      ) : null}
    </section>
  );
}

function ProposeForm({ menu, action }: { menu: Menu; action: (prev: ActionState, form: FormData) => Promise<ActionState> }) {
  const [state, formAction, pending] = useActionState(action, null);
  return (
    <section className="panel actions" aria-label="Propose menu">
      <h2>Propose this version</h2>
      <p className="hint">Nothing goes live until a different operator (ops-checker) approves it. Go-live times are UTC; empty or past = at approval.</p>
      <form action={formAction} aria-label="Menu proposal" aria-busy={pending}>
        <fieldset disabled={pending}>
          <input type="hidden" name="menu" value={JSON.stringify(menu)} />
          <div className="mnos">
            {MNOS.map((m) => (
              <div key={m} className="mno">
                <label className="inline"><input type="checkbox" name="mno" value={m} defaultChecked /> {m}</label>
                <label>Go-live {m}<input type="datetime-local" name={`goLive.${m}`} /></label>
              </div>
            ))}
          </div>
          <label>Reason<input name="reason" required maxLength={500} /></label>
          <span className="buttons"><button className="primary" type="submit">Request publish</button></span>
        </fieldset>
        {state ? <ApiError status={state.status} error={state.error} /> : null}
      </form>
    </section>
  );
}
