import { test } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

/**
 * Ids one spec hands to a later one, and the per-run login bookkeeping of session.ts. Written to
 * e2e/.state/run.json (gitignored); global setup resets it at the start of every run.
 */
export interface RunState {
  /** a COMPLETED P2P made over USSD by 10-console (seeded customer A -> B) */
  TXN_P2P?: string;
  /** the last TOTP step used per Keycloak username: realm ops refuses a code used twice */
  totpSteps?: Record<string, number>;
}

export const STATE_DIR = fileURLToPath(new URL("../.state/", import.meta.url));
const FILE = path.join(STATE_DIR, "run.json");

export function resetState() {
  fs.rmSync(STATE_DIR, { recursive: true, force: true });
  fs.mkdirSync(STATE_DIR, { recursive: true });
  fs.writeFileSync(FILE, "{}\n");
}

export function readState(): RunState {
  try {
    return JSON.parse(fs.readFileSync(FILE, "utf8")) as RunState;
  } catch {
    return {};
  }
}

export function saveState(patch: RunState) {
  fs.mkdirSync(STATE_DIR, { recursive: true });
  fs.writeFileSync(FILE, JSON.stringify({ ...readState(), ...patch }, null, 2) + "\n");
}

/**
 * A precondition an earlier spec should have left behind. In a full run it is a failure, so a broken
 * upstream test cannot hide as a skip downstream; running one spec on its own, E2E_ALLOW_SKIP=1 skips.
 */
export function need(missing: boolean, why: string) {
  if (!missing) return;
  if (process.env.E2E_ALLOW_SKIP === "1") test.skip(true, why);
  else throw new Error(`${why} (set E2E_ALLOW_SKIP=1 to skip when running this spec on its own)`);
}
