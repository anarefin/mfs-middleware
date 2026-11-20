// @vitest-environment happy-dom
// The visual USSD tree editor (ticket 133a): add a child, move a node, delete it with its links,
// the bn / en character counters, and the paged preview on an MNO. Fake texts only.
import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import type { Menu } from "@/lib/ussd-menu";
import { MenuEditor } from "./menu-editor";

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const t = (bn: string, en: string) => ({ bn, en });
const MENU: Menu = {
  start: "main",
  messages: {
    invalid: t("ভুল", "Invalid"), tooManyAttempts: t("অনেকবার", "Too many"), expired: t("শেষ", "Expired"),
    processing: t("চলছে", "Processing"), unavailable: t("বন্ধ", "Unavailable"), more: t("0. পরবর্তী", "0. Next"),
  },
  nodes: {
    main: { type: "MENU", text: t("বিটস", "Bits"), options: [{ key: "1", label: t("ব্যালেন্স", "Balance"), next: "sub" }] },
    sub: { type: "MENU", text: t("উপ", "Sub"), options: [{ key: "1", label: t("শেষ", "Done"), next: "done" }] },
    done: { type: "END", text: t("ধন্যবাদ", "Thanks") },
  },
};

let container: HTMLDivElement;
let root: Root;

beforeEach(async () => {
  container = document.createElement("div");
  document.body.appendChild(container);
  root = createRoot(container);
  await act(async () => root.render(<MenuEditor initial={MENU} baseLabel="v1 (PUBLISHED)" action={null} />));
});

afterEach(() => {
  act(() => root.unmount());
  container.remove();
});

function el<T extends Element>(selector: string): T {
  const e = container.querySelector<T>(selector);
  if (!e) throw new Error(`no ${selector}`);
  return e;
}

function button(name: string, scope: ParentNode = container): HTMLButtonElement {
  const b = [...scope.querySelectorAll("button")].find((x) => x.textContent?.trim() === name);
  if (!b) throw new Error(`no button ${name}`);
  return b;
}

/** Sets a controlled input / select the way a user does (React listens to input / change). */
function type(e: HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement, value: string) {
  const proto = Object.getPrototypeOf(e) as object;
  Object.getOwnPropertyDescriptor(proto, "value")!.set!.call(e, value);
  e.dispatchEvent(new Event(e instanceof HTMLSelectElement ? "change" : "input", { bubbles: true }));
}

const click = (b: HTMLElement) => act(() => b.click());
const treeIds = () => [...el("[data-testid=menu-tree]").querySelectorAll("button")].map((b) => b.querySelector("span")?.textContent);
const menuJson = (): Menu => JSON.parse(el<HTMLTextAreaElement>("textarea[aria-label='Menu JSON']").value) as Menu;
const select = (id: string) => click([...el("[data-testid=menu-tree]").querySelectorAll("button")].find((b) => b.querySelector("span")?.textContent === id)!);

describe("USSD tree editor", () => {
  it("adds a child under the selected MENU as a new option", async () => {
    await act(async () => type(el<HTMLInputElement>("input[aria-label='New child id']"), "balance"));
    await act(async () => type(el<HTMLSelectElement>("select[aria-label='New child type']"), "END"));
    await click(button("Add child"));
    expect(treeIds()).toEqual(["main", "sub", "done", "balance"]);
    expect(menuJson().nodes.main!.options!.map((o) => o.next)).toEqual(["sub", "balance"]);
  });

  it("moves a node under another parent", async () => {
    await select("done");
    await act(async () => type(el<HTMLSelectElement>("select[aria-label='Move under']"), "main"));
    await click(button("Move"));
    const m = menuJson();
    expect(m.nodes.sub!.options).toEqual([]);
    expect(m.nodes.main!.options!.map((o) => o.next)).toEqual(["sub", "done"]);
    expect(treeIds()).toEqual(["main", "sub", "done"]);
  });

  it("deletes a node and the options that led to it", async () => {
    await select("sub");
    await click(button("Delete node"));
    expect(treeIds()).toEqual(["main", "done"]);
    expect(menuJson().nodes.main!.options).toEqual([]);
  });

  it("counts bn and en characters per text against the UCS-2 / GSM-7 limits", async () => {
    const counters = () => [...container.querySelectorAll("[data-testid=char-count]")].map((c) => c.textContent);
    expect(counters().slice(0, 2)).toEqual(["4/70 UCS-2", "4/182 GSM-7"]);
    await act(async () => type(el<HTMLTextAreaElement>("textarea[lang=bn]"), "ক".repeat(71)));
    const bn = container.querySelector("[data-testid=char-count]")!;
    expect(bn.textContent).toBe("71/70 UCS-2");
    expect(bn.getAttribute("data-over")).toBe("true");
    expect(el("[data-testid=node-screen]").textContent).toContain("2 pages");
  });

  it("previews on the picked MNO, paging a long Bangla screen with 0 / 00", async () => {
    await act(async () => type(el<HTMLTextAreaElement>("textarea[lang=bn]"), "ক".repeat(80)));
    await act(async () => type(el<HTMLSelectElement>("select[aria-label='MNO']"), "robi"));
    expect(container.textContent).toContain("*269#");
    await click(button("Dial"));
    expect(el("[data-testid=ussd-page]").textContent).toBe("page 1/2");
    expect(el("[data-testid=ussd-screen]").textContent).toMatch(/00\. পেছনে$/);
    await act(async () => type(el<HTMLInputElement>("form[aria-label='Reply to the screen'] input"), "0"));
    await act(async () => el<HTMLFormElement>("form[aria-label='Reply to the screen']").requestSubmit());
    expect(el("[data-testid=ussd-page]").textContent).toBe("page 2/2");
  });
});
