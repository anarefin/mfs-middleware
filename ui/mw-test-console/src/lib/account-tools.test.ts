import { describe, expect, it } from "vitest";
import { KC_REALMS, USER_ACTIONS, WALLET_ACTIONS, isKcRealm, isUserAction, isWalletAction } from "./account-tools";

describe("account tools: a fixed list of actions, no free-form proxy", () => {
  it("allows only the wallet actions of the seed page", () => {
    expect([...WALLET_ACTIONS]).toEqual(["status", "unlock", "resetPin", "unbind", "setBalance"]);
    for (const a of WALLET_ACTIONS) expect(isWalletAction(a)).toBe(true);
    for (const a of ["", "delete", "STATUS", "status ", "__proto__", "constructor"]) expect(isWalletAction(a)).toBe(false);
  });

  it("allows only the user actions and the two realms", () => {
    expect([...USER_ACTIONS]).toEqual(["unlock", "resetPassword"]);
    expect(isUserAction("resetPassword")).toBe(true);
    // every user keeps the pre-set one-time code: no tool deletes it
    expect(isUserAction("resetTotp")).toBe(false);
    expect(isUserAction("deleteUser")).toBe(false);
    expect(isUserAction("toString")).toBe(false);
    expect([...KC_REALMS]).toEqual(["ops", "portals"]);
    expect(isKcRealm("master")).toBe(false);
    expect(isKcRealm("portals")).toBe(true);
  });
});

import { SHOWABLE_ENVS } from "./secrets";

describe("showable env names", () => {
  it("covers the logins and the platform secrets only", () => {
    for (const e of ["KC_OPS_TEST_PASSWORD", "KC_PORTALS_TEST_TOTP_SECRET", "MW_SEED_APP_PIN", "VAULT_DEV_ROOT_TOKEN", "SOFTHSM_PAYSIM_SO_PIN"]) expect(SHOWABLE_ENVS.has(e)).toBe(true);
    for (const e of ["PATH", "POSTGRES_PASSWORD", "HOME"]) expect(SHOWABLE_ENVS.has(e)).toBe(false);
  });
});
