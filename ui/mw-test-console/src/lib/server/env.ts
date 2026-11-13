import "server-only";
// Runtime configuration, read on the server only (never sent to the browser). In the stack the
// values are the fixed dev credentials of deploy/.env (a copy of deploy/dev.env.defaults): fake test
// values on a laptop stack, never production secrets.

function required(name: string): string {
  const v = process.env[name];
  if (!v) throw new Error(`${name} is not set (the test console reads it from deploy/.env: scripts/tester.sh)`);
  return v;
}

/** APISIX as every client reaches it; in the stack http://apisix:9080, with pnpm dev https://localhost. */
export const edgeUrl = (): string => (process.env.EDGE_URL || "http://apisix:9080").replace(/\/+$/, "");
/** The public origin a browser opens (links to the admin console). */
export const publicUrl = (): string => (process.env.PUBLIC_URL || "https://localhost").replace(/\/+$/, "");
/** key-auth of the test-only routes (/test/*), consumer mw_e2e. */
export const e2eApiKey = (): string => required("MW_E2E_API_KEY");
export const customersPassword = (): string => required("KC_CUSTOMERS_TEST_PASSWORD");
export const portalsPassword = (): string => required("KC_PORTALS_TEST_PASSWORD");
/** hmac-auth secret of the dev merchant merchant-demo (wallet 01911000001, "Demo Shop"). */
export const checkoutSecret = (): string => required("CHECKOUT_DEMO_MERCHANT_SECRET");
