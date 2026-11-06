// Fake admin API answers for the Vitest component tests (never imported by the app; fake values only).
// src/lib/contract.test.ts validates each against contracts/api/mw-admin-api/admin.v1, so a green
// component test reads answers the real mw-admin-api may send.
import type { ChangeRequest, ChangeRequestPage, ErrorEnvelope, TxnDetail } from "../types";

export const TXN_ID = "3f1c2b9e-7a4d-4c1e-9b8a-2d6f0e5a1c33";

export const completedP2p: TxnDetail = {
  transaction: {
    txnId: TXN_ID, txnType: "P2P", channel: "USSD", status: "COMPLETED", previousStatus: "POSTING",
    createdAt: "2026-11-05T09:30:00.000Z", updatedAt: "2026-11-05T09:30:02.412Z",
    correlationId: "5d0e1c3a-9b7f-4e2d-8a6c-1f0e2d3c4b5a",
    amount: { valueMinor: 1000, currency: "BDT" }, fee: { valueMinor: 0, currency: "BDT" },
    initiator: { type: "CUSTOMER", id: "0171****001", name: "R.", walletType: "CUSTOMER" },
    debitParty: { type: "CUSTOMER", id: "0171****001", name: "R." },
    creditParty: { type: "CUSTOMER", id: "0171****002", name: "K." },
    coreRef: "CORE-77001",
  },
  timeline: [
    { kind: "STATE", to: "RECEIVED", trigger: "TxnRequested", at: "2026-11-05T09:30:00.010Z" },
    { kind: "STATE", from: "POSTING", to: "COMPLETED", trigger: "TransferPosted", at: "2026-11-05T09:30:02.412Z" },
  ],
  partnerCalls: [],
  auditTrail: [],
  revealed: false,
} as TxnDetail;

export const pendingReversal: ChangeRequest = {
  id: "0193a1b2-0000-7000-8000-000000000001", resourceType: "transaction", resourceId: TXN_ID, action: "REVERSE",
  payload: {}, diff: { before: { status: "COMPLETED" }, after: { status: "REVERSING" } },
  makerId: "seed-ops-maker", reason: "duplicate debit", status: "PENDING",
  createdAt: "2026-11-05T10:00:00Z", expiresAt: "2026-11-06T10:00:00Z",
} as ChangeRequest;

export const page = (items: ChangeRequest[]): ChangeRequestPage => ({ items, limit: 1, offset: 0 }) as ChangeRequestPage;

/** What APISIX / mw-admin-api answer while the change-request list is down. */
export const unavailable: ErrorEnvelope = {
  code: "MW-SYS-001", message: "Service unavailable", correlationId: "c0ffee00-1111-4222-8333-444455556666",
  retryable: true, details: [],
};
