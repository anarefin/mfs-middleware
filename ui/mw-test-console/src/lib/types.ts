// What a test console action returns to the page: every call it made through the edge, in order,
// so a tester sees each step of a flow (request, HTTP status, answer), plus the facts to check.

export type Step = {
  label: string;
  /** "POST /app/v1/transactions" */
  call?: string;
  status?: number;
  ok: boolean;
  /** the answer (JSON or text), secrets redacted */
  body?: unknown;
  note?: string;
  ms?: number;
};

export type BalanceRow = {
  wallet: string;
  beforeMinor: number | null;
  afterMinor: number | null;
  heldMinor: number | null;
};

export type SmsRow = { destination?: string; source?: string; text?: string; dlrStatus?: string; receivedAt?: string };

export type Outcome = {
  ok: boolean;
  headline: string;
  steps: Step[];
  txnId?: string;
  status?: string;
  balances?: BalanceRow[];
  sms?: SmsRow[];
  /** extra data the page shows (session token, OTP, batch id, screens, …) */
  data?: Record<string, unknown>;
};

export const FINAL = ["COMPLETED", "FAILED", "REJECTED", "REVERSED"] as const;
