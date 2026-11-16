import "server-only";
// Runtime configuration, read on the server only (never sent to the browser). In the stack the
// values are the fixed dev credentials of deploy/.env (a copy of deploy/dev.env.defaults): fake test
// values on a laptop stack, never production secrets.

/** A value that may be missing (the logins and secrets pages): undefined, never a throw. */
export function optional(name: string): string | undefined {
  const v = process.env[name];
  return v ? v : undefined;
}

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
/** The app PIN of every seeded app wallet at mw-auth (MW_SEED_APP_PIN, ticket 69). */
export const appPin = (): string => required("MW_SEED_APP_PIN");
export const portalsPassword = (): string => required("KC_PORTALS_TEST_PASSWORD");
/** The pre-set TOTP secret of the portals test users (realm portals demands TOTP, ticket 73). */
export const portalsTotpSecret = (): string => required("KC_PORTALS_TEST_TOTP_SECRET");
/** hmac-auth secret of the dev merchant merchant_demo (wallet 01911000001, "Karim Traders"). */
export const checkoutSecret = (): string => required("CHECKOUT_DEMO_MERCHANT_SECRET");
/** mw-seeder's manifest on the read-only seed-manifest volume. */
export const seedManifestPath = (): string => process.env.SEED_MANIFEST || "/run/mw/seed/manifest.json";
/** Keycloak's bootstrap admin (realm master, client admin-cli): the user tools of the seed data page. */
export const kcAdminUser = (): string => optional("KC_BOOTSTRAP_ADMIN_USERNAME") ?? "kc-admin";
export const kcAdminPassword = (): string | undefined => optional("KC_BOOTSTRAP_ADMIN_PASSWORD");
