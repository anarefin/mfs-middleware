// The stub mw-admin-api of the Playwright suite answers what contracts/api/mw-admin-api/admin.v1
// promises, so a green UI suite means the UI reads real answers. The contracts folder is the
// repo root's (module isolation allows it, read-only, tests only): MW_CONTRACTS_DIR, default
// ../../contracts.
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import Ajv2020 from "ajv/dist/2020.js";
import { describe, expect, it } from "vitest";
import { parse } from "yaml";
import {
  approveAnswer, createStore, detailAnswer, errorAnswer, listRequestsAnswer, loadFixtures, rejectAnswer, searchAnswer,
  txnRequestAnswer, walletRequestAnswer,
// @ts-expect-error plain JS module of the e2e stub
} from "../../e2e/mock/admin-api-stub.mjs";

const contractsDir = process.env.MW_CONTRACTS_DIR ?? resolve(__dirname, "../../../../contracts");
const doc = parse(readFileSync(resolve(contractsDir, "api/mw-admin-api/admin.v1.openapi.yaml"), "utf8"));
const ajv = new Ajv2020({ strict: false, validateFormats: false, allErrors: true });
ajv.addSchema({ $id: "admin.v1", components: doc.components }, "admin.v1");

function check(schema: string, body: unknown) {
  const validate = ajv.getSchema(`admin.v1#/components/schemas/${schema}`);
  if (!validate) throw new Error(`no schema ${schema}`);
  const ok = validate(body);
  expect(ok, JSON.stringify(validate.errors)).toBe(true);
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const fx = loadFixtures();

describe("stub admin API answers match admin.v1", () => {
  it("search pages are TxnPage", () => {
    for (const q of ["", "msisdn=01712345678", "txnId=3f1c2b9e-7a4d-4c1e-9b8a-2d6f0e5a1c33", "status=FAILED"]) {
      const r = searchAnswer(fx, new URLSearchParams(q));
      expect(r.status).toBe(200);
      check("TxnPage", r.body);
      for (const t of r.body.items) expect(t.txnId).toMatch(UUID);
    }
  });

  it("details are TxnDetail, masked and revealed", () => {
    for (const id of Object.keys(fx.details)) {
      check("TxnDetail", detailAnswer(fx, id).body);
      check("TxnDetail", detailAnswer(fx, id, { reveal: true, mayReveal: true }).body);
    }
  });

  it("change requests are ChangeRequest / ChangeRequestPage, through the whole maker-checker path", () => {
    const store = createStore(fx);
    const maker = { username: "ops-maker01", sub: "sub-ops-maker01", roles: ["ops-maker"] };
    const checker = { username: "ops-checker01", sub: "sub-ops-checker01", roles: ["ops-checker"] };
    const P2P = "3f1c2b9e-7a4d-4c1e-9b8a-2d6f0e5a1c33";
    const STUCK = "c4e5f6a7-8b9c-4d0e-9f1a-2b3c4d5e6f70";

    const reverse = txnRequestAnswer(store, P2P, { action: "REVERSE", reason: "duplicate" }, maker);
    expect(reverse.status).toBe(201);
    check("ChangeRequest", reverse.body);
    const tsq = txnRequestAnswer(store, STUCK, { action: "INQUIRE", reason: "check" }, maker);
    const unlock = walletRequestAnswer(store, "01712345678", { action: "UNLOCK", reason: "verified" }, maker);
    expect(unlock.body.resourceId).toBe("0171****678");
    check("ChangeRequest", unlock.body);
    check("ChangeRequestPage", listRequestsAnswer(store, new URLSearchParams("status=PENDING")).body);

    expect(approveAnswer(store, reverse.body.id, maker).body.code).toBe("MW-AUTH-002");
    const makerAsChecker = { ...maker, roles: ["ops-maker", "ops-checker"] };
    expect(approveAnswer(store, reverse.body.id, makerAsChecker).body.code).toBe("MW-AUTH-030");
    const approved = approveAnswer(store, reverse.body.id, checker);
    expect(approved.body).toMatchObject({ status: "APPROVED", checkerId: "ops-checker01" });
    check("ChangeRequest", approved.body);
    check("ChangeRequest", approveAnswer(store, unlock.body.id, checker).body);
    check("ChangeRequest", rejectAnswer(store, tsq.body.id, { reason: "no" }, checker).body);
    check("ChangeRequestPage", listRequestsAnswer(store, new URLSearchParams()).body);

    // the reversed original and its REVERSAL read as TxnDetail
    const original = detailAnswer(store, P2P).body;
    expect(original.transaction.status).toBe("REVERSED");
    check("TxnDetail", original);
    check("TxnDetail", detailAnswer(store, original.transaction.reversalTxnId).body);

    for (const refused of [
      approveAnswer(store, reverse.body.id, checker),
      txnRequestAnswer(store, P2P, { action: "REVERSE", reason: "again" }, maker),
      walletRequestAnswer(store, "01212345678", { action: "UNLOCK", reason: "x" }, maker),
      txnRequestAnswer(store, STUCK, { action: "INQUIRE", reason: "x" }, checker),
    ]) {
      expect(refused.status).toBeGreaterThanOrEqual(400);
      check("ErrorEnvelope", refused.body);
    }
  });

  it("errors are the 5.3 envelope", () => {
    check("ErrorEnvelope", detailAnswer(fx, "00000000-0000-4000-8000-000000000000").body);
    check("ErrorEnvelope", errorAnswer(401, "MW-AUTH-001", "Unauthorised").body);
    check("ErrorEnvelope", searchAnswer(fx, new URLSearchParams("limit=999")).body);
  });
});
