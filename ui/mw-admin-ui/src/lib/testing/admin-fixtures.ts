// Fake admin API answers for the Vitest component tests (never imported by the app; fake values only).
// src/lib/contract.test.ts validates each against contracts/api/mw-admin-api/admin.v1, so a green
// component test reads answers the real mw-admin-api may send.
import type { ChangeRequest, ChangeRequestPage, Credential, ErrorEnvelope, Key, KeyCeremony, KeyPage, TxnDetail } from "../types";

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
  auditAnchor: { status: "INTACT", lastAnchorDate: "2026-11-04", verifiedAt: "2026-11-05T00:30:00.000Z" },
  revealed: false,
} as TxnDetail;

export const pendingReversal: ChangeRequest = {
  id: "0193a1b2-0000-7000-8000-000000000001", resourceType: "transaction", resourceId: TXN_ID, action: "REVERSE",
  payload: {}, diff: { before: { status: "COMPLETED" }, after: { status: "REVERSING" } },
  makerId: "seed-ops-maker", reason: "duplicate debit", status: "PENDING",
  createdAt: "2026-11-05T10:00:00Z", expiresAt: "2026-11-06T10:00:00Z",
} as ChangeRequest;

/** An approved care PIN reset (ticket 70): wallet id masked, mw-auth's resetId in the payload. */
export const approvedPinReset: ChangeRequest = {
  id: "0193a1b2-0000-7000-8000-000000000070", resourceType: "pin_reset", resourceId: "0171****321", action: "PIN_RESET",
  payload: { unlockTxnId: "0193a1b2-0000-7000-8000-0000000000a0", resetId: "3f6b1c2d-4e5f-4a6b-9c7d-8e9f0a1b2c3d" },
  diff: { before: { status: "LOCKED" }, after: { status: "RESET" } },
  makerId: "seed-ops-maker", checkerId: "seed-ops-checker", reason: "lost phone, identity checked", status: "APPROVED",
  createdAt: "2026-11-05T10:00:00Z", decidedAt: "2026-11-05T10:05:00Z", expiresAt: "2026-11-08T10:00:00Z",
} as ChangeRequest;

export const page = (items: ChangeRequest[]): ChangeRequestPage => ({ items, limit: 1, offset: 0 }) as ChangeRequestPage;

/** What APISIX / mw-admin-api answer while the change-request list is down. */
export const unavailable: ErrorEnvelope = {
  code: "MW-SYS-001", message: "Service unavailable", correlationId: "c0ffee00-1111-4222-8333-444455556666",
  retryable: true, details: [],
};

// --- keys and the key ceremony (ticket 75) ---

export const pinHmacKey: Key = {
  keyId: "pin-hmac", purpose: "PIN_HMAC", algorithm: "HMAC_SHA256", provider: "pkcs11",
  versions: [
    { version: 1, kid: "pin-hmac.v1", state: "VERIFY_ONLY", createdAt: "2026-11-01T00:00:00Z", rotatedAt: "2026-11-02T00:00:00Z" },
    { version: 2, kid: "pin-hmac.v2", state: "ACTIVE", createdAt: "2026-11-02T00:00:00Z", rotatedAt: null },
  ],
};

export const keyPage: KeyPage = { items: [pinHmacKey] };

export const pendingKeyRotation: ChangeRequest = {
  id: "0193a1b2-0000-7000-8000-000000000075", resourceType: "key", resourceId: "pin-hmac", action: "KEY_ROTATE",
  payload: { keyId: "pin-hmac", purpose: "PIN_HMAC" },
  diff: { before: { key: "pin-hmac.v2 ACTIVE" }, after: { key: "pin-hmac.v3 ACTIVE" } },
  makerId: "seed-ops-secadmin1", reason: "yearly rotation", status: "PENDING",
  createdAt: "2026-11-15T10:00:00Z", expiresAt: "2026-11-18T10:00:00Z",
};

export const ceremonyOneShare: KeyCeremony = {
  changeRequestId: "0193a1b2-0000-7000-8000-000000000076", keyId: "pin-hmac", purpose: "PIN_HMAC", kid: "pin-hmac.v3",
  status: "PENDING", shares: [{ share: 1, custodian: "seed-ops-secadmin1", enteredAt: "2026-11-15T10:01:00Z" }],
};

export const ceremonyCombined: KeyCeremony = {
  ...ceremonyOneShare,
  shares: [...ceremonyOneShare.shares, { share: 2, custodian: "seed-ops-secadmin2", enteredAt: "2026-11-15T10:02:00Z" }],
  checkValue: "A1B2C3",
};

/** A credential inside the 30-day window (ticket 131). Metadata only: there is no secret field. */
export const expiringCredential: Credential = {
  credentialId: "ocs/hmac-secret",
  partnerId: "ocs",
  name: "hmac-secret",
  adapter: "mw-adapter-telco",
  type: "HMAC_SECRET",
  vaultFields: ["hmac-secret"],
  kid: "hmac-secret-v1",
  fingerprint: `sha256:${"ab".repeat(32)}`,
  notAfter: "2026-12-02T00:00:00.000Z",
  daysLeft: 12,
  version: 1,
  uploadedBy: "ops-e2e-maker01",
  approvedBy: "ops-e2e-checker01",
  uploadedAt: "2026-11-01T00:00:00.000Z",
};
