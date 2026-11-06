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
  approveAnswer, createStore, detailAnswer, errorAnswer, listRequestsAnswer, loadFixtures, menuProposeAnswer,
  partnerAnswer, partnerRequestAnswer, partnersAnswer, rejectAnswer, searchAnswer, templateProposeAnswer,
  txnRequestAnswer, versionAnswer, versionsAnswer, walletRequestAnswer,
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

  it("partners, menus and templates through maker-checker are Partner / ChangeRequest / ContentVersion (ticket 42)", () => {
    const store = createStore(fx);
    const maker = { username: "ops-maker01", sub: "sub-ops-maker01", roles: ["ops-maker"] };
    const checker = { username: "ops-checker01", sub: "sub-ops-checker01", roles: ["ops-checker"] };
    const integration = { username: "ops-admin01", sub: "sub-ops-admin01", roles: ["integration-checker"] };

    check("PartnerPage", partnersAnswer(store).body);
    check("Partner", partnerAnswer(store, "ocs").body);
    const disable = partnerRequestAnswer(store, "ocs", { action: "DISABLE", reason: "incident" }, maker);
    expect(disable.status).toBe(201);
    expect(disable.body.diff).toEqual({ before: { enabled: true }, after: { enabled: false } });
    check("ChangeRequest", disable.body);
    const timeouts = partnerRequestAnswer(store, "desco", { action: "SET_TIMEOUTS", reason: "slow", timeouts: { timeoutMs: 5000 } }, maker);
    check("ChangeRequest", timeouts.body);
    const approved = approveAnswer(store, disable.body.id, integration);
    expect(approved.body).toMatchObject({ status: "APPROVED", payload: { version: 4 } });
    check("ChangeRequest", approved.body);
    const ocs = partnerAnswer(store, "ocs").body;
    expect(ocs).toMatchObject({ version: 4, config: { enabled: false }, updatedBy: "ops-maker01", approvedBy: "ops-admin01" });
    check("Partner", ocs);

    const MENU = "ussd-menu/main";
    const base = versionAnswer(store, MENU, 1).body;
    check("ContentVersion", base);
    const menu = menuProposeAnswer(store, "main", { menu: base.value.menu, activations: [{ mno: "gp" }], reason: "new text" }, maker);
    expect(menu.status).toBe(201);
    expect(menu.body.diff).toEqual({ before: { configKey: MENU, version: 1 }, after: { configKey: MENU, version: 2 } });
    check("ChangeRequest", menu.body);
    check("ContentVersionPage", versionsAnswer(store, MENU).body);
    expect(approveAnswer(store, menu.body.id, integration).body.code).toBe("MW-AUTH-002");
    check("ChangeRequest", approveAnswer(store, menu.body.id, checker).body);
    expect(versionAnswer(store, MENU, 2).body.status).toBe("PUBLISHED");
    check("ContentVersion", versionAnswer(store, MENU, 2).body);

    const tpl = templateProposeAnswer(store, "TXN_CASH_OUT_SENT", "SMS", "bn", { body: "নতুন {{amount}}", senderId: "Bits", reason: "wording" }, maker);
    check("ChangeRequest", tpl.body);
    check("ChangeRequest", rejectAnswer(store, tpl.body.id, { reason: "typo" }, checker).body);
    expect(versionAnswer(store, "template/TXN_CASH_OUT_SENT/SMS/bn", 2).body.status).toBe("REJECTED");
    check("ContentVersionPage", versionsAnswer(store, "template/TXN_CASH_OUT_SENT/SMS/bn").body);

    for (const refused of [
      partnerRequestAnswer(store, "ocs", { action: "DISABLE", reason: "again" }, maker),
      partnerRequestAnswer(store, "nope", { action: "ENABLE", reason: "x" }, maker),
      partnerRequestAnswer(store, "desco", { action: "ENABLE", reason: "x" }, maker),
      menuProposeAnswer(store, "main", { menu: { start: "x", messages: {}, nodes: { a: { type: "END" } } }, activations: [{ mno: "gp" }], reason: "x" }, maker),
      templateProposeAnswer(store, "OTP_X", "EMAIL", "en", { body: "{{#a}}", reason: "x" }, maker),
      versionAnswer(store, MENU, 99),
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
