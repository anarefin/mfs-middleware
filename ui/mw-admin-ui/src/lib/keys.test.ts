import { describe, expect, it } from "vitest";
import { checkerLabel, diffLabel, mayDecide, mayRetryApproval } from "./approvals";
import {
  canAdminKeys, canReadKeys, ceremonyStep, kcvMatches, mayEnterComponent, parseCheckValue, parseComponent, parseKeyId,
  parsePurpose, retirable,
} from "./keys";
import { ceremonyCombined, ceremonyOneShare, pendingKeyRotation, pinHmacKey } from "./testing/admin-fixtures";

describe("keys (ticket 75)", () => {
  it("security-admin manages keys, auditor only reads them", () => {
    expect(canAdminKeys(["security-admin"])).toBe(true);
    expect(canAdminKeys(["auditor"])).toBe(false);
    expect(canReadKeys(["auditor"])).toBe(true);
    expect(canReadKeys(["ops-checker", "viewer"])).toBe(false);
  });

  it("parses what a custodian types from the paper share", () => {
    expect(parseComponent("ab12 cd34-".repeat(1) + "0".repeat(56))).toBe("AB12CD34" + "0".repeat(56));
    expect(parseComponent("ab".repeat(31))).toBeNull();
    expect(parseComponent("zz".repeat(32))).toBeNull();
    expect(parseCheckValue(" a1b2c3 ")).toBe("A1B2C3");
    expect(parseCheckValue("A1B2C")).toBeNull();
    expect(parseKeyId("pin-hmac")).toBe("pin-hmac");
    expect(parseKeyId("PIN HMAC")).toBeNull();
    expect(parsePurpose("hmac_webhook")).toBe("HMAC_WEBHOOK");
  });

  it("only VERIFY_ONLY versions can be retired", () => {
    expect(retirable(pinHmacKey).map((v) => v.kid)).toEqual(["pin-hmac.v1"]);
  });

  it("walks custodian 1, custodian 2, KCV confirmation", () => {
    const empty = { ...ceremonyOneShare, shares: [] };
    expect(ceremonyStep(empty)).toBe("custodian1");
    expect(ceremonyStep(ceremonyOneShare)).toBe("custodian2");
    expect(ceremonyStep(ceremonyCombined)).toBe("confirm");
    expect(ceremonyStep({ ...ceremonyCombined, status: "APPROVED" })).toBe("closed");
  });

  it("the second component must come from another security-admin", () => {
    const admin = ["security-admin"];
    expect(mayEnterComponent(ceremonyOneShare, "seed-ops-secadmin1", admin)).toBe(false);
    expect(mayEnterComponent(ceremonyOneShare, "seed-ops-secadmin2", admin)).toBe(true);
    expect(mayEnterComponent(ceremonyOneShare, "seed-ops-secadmin2", ["auditor"])).toBe(false);
    expect(mayEnterComponent(ceremonyCombined, "seed-ops-secadmin2", admin)).toBe(false);
  });

  it("compares the KCV case-insensitively", () => {
    expect(kcvMatches(ceremonyCombined, "a1b2c3")).toBe(true);
    expect(kcvMatches(ceremonyCombined, "A1B2C4")).toBe(false);
    expect(kcvMatches(ceremonyOneShare, "A1B2C3")).toBe(false);
  });

  it("key requests are decided by another security-admin, never an ops-checker", () => {
    expect(mayDecide(pendingKeyRotation, "seed-ops-secadmin2", ["security-admin"])).toBe(true);
    expect(mayDecide(pendingKeyRotation, "seed-ops-secadmin1", ["security-admin"])).toBe(false);
    expect(mayDecide(pendingKeyRotation, "seed-ops-checker", ["ops-checker"])).toBe(false);
    expect(checkerLabel(pendingKeyRotation)).toBe("another security-admin");
    expect(diffLabel(pendingKeyRotation)).toEqual({ before: "pin-hmac.v2 ACTIVE", after: "pin-hmac.v3 ACTIVE" });
    // claim-first (APPROVING): only a retry of the approval, never by the maker, never a reject
    const approving = { ...pendingKeyRotation, status: "APPROVING" as const };
    expect(mayDecide(approving, "seed-ops-secadmin2", ["security-admin"])).toBe(false);
    expect(mayRetryApproval(approving, "seed-ops-secadmin2", ["security-admin"])).toBe(true);
    expect(mayRetryApproval(approving, "seed-ops-secadmin1", ["security-admin"])).toBe(false);
    expect(mayRetryApproval(pendingKeyRotation, "seed-ops-secadmin2", ["security-admin"])).toBe(false);
  });
});
