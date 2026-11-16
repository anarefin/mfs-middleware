import { describe, expect, it } from "vitest";
import { appWalletRows, parseManifest, personasOf } from "./seed-manifest";

const persona = (over: Record<string, unknown> = {}) => ({
  persona: "maker", realm: "ops", username: "seed-ops-maker", displayName: "Nusrat Jahan", roles: ["viewer", "ops-maker"],
  attributes: {}, loginClient: "mw-e2e-ops", passwordEnv: "KC_OPS_TEST_PASSWORD", totpSecretEnv: "KC_OPS_TEST_TOTP_SECRET", ...over,
});

const manifest = {
  schemaVersion: 1,
  personas: [
    persona(),
    persona({ persona: "org-maker", realm: "portals", username: "seed-org-maker", displayName: "Mizan R", attributes: { orgId: "ORG001" }, passwordEnv: "KC_PORTALS_TEST_PASSWORD", totpSecretEnv: "KC_PORTALS_TEST_TOTP_SECRET" }),
    persona({ persona: "customer", realm: "mw-auth", username: "seed-customer01", attributes: { msisdn: "01712000001" }, passwordEnv: "MW_SEED_APP_PIN", totpSecretEnv: null }),
    persona({ persona: "run-customer", realm: "mw-auth", username: "seed-run-customer01", attributes: { msisdn: "01714010001" }, passwordEnv: "MW_SEED_APP_PIN", totpSecretEnv: null }),
  ],
  importedUsers: [persona({ persona: "ops-maker01", username: "ops-maker01", displayName: undefined, totpSecretEnv: null })],
  wallets: [
    { walletId: "01712000001", type: "CUSTOMER", openingBalanceMinor: 1000000, holderName: "Shirin Akter" },
    { walletId: "01714010001", type: "CUSTOMER", openingBalanceMinor: 1000000000 },
  ],
  partners: [{ partnerId: "ocs", family: "telco", version: 1, enabled: true, timeoutMs: 20000 }],
  ussdMenus: [{ menuId: "main", version: 2, mnos: ["gp", "robi"] }],
  templates: [{ code: "TXN_P2P_RECEIVED", channel: "SMS", locale: "en", version: 1 }],
  appPins: { pinEnv: "MW_SEED_APP_PIN", walletIds: ["01711000001", "01712000001"] },
};

describe("parseManifest", () => {
  it("reads every section of the seeder's manifest", () => {
    const m = parseManifest(manifest);
    expect(m.ok).toBe(true);
    if (!m.ok) return;
    expect(m.value.personas).toHaveLength(4);
    expect(m.value.importedUsers).toHaveLength(1);
    expect(m.value.personas[0]).toMatchObject({ username: "seed-ops-maker", displayName: "Nusrat Jahan", realm: "ops", totpSecretEnv: "KC_OPS_TEST_TOTP_SECRET" });
    expect(m.value.importedUsers[0]?.displayName).toBeUndefined();
    expect(m.value.importedUsers[0]?.totpSecretEnv).toBeNull();
    expect(m.value.wallets[0]?.holderName).toBe("Shirin Akter");
    expect(m.value.wallets[1]?.holderName).toBeUndefined();
    expect(m.value.partners[0]).toEqual({ partnerId: "ocs", family: "telco", version: 1, enabled: true, timeoutMs: 20000 });
    expect(m.value.appPins?.walletIds).toContain("01711000001");
  });

  it("treats displayName, holderName, importedUsers and appPins as optional", () => {
    const { importedUsers: _i, appPins: _a, ...rest } = manifest;
    const m = parseManifest({ ...rest, appPins: null });
    expect(m.ok).toBe(true);
    if (m.ok) {
      expect(m.value.importedUsers).toEqual([]);
      expect(m.value.appPins).toBeNull();
    }
  });

  it("skips malformed entries instead of failing the page", () => {
    const m = parseManifest({ ...manifest, personas: [persona(), { username: 7 }, null], wallets: [{ walletId: "x" }, "y"] });
    expect(m.ok).toBe(true);
    if (m.ok) {
      expect(m.value.personas).toHaveLength(1);
      expect(m.value.wallets).toEqual([]);
    }
  });

  it("refuses what is not a manifest", () => {
    expect(parseManifest(null).ok).toBe(false);
    expect(parseManifest("x").ok).toBe(false);
    expect(parseManifest({ schemaVersion: 2, personas: [] }).ok).toBe(false);
    expect(parseManifest({ schemaVersion: 1 }).ok).toBe(false);
  });
});

describe("views of the manifest", () => {
  const m = parseManifest(manifest);
  if (!m.ok) throw new Error("fixture");

  it("splits logins by realm, the imported users included", () => {
    expect(personasOf(m.value, "ops").map((p) => p.username)).toEqual(["seed-ops-maker", "ops-maker01"]);
    expect(personasOf(m.value, "portals").map((p) => p.username)).toEqual(["seed-org-maker"]);
  });

  it("lists the app wallets: core-mock fixtures, seeded app users and PIN wallets, without the run customers", () => {
    const rows = appWalletRows(m.value);
    const ids = rows.map((r) => r.walletId);
    expect(ids).toContain("01711000001");
    expect(ids).toContain("01712000001");
    expect(ids).not.toContain("01714010001");
    expect(new Set(ids).size).toBe(ids.length);
    expect(rows.find((r) => r.walletId === "01711000001")).toMatchObject({ user: "customer01", name: "Rahim Uddin", role: "customer" });
    expect(rows.find((r) => r.walletId === "01712000001")).toMatchObject({ user: "seed-customer01", name: "Shirin Akter", hasPin: true });
  });

  it("still lists the core-mock fixtures without a manifest", () => {
    expect(appWalletRows(null).map((r) => r.walletId)).toContain("01911000002");
  });
});

describe("personasOf with the realm import", () => {
  it("adds the realm import's portal users a manifest does not list, and works without a manifest", async () => {
    const { PORTALS_IMPORT } = await import("./seed-manifest");
    expect(personasOf(null, "portals", PORTALS_IMPORT).map((p) => p.username)).toContain("sp-viewer01");
    const m = parseManifest(manifest);
    if (!m.ok) throw new Error("fixture");
    const names = personasOf(m.value, "portals", PORTALS_IMPORT).map((p) => p.username);
    expect(names[0]).toBe("seed-org-maker");
    expect(names).toContain("org-maker01");
    expect(personasOf(null, "ops")).toEqual([]);
  });
});
