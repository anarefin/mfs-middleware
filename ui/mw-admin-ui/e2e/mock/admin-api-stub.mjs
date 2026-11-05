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

// --- change requests (maker-checker, tickets 37 / 41 / 52) ------------------------------------
// The stub applies mw-admin-api's rules so the UI suite sees the same answers: ops-maker files,
// a different ops-checker decides (compared on username and subject), one open request per
// resource, actions per transaction status. On approval of a REVERSE the stub plays the
// orchestrator too: the original becomes REVERSED with a REVERSAL transaction next to it.

const TTL_MS = 72 * 3600 * 1000;
const WALLET_ID = /^01[3-9]\d{8}$/;
const TXN_ACTIONS = ["INQUIRE", "FORCE_COMMIT", "FORCE_RELEASE", "REVERSE"];

/** A mutable copy of the fixtures plus the change requests; reset by building a new one. */
export function createStore(fx = loadFixtures()) {
  return { ...structuredClone(fx), changeRequests: [], seq: 0 };
}

const maskWallet = (id) => `${id.slice(0, 4)}****${id.slice(-3)}`;

function target(action, t) {
  const s = t.status;
  switch (action) {
    case "INQUIRE": return s === "UNKNOWN" || s === "MANUAL_REVIEW" ? s : null;
    case "FORCE_COMMIT": return s === "MANUAL_REVIEW" ? "COMMITTING" : null;
    case "FORCE_RELEASE": return s === "MANUAL_REVIEW" ? "RELEASING" : null;
    case "REVERSE": return s === "COMPLETED" && !t.reversalTxnId && t.txnType !== "REVERSAL" ? "REVERSING" : null;
    default: return null;
  }
}

function badBody(body, actions, correlationId) {
  const details = [];
  if (actions && !actions.includes(body?.action)) details.push({ field: "action", issue: "not allowed" });
  const r = body?.reason;
  if (typeof r !== "string" || r.length < 1 || r.length > 500) details.push({ field: "reason", issue: "1 to 500 characters" });
  return details.length ? { status: 400, body: envelope("MW-VAL-001", "Invalid request", correlationId, details) } : null;
}

function uuid(store, prefix) {
  store.seq += 1;
  return `${prefix}-0000-7000-8000-${String(store.seq).padStart(12, "0")}`;
}

function newRequest(store, op, resourceType, resourceId, action, reason, diff, payload) {
  const now = new Date();
  const cr = {
    id: uuid(store, "0193a1b2"), resourceType, resourceId, action, payload, diff, makerId: op.username,
    makerSub: op.sub, status: "PENDING", reason, createdAt: now.toISOString(),
    expiresAt: new Date(now.getTime() + TTL_MS).toISOString(),
  };
  store.changeRequests.unshift(cr);
  return cr;
}

/** What the API answers: the row without the maker's subject; a wallet id masked. */
function view(cr) {
  const { makerSub: _sub, ...rest } = cr;
  return cr.resourceType === "wallet" ? { ...rest, resourceId: maskWallet(cr.resourceId) } : rest;
}

const openFor = (store, resourceType, resourceId) =>
  store.changeRequests.some((c) => c.status === "PENDING" && c.resourceType === resourceType && c.resourceId === resourceId);

function audit(store, txnId, action, actorId, resource, correlationId) {
  const d = store.details[txnId];
  if (!d) return;
  d.auditTrail.push({
    auditId: uuid(store, "a0d17000"), at: new Date().toISOString(), txnId, correlationId,
    actor: { type: "user", id: actorId }, action, resource, outcome: "SUCCESS", service: "mw-admin-api",
  });
}

/** POST /admin/v1/transactions/{txnId}/change-requests */
export function txnRequestAnswer(store, txnId, body, op, correlationId = "stub") {
  if (!op.roles.includes("ops-maker")) return errorAnswer(403, "MW-AUTH-002", "Needs the ops-maker role", correlationId);
  const bad = badBody(body, TXN_ACTIONS, correlationId);
  if (bad) return bad;
  const t = store.transactions.find((x) => x.txnId === txnId);
  if (!t) return errorAnswer(404, "MW-VAL-003", "Transaction not found", correlationId);
  const to = target(body.action, t);
  if (!to) return errorAnswer(409, "MW-VAL-030", `${body.action} does not fit status ${t.status}`, correlationId);
  if (openFor(store, "transaction", txnId)) return errorAnswer(409, "MW-VAL-031", "An open request exists for this transaction", correlationId);
  const payload = body.action === "REVERSE" ? { amount: { ...t.amount } } : {};
  const cr = newRequest(store, op, "transaction", txnId, body.action, body.reason, { before: { status: t.status }, after: { status: to } }, payload);
  audit(store, txnId, "admin.change_request.created", op.username, `transaction/${txnId}`, correlationId);
  return { status: 201, body: view(cr) };
}

/** POST /admin/v1/wallets/{walletId}/change-requests */
export function walletRequestAnswer(store, walletId, body, op, correlationId = "stub") {
  if (!op.roles.includes("ops-maker")) return errorAnswer(403, "MW-AUTH-002", "Needs the ops-maker role", correlationId);
  if (!WALLET_ID.test(walletId)) {
    return { status: 400, body: envelope("MW-VAL-001", "Invalid request", correlationId, [{ field: "walletId", issue: "11-digit MSISDN" }]) };
  }
  const bad = badBody(body, ["UNLOCK"], correlationId);
  if (bad) return bad;
  if (openFor(store, "wallet", walletId)) return errorAnswer(409, "MW-VAL-031", "An open request exists for this wallet", correlationId);
  const cr = newRequest(store, op, "wallet", walletId, "UNLOCK", body.reason,
    { before: { status: "LOCKED" }, after: { status: "ACTIVE" } }, { unlockTxnId: uuid(store, "0193a1b3") });
  return { status: 201, body: view(cr) };
}

/** GET /admin/v1/change-requests[?status&resourceId&limit&offset] */
export function listRequestsAnswer(store, query, correlationId = "stub") {
  const status = query.get("status");
  if (status && !["PENDING", "APPROVED", "REJECTED", "EXPIRED"].includes(status)) {
    return { status: 400, body: envelope("MW-VAL-001", "Invalid request", correlationId, [{ field: "status", issue: "unknown" }]) };
  }
  const resourceId = query.get("resourceId");
  const limit = Number(query.get("limit") ?? 50);
  const offset = Number(query.get("offset") ?? 0);
  const items = store.changeRequests
    .filter((c) => !status || c.status === status)
    .filter((c) => !resourceId || c.resourceId === resourceId)
    .map(view);
  return { status: 200, body: { items: items.slice(offset, offset + limit), limit, offset } };
}

function decidable(store, id, op, correlationId) {
  if (!op.roles.includes("ops-checker")) return { error: errorAnswer(403, "MW-AUTH-002", "Needs the ops-checker role", correlationId) };
  const cr = store.changeRequests.find((c) => c.id === id);
  if (!cr) return { error: errorAnswer(404, "MW-VAL-003", "Change request not found", correlationId) };
  if (cr.makerId === op.username || cr.makerSub === op.sub) {
    return { error: errorAnswer(403, "MW-AUTH-030", "The maker of a request cannot decide it", correlationId) };
  }
  if (cr.status !== "PENDING") return { error: errorAnswer(409, "MW-VAL-033", "The request is decided already", correlationId) };
  return { cr };
}

/** POST /admin/v1/change-requests/{id}/approve */
export function approveAnswer(store, id, op, correlationId = "stub") {
  const { cr, error } = decidable(store, id, op, correlationId);
  if (error) return error;
  if (cr.resourceType === "transaction") {
    const t = store.transactions.find((x) => x.txnId === cr.resourceId);
    if (!target(cr.action, t)) return errorAnswer(409, "MW-VAL-030", "The transaction moved on", correlationId);
    if (cr.action === "REVERSE") {
      const reversalTxnId = uuid(store, "0193a1b4");
      cr.payload = { ...cr.payload, reversalTxnId };
      const now = new Date().toISOString();
      store.transactions.push({
        txnId: reversalTxnId, txnType: "REVERSAL", channel: "SYSTEM", status: "COMPLETED", createdAt: now, updatedAt: now,
        amount: { ...t.amount }, originalTxnId: t.txnId,
      });
      store.details[reversalTxnId] = { timeline: [], partnerCalls: [], auditTrail: [] };
      Object.assign(t, { status: "REVERSED", previousStatus: "REVERSING", reversalTxnId, updatedAt: now });
    } else {
      cr.payload = { ...cr.payload, actionId: uuid(store, "0193a1b5") };
    }
    audit(store, t.txnId, "admin.change_request.approved", op.username, `transaction/${t.txnId}`, correlationId);
  } else {
    cr.payload = { ...cr.payload, commandId: `${cr.id}:unlock` };
  }
  Object.assign(cr, { status: "APPROVED", checkerId: op.username, decidedAt: new Date().toISOString() });
  return { status: 200, body: view(cr) };
}

/** POST /admin/v1/change-requests/{id}/reject */
export function rejectAnswer(store, id, body, op, correlationId = "stub") {
  const bad = badBody(body, null, correlationId);
  if (bad) return bad;
  const { cr, error } = decidable(store, id, op, correlationId);
  if (error) return error;
  Object.assign(cr, { status: "REJECTED", checkerId: op.username, decisionReason: body.reason, decidedAt: new Date().toISOString() });
  return { status: 200, body: view(cr) };
}
