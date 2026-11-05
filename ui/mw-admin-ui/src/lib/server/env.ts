// Runtime configuration (read on the server at request time, never baked into the build or sent
// to the browser). Secrets come from the environment (in the stack: generated dev values, OpenBao
// in later environments); none is in the repo.

/** The console is served under this path (APISIX routes `/console/*` to it); also next.config.ts. */
export const BASE_PATH = "/console";

export type AuthEnv = {
  /** encrypts the session cookie (JWE) */
  secret: string;
  /** public issuer of realm ops, e.g. https://localhost/realms/ops (what the browser reaches) */
  issuer: string;
  /** Keycloak inside the stack, e.g. http://keycloak:8080; unset = call the issuer directly */
  internalUrl?: string;
  clientId: string;
  clientSecret?: string;
  authBasePath: string;
};

function required(name: string): string {
  const v = process.env[name];
  if (!v) throw new Error(`${name} is not set`);
  return v;
}

export function authEnv(): AuthEnv {
  return {
    secret: required("AUTH_SECRET"),
    issuer: required("AUTH_KEYCLOAK_ISSUER").replace(/\/+$/, ""),
    internalUrl: process.env.AUTH_KEYCLOAK_INTERNAL_URL || undefined,
    clientId: process.env.AUTH_KEYCLOAK_ID || "mw-admin-ui",
    clientSecret: process.env.AUTH_KEYCLOAK_SECRET || undefined,
    authBasePath: `${BASE_PATH}/api/auth`,
  };
}

/** mw-admin-api through APISIX (spec 15.1), e.g. http://apisix:9080 */
export function adminApiUrl(): string {
  return required("ADMIN_API_URL");
}
