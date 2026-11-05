// Notification templates as the console edits them (spec 10.3, ticket 42). The checks are a copy
// of mw-admin-api's TemplateValidator (the API decides, 400 MW-VAL-040); the preview renders as
// mw-notify does: logic-less `{{name}}`, params masked by name first (ParamMasker + MaskingPolicy
// of mw-notify, spec 18.3), so the preview never shows a whole MSISDN, account, NID or name.

export type Channel = "SMS" | "EMAIL";
export type Locale = "bn" | "en";
export type TemplateKey = { code: string; channel: Channel; locale: Locale };
export type TemplateTexts = { body?: string; subject?: string; htmlBody?: string; senderId?: string };

const CODE = /^[A-Z][A-Z0-9_]{1,63}$/;
const PLACEHOLDER = /\{\{(.*?)\}\}/g;
const NAME = /^[A-Za-z][A-Za-z0-9_]{0,63}$/;
const SENDER = /^[A-Za-z0-9 ]{1,11}$/;
const STARS = "****";

/** Template codes mw-notify seeds (suggestions only; any code matching the contract is accepted). */
export const KNOWN_CODES = [
  "CHECKOUT_OTP", "DEVICE_BIND_OTP", "DEVICE_BOUND",
  "TXN_ADD_MONEY_FAILED", "TXN_ADD_MONEY_PROCESSING", "TXN_ADD_MONEY_SUCCESS",
  "TXN_BILL_PAY_FAILED", "TXN_BILL_PAY_PROCESSING", "TXN_BILL_PAY_SUCCESS",
  "TXN_CASH_IN_FAILED", "TXN_CASH_IN_PROCESSING", "TXN_CASH_IN_RECEIVED", "TXN_CASH_IN_SENT",
  "TXN_CASH_OUT_FAILED", "TXN_CASH_OUT_PROCESSING", "TXN_CASH_OUT_RECEIVED", "TXN_CASH_OUT_SENT",
  "TXN_TOPUP_FAILED", "TXN_TOPUP_PROCESSING", "TXN_TOPUP_RECEIVED", "TXN_TOPUP_SUCCESS",
] as const;

export function parseTemplateKey(code: unknown, channel: unknown, locale: unknown): TemplateKey | null {
  if (typeof code !== "string" || !CODE.test(code)) return null;
  if (channel !== "SMS" && channel !== "EMAIL") return null;
  if (locale !== "bn" && locale !== "en") return null;
  return { code, channel, locale };
}

type Sensitive = "MSISDN" | "ACCOUNT" | "NID" | "NAME" | "SECRET";

// mw-notify ParamMasker.DEFAULTS (param name, case-insensitive)
const POLICY: Record<string, Sensitive> = {
  msisdn: "MSISDN", receiver: "MSISDN", sender: "MSISDN", counterparty: "MSISDN", agent: "MSISDN",
  account: "ACCOUNT", accountnumber: "ACCOUNT", nid: "NID",
  name: "NAME", receivername: "NAME", sendername: "NAME", pin: "SECRET",
};

function mask(type: Sensitive, v: string): string {
  switch (type) {
    case "MSISDN": return v.length < 8 ? STARS : `${v.slice(0, v.length - 7)}${STARS}${v.slice(-3)}`;
    case "NID": return v.length <= 3 ? STARS : `${STARS}${v.slice(-3)}`;
    case "ACCOUNT": return v.length <= 4 ? STARS : `${STARS}${v.slice(-4)}`;
    case "NAME": {
      const t = v.trim();
      return t ? `${t[0]!.toUpperCase()}.` : STARS;
    }
    case "SECRET": return STARS;
  }
}

/** One param as mw-notify renders it: masked by name, a value already masked kept. */
export function maskParam(name: string, value: string): string {
  const type = POLICY[name.toLowerCase()];
  if (!type || !value || value.includes(STARS)) return value;
  return mask(type, value);
}

/** The plain `{{name}}` placeholders of a text, each once, in order. */
export function placeholderNames(text: string): string[] {
  const out: string[] = [];
  for (const m of text.matchAll(PLACEHOLDER)) {
    const n = m[1]!;
    if (NAME.test(n) && !out.includes(n)) out.push(n);
  }
  return out;
}

// fake sample values (never real data); personal ones are masked by the preview like any param
const SAMPLES: Record<string, string> = {
  amount: "1500.00", fee: "0.00", balance: "8250.75", txnid: "0193a1b2-0000-7000-8000-000000000001",
  txntype: "P2P", otp: "123456", counterparty: "01812345321", msisdn: "01712345678", receiver: "01812345321",
  sender: "01712345678", agent: "01912345111", account: "1234567890123", accountnumber: "1234567890123",
  nid: "19901234567890123", name: "Rahim Uddin", receivername: "Karim Mia", sendername: "Rahim Uddin",
  coreref: "CORE-77001", partnerref: "GP-555001", partner: "ocs", biller: "DESCO", errorcode: "MW-CORE-INSUFFICIENT_FUNDS",
  merchant: "Demo Shop",
};

/** Sample values for the given params (an unknown name gets `<name>`). */
export function sampleParams(names: readonly string[]): Record<string, string> {
  return Object.fromEntries(names.map((n) => [n, SAMPLES[n.toLowerCase()] ?? `<${n}>`]));
}

/** The text as a customer would read it: params masked by name; a missing one stays and is listed. */
export function renderPreview(text: string, params: Record<string, string>): { text: string; missing: string[] } {
  const missing: string[] = [];
  const out = text.replace(PLACEHOLDER, (whole, name: string) => {
    if (!NAME.test(name)) return whole;
    const v = params[name];
    if (v === undefined || v === "") {
      if (!missing.includes(name)) missing.push(name);
      return whole;
    }
    return maskParam(name, v);
  });
  return { text: out, missing };
}

function placeholderProblems(field: string, text: string, p: string[]) {
  let rest = "";
  let last = 0;
  for (const m of text.matchAll(PLACEHOLDER)) {
    if (!NAME.test(m[1]!)) p.push(`${field}: placeholder ${m[0]} is not a plain {{name}}`);
    rest += text.slice(last, m.index);
    last = m.index! + m[0].length;
  }
  rest += text.slice(last);
  if (rest.includes("{{") || rest.includes("}}")) p.push(`${field}: unbalanced {{ or }}`);
}

/** mw-admin-api TemplateValidator.problems: empty when the API would take it. */
export function templateProblems(channel: Channel, t: TemplateTexts): string[] {
  const p: string[] = [];
  if (!t.body || !t.body.trim()) p.push("body is required");
  else placeholderProblems("body", t.body, p);
  if (channel === "EMAIL") {
    if (!t.subject || !t.subject.trim()) p.push("subject is required for EMAIL");
    else placeholderProblems("subject", t.subject, p);
    if (t.htmlBody !== undefined) placeholderProblems("htmlBody", t.htmlBody, p);
  } else {
    if (t.subject !== undefined) p.push("subject is for EMAIL only");
    if (t.htmlBody !== undefined) p.push("htmlBody is for EMAIL only");
  }
  if (t.senderId !== undefined && !SENDER.test(t.senderId)) p.push("senderId must be 1-11 letters, digits or spaces");
  return p;
}

// GSM 03.38 default alphabet (the extension table counted as one character here; preview only)
const GSM7 = new Set(
  "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà^{}\\[~]|€",
);

/** Size of an SMS text: Bangla needs UCS-2 (70 per message, 67 per part), else GSM-7 (160 / 153). */
export function smsInfo(text: string): { encoding: "GSM-7" | "UCS-2"; length: number; parts: number } {
  const chars = [...text];
  const gsm = chars.every((c) => GSM7.has(c));
  const length = gsm ? chars.length : text.length;
  const [single, multi] = gsm ? [160, 153] : [70, 67];
  const parts = length <= single ? 1 : Math.ceil(length / multi);
  return { encoding: gsm ? "GSM-7" : "UCS-2", length, parts };
}
