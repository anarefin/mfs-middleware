"use server";
// The file mocks of mw-simulators (ticket 123: AML vendor, trust bank MT940 / MT942, DW drop check on
// partner-sftp) through the test-only route /test/mock-control/*: run one now, drop a sample AML file,
// add a file fault, read a party's journal (every file read, written or skipped, with its sha256).

import { amlSampleFile, faultBody, isFileParty, runBody } from "@/lib/file-mocks";
import type { Outcome } from "@/lib/types";
import { Trace } from "@/lib/server/edge";

export type FileState = Outcome | null;
const str = (f: FormData, k: string) => String(f.get(k) ?? "").trim();
const done = (t: Trace, headline: string): Outcome => ({ ok: !t.failed, headline, steps: t.steps });
const MC = "/test/mock-control/";

/** Runs a file mock now (trust bank: the MT940 / MT942 of a day; AML and DW: every new inbox file). */
export async function runFileMock(_prev: FileState, form: FormData): Promise<FileState> {
  const t = new Trace();
  const party = str(form, "party");
  if (!isFileParty(party)) return done(t, "choose a file mock");
  const body = runBody({ operation: str(form, "operation"), date: str(form, "date"), at: str(form, "at") });
  await t.call(`run ${party} now`, "POST", `${MC}${party}/run`, { apikey: true, json: body });
  return done(t, t.failed ? `${party}: the run failed (is partner-sftp up? profile tester)` : `${party}: ran; the files are in the step's answer`);
}

/** Drops a sample AML transaction file (watch-listed, 6666 and a clean payer) and runs the AML mock. */
export async function amlSample(_prev: FileState, form: FormData): Promise<FileState> {
  const t = new Trace();
  const stamp = new Date().toISOString().replace(/\D/g, "").slice(0, 14);
  const amount = Number(str(form, "amountMinor") || "1000");
  const content = amlSampleFile(stamp, ["01711000777", "01811116666", "01711000002"], amount);
  await t.call("upload a transaction file to aml/in", "POST", `${MC}aml/files`,
    { apikey: true, json: { name: `AML_TXN_${stamp}.txt`, content } }, [201]);
  if (!t.failed) await t.call("the AML mock answers it", "POST", `${MC}aml/run`, { apikey: true, json: {} });
  return done(t, t.failed ? "AML sample: a call failed" : "AML sample: answered (HOLD for 01711000777, BLOCK for 01811116666)");
}

/** Adds a file fault (missing, late, duplicate, corrupt, injected mismatch) to the next N files or a date. */
export async function addFileFault(_prev: FileState, form: FormData): Promise<FileState> {
  const t = new Trace();
  const party = str(form, "party");
  if (!isFileParty(party)) return done(t, "choose a file mock");
  const body = faultBody(Object.fromEntries(["operation", "behaviour", "kind", "count", "delayMs", "key", "nextN"].map((k) => [k, str(form, k)])));
  await t.call(`add ${String(body.behaviour)} to ${party}`, "POST", `${MC}${party}/scenarios`, { apikey: true, json: body }, [201]);
  return done(t, t.failed ? `${party}: fault refused (see the answer)` : `${party}: fault added; run the mock to see it`);
}

/** A file party's journal (optionally only paths containing a text), or its reset. */
export async function fileJournal(_prev: FileState, form: FormData): Promise<FileState> {
  const t = new Trace();
  const party = str(form, "party");
  if (!isFileParty(party)) return done(t, "choose a file mock");
  if (str(form, "op") === "reset") {
    await t.call(`reset ${party} (journal, faults, files it remembers)`, "DELETE", `${MC}${party}/journal`, { apikey: true }, [204]);
    return done(t, `${party}: reset`);
  }
  const path = str(form, "path");
  await t.call(`${party}'s journal`, "GET", `${MC}${party}/journal${path ? `?path=${encodeURIComponent(path)}` : ""}`, { apikey: true });
  return done(t, `${party}: journal read`);
}
