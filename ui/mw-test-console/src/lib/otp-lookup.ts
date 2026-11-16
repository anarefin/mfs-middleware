// The latest one-time code per purpose in the SMS the SMSC simulator received for one MSISDN
// (mw-notify's texts, lib/auth.ts OTP_TEXT). Pure: the server action reads the SMS.

import { OTP_TEXT, type OtpPurpose } from "./auth";
import type { SmsRow } from "./types";

export type LatestOtp = { purpose: OtpPurpose; code: string | null; receivedAt: string | null; ageS: number | null };

const PURPOSES: readonly OtpPurpose[] = ["BIND", "LOGIN", "RESET"];

export function latestOtps(sms: readonly SmsRow[], now: number = Date.now()): LatestOtp[] {
  return PURPOSES.map((purpose) => {
    let best: { code: string; at: number; receivedAt: string | null; i: number } | null = null;
    sms.forEach((m, i) => {
      const hit = OTP_TEXT[purpose].exec(m.text ?? "");
      if (!hit?.[1]) return;
      const t = m.receivedAt ? Date.parse(m.receivedAt) : Number.NaN;
      const at = Number.isNaN(t) ? Number.NEGATIVE_INFINITY : t;
      // newest time wins; without times (or on a tie) the later row, as the simulator lists in arrival order
      if (!best || at > best.at || (at === best.at && i > best.i)) best = { code: hit[1], at, receivedAt: m.receivedAt ?? null, i };
    });
    const b = best as { code: string; at: number; receivedAt: string | null } | null;
    if (!b) return { purpose, code: null, receivedAt: null, ageS: null };
    return { purpose, code: b.code, receivedAt: b.receivedAt, ageS: Number.isFinite(b.at) ? Math.max(0, Math.round((now - b.at) / 1000)) : null };
  });
}
