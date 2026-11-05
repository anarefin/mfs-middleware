import { describe, expect, it } from "vitest";
import { canReveal, expiresSoon, internalRequest, rolesOf } from "./oidc";

function jwt(payload: object): string {
  const b64 = (o: object) => Buffer.from(JSON.stringify(o)).toString("base64url");
  return `${b64({ alg: "RS256" })}.${b64(payload)}.sig`;
}

describe("internalRequest", () => {
  const issuer = "https://localhost/realms/ops";

  it("sends a server-side call for the public issuer to Keycloak inside the stack, keeping the public host", () => {
    const r = internalRequest("https://localhost/realms/ops/protocol/openid-connect/token", issuer, "http://keycloak:8080");
    expect(r?.url).toBe("http://keycloak:8080/realms/ops/protocol/openid-connect/token");
    expect(r?.headers).toEqual({ "x-forwarded-host": "localhost", "x-forwarded-proto": "https", "x-forwarded-port": "443" });
  });

  it("keeps a non-default public port", () => {
    const r = internalRequest("https://localhost:8443/realms/ops/.well-known/openid-configuration",
      "https://localhost:8443/realms/ops", "http://keycloak:8080/");
    expect(r?.url).toBe("http://keycloak:8080/realms/ops/.well-known/openid-configuration");
    expect(r?.headers["x-forwarded-port"]).toBe("8443");
  });

  it("leaves calls alone when no internal URL is set or the URL is elsewhere", () => {
    expect(internalRequest(`${issuer}/x`, issuer, undefined)).toBeNull();
    expect(internalRequest("https://other.example/realms/ops/x", issuer, "http://keycloak:8080")).toBeNull();
  });
});

describe("rolesOf", () => {
  it("reads the realm roles of the access token", () => {
    expect(rolesOf(jwt({ realm_access: { roles: ["viewer", "offline_access"] } }))).toEqual(["viewer", "offline_access"]);
  });

  it("gives no role for a token it cannot read", () => {
    expect(rolesOf("not-a-jwt")).toEqual([]);
    expect(rolesOf(jwt({ realm_access: { roles: "viewer" } }))).toEqual([]);
    expect(rolesOf(undefined)).toEqual([]);
  });
});

describe("canReveal", () => {
  it("offers the MSISDN reveal to compliance-officer and auditor only (spec 15.4)", () => {
    expect(canReveal(["compliance-officer"])).toBe(true);
    expect(canReveal(["auditor"])).toBe(true);
    expect(canReveal(["viewer", "ops-checker", "security-admin"])).toBe(false);
  });
});

describe("expiresSoon", () => {
  it("refreshes 30 s before the access token expires", () => {
    const now = 1_000_000_000_000;
    expect(expiresSoon(now / 1000 + 31, now)).toBe(false);
    expect(expiresSoon(now / 1000 + 29, now)).toBe(true);
    expect(expiresSoon(undefined, now)).toBe(true);
  });
});
