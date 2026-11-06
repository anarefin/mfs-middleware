import { expect, request } from "@playwright/test";
import { randomUUID } from "node:crypto";
import { BASE_URL, SEED_PIN, USSD_MNO, USSD_SECRET_ENV } from "./env";

/**
 * Makes a real transaction for the console to show: a seeded customer sends money over USSD, the
 * suite playing MNO robi's HTTP gateway through APISIX (`/ussd/robi`, basic-auth `ussd-robi`, the
 * secret from deploy/.env). English (option 9, per session only) so the end screen carries the TxnID.
 * Unique per call, so runs never collide.
 */
export async function p2pOverUssd(from: string, to: string, amountTk = "10"): Promise<string> {
  const secret = process.env[USSD_SECRET_ENV];
  if (!secret) throw new Error(`${USSD_SECRET_ENV} not set (run through make ui-e2e)`);
  const api = await request.newContext({
    baseURL: BASE_URL,
    ignoreHTTPSErrors: true,
    extraHTTPHeaders: { authorization: `Basic ${Buffer.from(`ussd-${USSD_MNO}:${secret}`).toString("base64")}` },
  });
  try {
    const sessionId = `e2e-ui-${randomUUID()}`;
    const step = async (type: "BEGIN" | "CONTINUE", input: string) => {
      const res = await api.post(`/ussd/${USSD_MNO}`, { data: { sessionId, msisdn: from, serviceCode: "*268#", input, type } });
      const text = await res.text();
      expect(res.status(), `USSD ${type} ${input === SEED_PIN ? "<pin>" : input} -> ${text}`).toBe(200);
      return JSON.parse(text) as { message: string; flag: string };
    };
    await step("BEGIN", "");
    let last = { message: "", flag: "" };
    for (const input of ["9", "1", to, amountTk, "1", SEED_PIN]) last = await step("CONTINUE", input);
    const txnId = /TxnID ([0-9a-f-]{36})/.exec(last.message)?.[1];
    if (!txnId) throw new Error(`the P2P did not complete within mw-ussd's sync budget: "${last.message}"`);
    return txnId;
  } finally {
    await api.dispose();
  }
}
