import { describe, expect, it } from "vitest";
import { appUsers, msisdnOf, userLabel, usersOf } from "./app-users";
import { parseManifest, type SeedManifest } from "./seed-manifest";

const auth = (persona: string, username: string, msisdn: string, roles: string[], displayName?: string) => ({
  persona, realm: "mw-auth", username, displayName, roles, attributes: { msisdn }, passwordEnv: "MW_SEED_APP_PIN", totpSecretEnv: null,
});

const raw = {
  schemaVersion: 1,
  personas: [
    { persona: "maker", realm: "ops", username: "seed-ops-maker", roles: ["ops-maker"], attributes: {}, totpSecretEnv: "KC_OPS_TEST_TOTP_SECRET" },
    auth("run-customer", "seed-run-customer01", "01714010001", ["customer"], "Sakib Hasan"),
    auth("customer", "seed-customer01", "01712000001", ["customer"], "Sumaiya Islam"),
    auth("agent", "seed-agent01", "01812000001", ["agent"], "Monir Hossain"),
    auth("merchant", "seed-merchant01", "01912000001", ["merchant"]),
    auth("customer", "seed-customer09", "01712000009", ["customer"]),
  ],
  wallets: [
    { walletId: "01812000001", type: "AGENT", openingBalanceMinor: 1, holderName: "Monir Store" },
    { walletId: "01912000001", type: "MERCHANT", openingBalanceMinor: 0, holderName: "Dhanmondi Bakery" },
    { walletId: "01712000077", type: "CUSTOMER", openingBalanceMinor: 0, holderName: "No Persona" },
  ],
  appPins: {
    pinEnv: "MW_SEED_APP_PIN",
    walletIds: ["01714010001", "01712000001", "01812000001", "01912000001", "01711000001", "01711000002", "01811000001", "01911000001", "01712000077"],
  },
};
const manifest = (() => {
  const p = parseManifest(raw);
  if (!p.ok) throw new Error(p.error);
  return p.value as SeedManifest;
})();

describe("appUsers", () => {
  it("lists only PIN-ready wallets: personas first, seeder's users, run customers, then wallets without a user", () => {
    const users = appUsers(manifest);
    expect(users.map((u) => u.user)).toEqual([
      "customer01", "customer02", "agent01", "merchant01", "seed-customer01", "seed-agent01", "seed-merchant01", "seed-run-customer01", "01712000077",
    ]);
    // merchant02 and seed-customer09 have no app PIN
    expect(users.find((u) => u.user === "merchant02")).toBeUndefined();
    expect(users.find((u) => u.user === "seed-customer09")).toBeUndefined();
  });

  it("takes the role from the persona's roles and the holder name from the wallets", () => {
    const users = appUsers(manifest);
    expect(users.find((u) => u.user === "seed-agent01")).toEqual({ user: "seed-agent01", msisdn: "01812000001", role: "agent", name: "Monir Store" });
    expect(users.find((u) => u.user === "seed-customer01")?.name).toBe("Sumaiya Islam");
    expect(users.find((u) => u.user === "01712000077")).toMatchObject({ role: "customer", name: "No Persona" });
  });

  it("groups by role", () => {
    const users = appUsers(manifest);
    expect(usersOf(users, "agent").map((u) => u.user)).toEqual(["agent01", "seed-agent01"]);
    expect(usersOf(users, "merchant").map((u) => u.user)).toEqual(["merchant01", "seed-merchant01"]);
  });

  it("without a manifest: the core-mock personas only", () => {
    expect(appUsers(null).map((u) => u.user)).toEqual(["customer01", "customer02", "agent01", "merchant01", "merchant02"]);
  });

  it("labels and resolves wallets", () => {
    const users = appUsers(manifest);
    expect(userLabel(users[0]!)).toBe("Rahim Uddin, 01711000001 (customer01)");
    expect(userLabel(users.find((u) => u.user === "01712000077")!)).toBe("No Persona, 01712000077");
    expect(msisdnOf(users, "seed-customer01")).toBe("01712000001");
    expect(msisdnOf(users, "01711000555")).toBe("01711000555");
    expect(msisdnOf(users, "nobody")).toBeUndefined();
  });
});
