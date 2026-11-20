import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { scenarios } from "./app-flow-options";
import { FILL_FIELDS, HOW_TO, PAGE_KEYS, type PageKey } from "./how-to";
import { APP_PERSONAS, FLOWS_BY_ROLE, TXN_TYPES } from "./personas";

/** The client components that hold each page's form fields (credentials / seed data have no fills). */
const SOURCES: Record<PageKey, string[]> = {
  home: [],
  app: ["app/app-tester.tsx", "app/biller-picker.tsx"],
  auth: ["auth/auth-tester.tsx"],
  ussd: ["ussd/phone.tsx"],
  simulators: ["simulators/forms.tsx"],
  files: ["files/forms.tsx"],
  inbox: ["inbox/inbox.tsx"],
  checkout: ["checkout/checkout-tester.tsx"],
  bulk: ["bulk/bulk-tester.tsx"],
  transactions: ["transactions/explorer.tsx"],
  preferences: ["preferences/preferences.tsx"],
  credentials: [],
  "seed-data": [],
};

const appDir = fileURLToPath(new URL("../app", import.meta.url));
const source = (k: PageKey) => SOURCES[k].map((f) => readFileSync(join(appDir, f), "utf8")).join("\n");

describe("how-to content", () => {
  it("has an entry with a goal, steps and expectations for every page", () => {
    expect(Object.keys(HOW_TO).sort()).toEqual([...PAGE_KEYS].sort());
    for (const k of PAGE_KEYS) {
      expect(HOW_TO[k].goal, k).not.toBe("");
      expect(HOW_TO[k].steps.length, k).toBeGreaterThan(0);
      expect(HOW_TO[k].expect.length, k).toBeGreaterThan(0);
    }
  });

  it("fills only declared fields, with unique labels", () => {
    for (const k of PAGE_KEYS) {
      const fills = HOW_TO[k].fills ?? [];
      expect(new Set(fills.map((f) => f.label)).size, k).toBe(fills.length);
      for (const f of fills) {
        expect(Object.keys(f.values).length, `${k}: ${f.label}`).toBeGreaterThan(0);
        for (const field of Object.keys(f.values)) expect(FILL_FIELDS[k], `${k}: ${f.label}`).toContain(field);
      }
    }
  });

  it("every declared field exists on its page as name= or data-fill= (and its scope as a data-testid)", () => {
    for (const k of PAGE_KEYS) {
      const src = source(k);
      for (const key of FILL_FIELDS[k]) {
        const [scope, field] = key.includes(":") ? key.split(":") : [undefined, key];
        expect(src.includes(`name="${field}"`) || src.includes(`data-fill="${field}"`), `${k}: ${key}`).toBe(true);
        if (scope) {
          const prefix = scope.slice(0, scope.lastIndexOf("-") + 1);
          expect(src.includes(`testid="${scope}"`) || src.includes(`testid={\`${prefix}\${`), `${k}: scope ${scope}`).toBe(true);
        }
      }
    }
  });

  it("every page with a form has a page file that mounts its panel", () => {
    const pages = readdirSync(appDir, { withFileTypes: true }).filter((d) => d.isDirectory() && SOURCES[d.name as PageKey]?.length);
    for (const d of pages) expect(readFileSync(join(appDir, d.name, "page.tsx"), "utf8"), d.name).toContain(`<HowToTest page="${d.name}"`);
    expect(readFileSync(join(appDir, "page.tsx"), "utf8")).toContain('<HowToTest page="home"');
  });

  it("App flows' fills pick the category, then a wallet of it, then one of its flows", () => {
    for (const f of HOW_TO.app.fills ?? []) {
      const keys = Object.keys(f.values);
      expect(keys.slice(0, 3), f.label).toEqual(["category", "user", "txnType"]);
      const p = APP_PERSONAS.find((x) => x.user === f.values.user);
      expect(p?.role, f.label).toBe(f.values.category);
      expect(FLOWS_BY_ROLE[p!.role], f.label).toContain(f.values.txnType);
    }
  });

  it("App flows' scenario chips fill only declared fields", () => {
    for (const t of TXN_TYPES) for (const c of scenarios(t, "01711000002")) for (const k of Object.keys(c.values)) expect(FILL_FIELDS.app, `${t}: ${c.label}`).toContain(k);
  });
});
