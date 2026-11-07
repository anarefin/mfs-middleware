// Amounts, MSISDNs and CSV files as the test console's forms take them. Pure, no I/O.

/** Taka as typed ("10", "10.5", "10.50") to poisha; null when not a positive amount with at most 2 decimals. */
export function takaToMinor(input: string): number | null {
  const s = input.trim();
  if (!/^\d{1,9}(\.\d{1,2})?$/.test(s)) return null;
  const [whole = "0", frac = ""] = s.split(".");
  const minor = Number(whole) * 100 + Number(frac.padEnd(2, "0"));
  return minor > 0 ? minor : null;
}

/** Poisha as "Tk 1,234.50" (negative with a leading minus). */
export function formatMinor(minor: number): string {
  const sign = minor < 0 ? "-" : "";
  const a = Math.abs(minor);
  const whole = Math.floor(a / 100).toLocaleString("en-US");
  return `${sign}Tk ${whole}.${String(a % 100).padStart(2, "0")}`;
}

/** A Bangladeshi MSISDN in any form (01…, 8801…, +8801…) as 01XXXXXXXXX; null when it is not one. */
export function normaliseMsisdn(input: string): string | null {
  const d = input.replace(/[\s-]/g, "").replace(/^\+/, "");
  const local = d.startsWith("880") ? "0" + d.slice(3) : d;
  return /^01[3-9]\d{8}$/.test(local) ? local : null;
}

/**
 * A bulk file of `rows` payouts of `taka` each to the core mock's seeded customers 01711000101..01711001000
 * in turn: never the test personas customer01 / customer02, whose balances the other tests compare.
 */
export function bulkCsv(rows: number, taka: string, run: string): string {
  let s = "msisdn,amount,reference\n";
  for (let i = 1; i <= rows; i++) {
    s += `0171100${String(((i - 1) % 900) + 101).padStart(4, "0")},${taka},TC-${run}-${i}\n`;
  }
  return s;
}

/** A short unique token for references and order ids. */
export function unique(): string {
  return Date.now().toString(36) + Math.floor(Math.random() * 46656).toString(36);
}

/** Inputs typed on a USSD screen that asks for the PIN are shown as ****. */
export function asksForPin(screen: string): boolean {
  return /\bPIN\b|পিন/i.test(screen);
}
