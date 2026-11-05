// The stub mw-admin-api of the Playwright suite answers what contracts/api/mw-admin-api/admin.v1
// promises, so a green UI suite means the UI reads real answers. The contracts folder is the
// repo root's (module isolation allows it, read-only, tests only): MW_CONTRACTS_DIR, default
// ../../contracts.
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import Ajv2020 from "ajv/dist/2020.js";
import { describe, expect, it } from "vitest";
import { parse } from "yaml";
// @ts-expect-error plain JS module of the e2e stub
import { detailAnswer, errorAnswer, loadFixtures, searchAnswer } from "../../e2e/mock/admin-api-stub.mjs";

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

  it("errors are the 5.3 envelope", () => {
    check("ErrorEnvelope", detailAnswer(fx, "00000000-0000-4000-8000-000000000000").body);
    check("ErrorEnvelope", errorAnswer(401, "MW-AUTH-001", "Unauthorised").body);
    check("ErrorEnvelope", searchAnswer(fx, new URLSearchParams("limit=999")).body);
  });
});
