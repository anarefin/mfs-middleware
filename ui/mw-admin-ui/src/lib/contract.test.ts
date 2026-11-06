// The fake admin API answers the Vitest component tests use (src/lib/testing/admin-fixtures.ts) are
// what contracts/api/mw-admin-api/admin.v1 promises, so those tests read answers the real
// mw-admin-api may send (the Playwright suite runs against the real one, ticket 59). The contracts
// folder is the repo root's (module isolation allows it, read-only, tests only): MW_CONTRACTS_DIR,
// default ../../contracts.
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import Ajv2020 from "ajv/dist/2020.js";
import { describe, expect, it } from "vitest";
import { parse } from "yaml";
import { completedP2p, page, pendingReversal, unavailable } from "./testing/admin-fixtures";

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

describe("component-test fixtures match admin.v1", () => {
  it("the transaction detail is a TxnDetail", () => {
    check("TxnDetail", completedP2p);
  });

  it("change requests are ChangeRequest / ChangeRequestPage", () => {
    check("ChangeRequest", pendingReversal);
    check("ChangeRequestPage", page([]));
    check("ChangeRequestPage", page([pendingReversal]));
  });

  it("the failure is the 5.3 envelope", () => {
    check("ErrorEnvelope", unavailable);
  });
});
