// The mock parties of mw-simulators' mock-control API (ADR D33, tickets 102a/102b; contract
// contracts/api/mw-simulators/mock-control.v1.openapi.yaml), as the Simulators page offers them, and the
// request bodies its forms send to /test/mock-control/*.

export type MockPartyName = "ocs" | "billagg" | "c2wbank" | "merchant" | "smsc" | "ussdgw" | "payshield";

export type MockPartyInfo = {
  name: MockPartyName;
  title: string;
  operations: readonly string[];
  /** what a BUSINESS_ERROR code looks like for this party */
  codeHint: string;
  /** the party sends callbacks to a URL (replay works) */
  callbacks?: string;
};

export const MOCK_PARTIES: readonly MockPartyInfo[] = [
  { name: "ocs", title: "OCS (prepaid top-up, every MNO)", operations: ["topup", "status", "reverse"], codeHint: "e.g. 2001, 9876" },
  {
    name: "billagg", title: "Bill aggregator (10 billers, REST + SOAP)", operations: ["billers", "fetch", "pay", "status", "reverse"],
    codeHint: "e.g. 3003", callbacks: "http://apisix:9080/partner/billagg/callback/payments",
  },
  {
    name: "c2wbank", title: "C2W bank (add money from bank)", operations: ["debit", "status", "reverse"], codeHint: "e.g. 4002",
    callbacks: "http://apisix:9080/partner/c2wbank/callback/debits",
  },
  { name: "merchant", title: "Checkout merchant IPN receiver", operations: ["ipn"], codeHint: "use httpStatus" },
  { name: "smsc", title: "SMSC (SMPP, mw-notify; DLR = callback)", operations: ["bind", "submit"], codeHint: "SMPP status, e.g. 0x58 throttled" },
  { name: "ussdgw", title: "USSD gateway (SMPP binds of mw-ussd, dial)", operations: ["bind", "submit", "enquire_link", "dial"], codeHint: "SMPP status, e.g. 0x58" },
  { name: "payshield", title: "payShield HSM (mw-crypto)", operations: ["command"], codeHint: "payShield error, e.g. 68" },
];

export const PARTY_NAMES: readonly MockPartyName[] = MOCK_PARTIES.map((p) => p.name);

export const BEHAVIOURS = [
  "SUCCESS", "BUSINESS_ERROR", "TIMEOUT", "DELAY", "HTTP_5XX", "MALFORMED_RESPONSE", "CONNECTION_RESET",
  "PARTIAL_SUCCESS", "DUPLICATE_CALLBACK", "LATE_CALLBACK", "OUT_OF_ORDER_CALLBACK", "NO_CALLBACK",
] as const;

export const SWITCH_STATES = ["UP", "DOWN", "MAINTENANCE", "SLOW"] as const;

export function isParty(v: string): v is MockPartyName {
  return (PARTY_NAMES as readonly string[]).includes(v);
}

const int = (v: string | undefined) => (v === undefined || v.trim() === "" ? undefined : Number(v.trim()));
const text = (v: string | undefined) => (v === undefined || v.trim() === "" ? undefined : v.trim());

/** A scenario body from the form's text values; empty fields are left out (the engine's defaults). */
export function scenarioBody(f: Record<string, string | undefined>): Record<string, unknown> {
  const body: Record<string, unknown> = {
    operation: text(f.operation),
    behaviour: text(f.behaviour),
    code: text(f.code),
    delayMs: int(f.delayMs),
    httpStatus: int(f.httpStatus),
    key: text(f.key),
    nextN: int(f.nextN),
  };
  return Object.fromEntries(Object.entries(body).filter(([, v]) => v !== undefined));
}

/** A switch body: the latency range defaults to one value when only the minimum is given. */
export function switchBody(f: Record<string, string | undefined>): Record<string, unknown> {
  const min = int(f.latencyMinMs);
  const body: Record<string, unknown> = {
    state: text(f.state) ?? "UP",
    latencyMinMs: min,
    latencyMaxMs: int(f.latencyMaxMs) ?? min,
    ratio: int(f.ratio),
  };
  return Object.fromEntries(Object.entries(body).filter(([, v]) => v !== undefined));
}

/** The calls that put a party back to normal: no scenario, switch UP, default settings (journal and ledgers kept). */
export function normalCalls(party: MockPartyName): { method: "DELETE" | "PUT"; path: string; json?: unknown }[] {
  return [
    { method: "DELETE", path: `/test/mock-control/${party}/scenarios` },
    { method: "PUT", path: `/test/mock-control/${party}/switch`, json: { state: "UP" } },
    { method: "PUT", path: `/test/mock-control/${party}/settings`, json: {} },
  ];
}
