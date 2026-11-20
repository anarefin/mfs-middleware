import { expect, request } from "@playwright/test";
import { randomUUID } from "node:crypto";
import { BASE_URL, SEED_PIN, USSD_CODE, USSD_MNO, USSD_SECRET_ENV } from "./env";

/**
 * Makes a real transaction for the console to show: a seeded customer sends money over USSD, the
 * suite playing MNO robi's HTTP gateway through APISIX (`/ussd/robi`, basic-auth `ussd-robi`, the
 * secret from deploy/.env). The English end screen is the one that carries the TxnID. Language is
 * remembered per MSISDN (ticket 109), so option 9 toggles: press it only when the dial-in is Bangla.
 * Unique per call, so runs never collide.
 */
export async function p2pOverUssd(from: string, to: string, amountTk = "10"): Promise<string> {
  const secret = process.env[USSD_SECRET_ENV];
  if (!secret) throw new Error(`${USSD_SECRET_ENV} not set (run through scripts/ui-e2e.sh)`);
  const api = await request.newContext({
    baseURL: BASE_URL,
    ignoreHTTPSErrors: true,
    extraHTTPHeaders: { authorization: `Basic ${Buffer.from(`ussd-${USSD_MNO}:${secret}`).toString("base64")}` },
  });
  try {
    const sessionId = `e2e-ui-${randomUUID()}`;
    const step = async (type: "BEGIN" | "CONTINUE", input: string) => {
      const res = await api.post(`/ussd/${USSD_MNO}`, { data: { sessionId, msisdn: from, serviceCode: USSD_CODE, input, type } });
      const text = await res.text();
      expect(res.status(), `USSD ${type} ${input === SEED_PIN ? "<pin>" : input} -> ${text}`).toBe(200);
      return JSON.parse(text) as { message: string; flag: string };
    };
    const begin = await step("BEGIN", "");
    let last = begin;
    const inputs = ["1", to, amountTk, "1", SEED_PIN];
    if (!begin.message.includes("Send Money")) inputs.unshift("9");
    for (const input of inputs) last = await step("CONTINUE", input);
    const txnId = /TxnID ([0-9a-f-]{36})/.exec(last.message)?.[1];
    if (!txnId) throw new Error(`the P2P did not complete within mw-ussd's sync budget: "${last.message}"`);
    return txnId;
  } finally {
    await api.dispose();
  }
}
