// The deploy/.env variables the logins and secrets pages may show (fixed fake dev values, ADR D9
// amendment; the console runs only in the tester profile). The server resolves a value only for a
// name on this list, so a manifest entry can never make it print some other variable. Pure data.

export type SecretEntry = { env: string; label: string; hint?: string };
export type SecretGroup = { title: string; entries: SecretEntry[] };

export const PLATFORM_SECRETS: readonly SecretGroup[] = [
  {
    title: "Keycloak admin (realm master, client admin-cli)",
    entries: [
      { env: "KC_BOOTSTRAP_ADMIN_USERNAME", label: "Username", hint: "kc-admin when not set" },
      { env: "KC_BOOTSTRAP_ADMIN_PASSWORD", label: "Password" },
    ],
  },
  { title: "Vault", entries: [{ env: "VAULT_DEV_ROOT_TOKEN", label: "Root token", hint: "internal only: docker compose exec vault ..." }] },
  {
    title: "APISIX",
    entries: [
      { env: "MW_E2E_API_KEY", label: "Test routes key", hint: "header apikey (consumer mw_e2e, /test/*)" },
      { env: "APISIX_ADMIN_KEY", label: "Admin API key", hint: "header X-API-KEY, 127.0.0.1:9180 inside the apisix container" },
    ],
  },
  {
    title: "Partner and merchant signing secrets",
    entries: [
      { env: "CHECKOUT_DEMO_MERCHANT_SECRET", label: "Checkout merchant merchant_demo (hmac-auth)" },
      { env: "BILLAGG_HMAC_SECRET", label: "Bill aggregator callbacks (HMAC-SHA256)" },
      { env: "C2WBANK_HMAC_SECRET", label: "Bank C2W callbacks (HMAC-SHA256)" },
      { env: "UCB_IMT_HMAC_SECRET", label: "Remittance partner ucb-imt requests (HMAC-SHA256)" },
    ],
  },
  {
    title: "USSD gateways (basic auth, user ussd-<mno>, POST /ussd/<mno>)",
    entries: [
      { env: "USSD_GP_GATEWAY_SECRET", label: "gp" },
      { env: "USSD_ROBI_GATEWAY_SECRET", label: "robi" },
      { env: "USSD_BL_GATEWAY_SECRET", label: "bl" },
      { env: "USSD_TELETALK_GATEWAY_SECRET", label: "teletalk" },
    ],
  },
  {
    title: "SoftHSM PKCS#11 tokens (deploy/softhsm)",
    entries: [
      { env: "SOFTHSM_MWCRYPTO_USER_PIN", label: "mw-crypto user PIN", hint: "port 5657" },
      { env: "SOFTHSM_MWCRYPTO_SO_PIN", label: "mw-crypto SO PIN" },
      { env: "SOFTHSM_PAYSIM_USER_PIN", label: "paysim user PIN", hint: "port 5658" },
      { env: "SOFTHSM_PAYSIM_SO_PIN", label: "paysim SO PIN" },
    ],
  },
];

/** The login secrets a manifest persona may name (passwordEnv, totpSecretEnv, appPins.pinEnv). */
export const LOGIN_SECRET_ENVS: readonly string[] = [
  "KC_OPS_TEST_PASSWORD", "KC_OPS_TEST_TOTP_SECRET", "KC_PORTALS_TEST_PASSWORD", "KC_PORTALS_TEST_TOTP_SECRET", "MW_SEED_APP_PIN",
];

export const SHOWABLE_ENVS: ReadonlySet<string> = new Set([...LOGIN_SECRET_ENVS, ...PLATFORM_SECRETS.flatMap((g) => g.entries.map((e) => e.env))]);

/** The wrong PIN the API suite and the guide use (any other wrong 5-digit PIN works as well). */
export const WRONG_PIN_MAGIC = "9999";
