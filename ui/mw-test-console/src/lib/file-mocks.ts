// The file mocks of mw-simulators (ticket 123; contract contracts/api/mw-simulators/mock-control.v1.openapi.yaml):
// the AML vendor, the trust bank (MT940 / MT942) and the Data Warehouse drop check on partner-sftp, as the
// Files page offers them, and the bodies its forms send to /test/mock-control/*. Pure (Node crypto only).

import { createHash } from "node:crypto";

export type FileParty = "aml" | "trustbank" | "dw";

export const FILE_PARTIES: readonly { name: FileParty; title: string; operations: readonly string[] }[] = [
  { name: "aml", title: "AML vendor (aml/in -> aml/out)", operations: ["respond"] },
  { name: "trustbank", title: "Trust bank (MT940 daily, MT942 intraday, trustbank/out)", operations: ["mt940", "mt942"] },
  { name: "dw", title: "Data Warehouse drop check (dw/in)", operations: ["validate"] },
];

export const FILE_FAULTS = ["MISSING_FILE", "LATE_FILE", "DUPLICATE_FILE", "CORRUPT_FILE", "INJECTED_MISMATCH"] as const;

/** INJECTED_MISMATCH kinds (the scenario's code) per party. */
export const MISMATCH_KINDS: Record<string, readonly string[]> = {
  trustbank: ["MISSING_IN_PARTNER", "MISSING_IN_UPAY", "AMOUNT_MISMATCH", "DC_FLIP"],
  aml: ["UNKNOWN_TXN"],
};

export function isFileParty(v: string): v is FileParty {
  return FILE_PARTIES.some((p) => p.name === v);
}

const text = (v: string | undefined) => (v === undefined || v.trim() === "" ? undefined : v.trim());
const int = (v: string | undefined) => (text(v) === undefined ? undefined : Number(v!.trim()));
const compact = (o: Record<string, unknown>) => Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined));

/** POST /{party}/run: operation, date (yyyy-MM-dd), at (HH:mm); empty = the mock's default. */
export function runBody(f: Record<string, string | undefined>): Record<string, unknown> {
  return compact({ operation: text(f.operation), date: text(f.date), at: text(f.at) });
}

/** A file-fault scenario: the mismatch kind goes as code, count with it. */
export function faultBody(f: Record<string, string | undefined>): Record<string, unknown> {
  return compact({
    operation: text(f.operation),
    behaviour: text(f.behaviour),
    code: text(f.kind),
    count: int(f.count),
    delayMs: int(f.delayMs),
    key: text(f.key),
    nextN: int(f.nextN),
  });
}

/**
 * An AML transaction file in the draft format of contracts/files/aml/README.md: one P2P row per payee MSISDN
 * (payer customer01), each of amountMinor poisha, and the trailer (rows, total, sha256 of everything before it).
 */
export function amlSampleFile(stamp: string, payees: readonly string[], amountMinor: number): string {
  let body = `H|AML_TXN|1|F-${stamp}|2026-11-20T10:00:00+06:00\n`;
  payees.forEach((p, i) => {
    const id = `0196a1b2-0000-7000-8000-${String(i + 1).padStart(12, "0")}`;
    body += `D|${id}|P2P|APP|WALLET:${p}|WALLET:01711000001|${amountMinor}|2026-11-20T09:0${i % 10}:00+06:00\n`;
  });
  const sha = createHash("sha256").update(body, "utf8").digest("hex");
  return `${body}T|${payees.length}|${payees.length * amountMinor}|${sha}\n`;
}
