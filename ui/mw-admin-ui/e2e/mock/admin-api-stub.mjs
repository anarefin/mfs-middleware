// Answers of the stub mw-admin-api used by the Playwright suite (e2e/mock/server.mjs), built from
// e2e/fixtures/admin-api.json. src/lib/contract.test.ts validates these answers against
// contracts/api/mw-admin-api/admin.v1.openapi.yaml, so the stub cannot drift from the contract.

import { readFileSync } from "node:fs";

export function loadFixtures() {
  return JSON.parse(readFileSync(new URL("../fixtures/admin-api.json", import.meta.url), "utf8"));
}

function envelope(code, message, correlationId, details = []) {
  return { code, message, correlationId, retryable: code.startsWith("MW-SYS"), details };
}

/** 01..., 880..., +880... with spaces/dashes -> 01XXXXXXXXX (as mw-orchestrator normalises). */
function normaliseMsisdn(raw) {
  const digits = raw.replace(/\D/g, "");
  return digits.startsWith("880") ? `0${digits.slice(3)}` : digits;
}

/** GET /admin/v1/transactions -> { status, body } */
export function searchAnswer(fx, query, correlationId = "stub") {
  let items = fx.transactions;
  const txnId = query.get("txnId");
  const msisdn = query.get("msisdn");
  const status = query.get("status");
  if (txnId) items = items.filter((t) => t.txnId === txnId.toLowerCase());
  if (msisdn) {
    const ids = fx.msisdnIndex[normaliseMsisdn(msisdn)] ?? [];
    items = items.filter((t) => ids.includes(t.txnId));
  }
  if (status) items = items.filter((t) => t.status === status);
  const limit = Number(query.get("limit") ?? 50);
  const offset = Number(query.get("offset") ?? 0);
  if (!(limit >= 1 && limit <= 200) || !(offset >= 0)) {
    return { status: 400, body: envelope("MW-VAL-001", "Invalid request", correlationId, [{ field: "limit", issue: "out of range" }]) };
  }
  return { status: 200, body: { items: items.slice(offset, offset + limit), limit, offset } };
}

/** GET /admin/v1/transactions/{txnId}[?reveal=true] -> { status, body } */
export function detailAnswer(fx, txnId, { reveal = false, mayReveal = false } = {}, correlationId = "stub") {
  const t = fx.transactions.find((x) => x.txnId === txnId);
  const d = fx.details[txnId];
  if (!t || !d) return { status: 404, body: envelope("MW-VAL-003", "Transaction not found", correlationId) };
  const revealed = reveal && mayReveal && !!fx.revealedMsisdn[txnId];
  const transaction = revealed
    ? { ...t, initiator: { ...t.initiator, id: fx.revealedMsisdn[txnId] } }
    : t;
  return { status: 200, body: { transaction, ...d, revealed } };
}

export function errorAnswer(status, code, message, correlationId = "stub") {
  return { status, body: envelope(code, message, correlationId) };
}
