import { describe, expect, it } from "vitest";
import { canMake, diffLabel, mayDecide, parseReason, parseWalletId, txnActionsFor } from "./approvals";
import type { ChangeRequest, Txn } from "./types";

const txn = (over: Partial<Txn>): Txn => ({
  txnId: "3f1c2b9e-7a4d-4c1e-9b8a-2d6f0e5a1c33", txnType: "P2P", channel: "APP", status: "COMPLETED",
  createdAt: "2026-11-05T09:30:00Z", updatedAt: "2026-11-05T09:30:02Z", amount: { valueMinor: 100, currency: "BDT" },
  ...over,
});

const cr = (over: Partial<ChangeRequest>): ChangeRequest => ({
  id: "0193a1b2-0000-7000-8000-000000000001", resourceType: "transaction",
  resourceId: "3f1c2b9e-7a4d-4c1e-9b8a-2d6f0e5a1c33", action: "REVERSE", payload: {},
  diff: { before: { status: "COMPLETED" }, after: { status: "REVERSING" } }, makerId: "ops-maker01",
  status: "PENDING", reason: "customer complaint", createdAt: "2026-11-05T10:00:00Z", expiresAt: "2026-11-08T10:00:00Z",
  ...over,
});

describe("actions a maker may ask for, per transaction status (ticket 37 rules)", () => {
  it("offers a reversal of a completed, unreversed transaction only", () => {
    expect(txnActionsFor(txn({ status: "COMPLETED" }))).toEqual(["REVERSE"]);
    expect(txnActionsFor(txn({ status: "COMPLETED", reversalTxnId: "8b2d4e61-1f3a-4b7c-a9d0-5e6f7a8b9c0d" }))).toEqual([]);
    expect(txnActionsFor(txn({ status: "COMPLETED", txnType: "REVERSAL" }))).toEqual([]);
  });

  it("offers a manual TSQ when the outcome is unknown, and force commit / release in manual review", () => {
    expect(txnActionsFor(txn({ status: "UNKNOWN" }))).toEqual(["INQUIRE"]);
    expect(txnActionsFor(txn({ status: "MANUAL_REVIEW" }))).toEqual(["INQUIRE", "FORCE_COMMIT", "FORCE_RELEASE"]);
  });

  it("offers nothing in other states", () => {
    for (const status of ["FAILED", "REJECTED", "POSTING", "REVERSED", "REVERSING"]) {
      expect(txnActionsFor(txn({ status }))).toEqual([]);
    }
  });
});

describe("who may act", () => {
  it("only ops-maker files requests", () => {
    expect(canMake(["ops-maker"])).toBe(true);
    expect(canMake(["viewer", "ops-checker", "recon-maker"])).toBe(false);
  });

  it("an ops-checker decides a pending request of somebody else, never their own", () => {
    expect(mayDecide(cr({}), "ops-checker01", ["ops-checker"])).toBe(true);
    expect(mayDecide(cr({}), "ops-maker01", ["ops-maker", "ops-checker"])).toBe(false);
    expect(mayDecide(cr({}), "ops-viewer01", ["viewer"])).toBe(false);
    expect(mayDecide(cr({ status: "APPROVED" }), "ops-checker01", ["ops-checker"])).toBe(false);
    expect(mayDecide(cr({}), undefined, ["ops-checker"])).toBe(false);
  });
});

describe("form input", () => {
  it("takes a wallet id in any MSISDN form and gives the 11-digit id", () => {
    expect(parseWalletId("01712345678")).toBe("01712345678");
    expect(parseWalletId("+880 1712-345678")).toBe("01712345678");
    expect(parseWalletId("8801712345678")).toBe("01712345678");
    expect(parseWalletId("01212345678")).toBeNull();
    expect(parseWalletId("0171234567")).toBeNull();
    expect(parseWalletId("")).toBeNull();
  });

  it("wants a reason of 1 to 500 characters", () => {
    expect(parseReason("  duplicate debit  ")).toBe("duplicate debit");
    expect(parseReason("   ")).toBeNull();
    expect(parseReason("x".repeat(501))).toBeNull();
    expect(parseReason(null)).toBeNull();
  });
});

describe("diff view", () => {
  it("shows the status before and the one the action leads to", () => {
    expect(diffLabel(cr({}))).toEqual({ before: "COMPLETED", after: "REVERSING" });
    expect(diffLabel(cr({ diff: { before: { status: "MANUAL_REVIEW" }, after: {} } }))).toEqual({ before: "MANUAL_REVIEW", after: "-" });
  });
});
