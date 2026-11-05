// Shapes of contracts/api/mw-admin-api/admin.v1.openapi.yaml as this console reads them (the
// fields it shows; unknown fields are ignored). Keep in sync with the contract:
// src/lib/contract.test.ts checks the Playwright fixtures against it.

export type Amount = { valueMinor: number; currency: string };
export type Party = { type?: string; id?: string; name?: string; walletType?: string };

export type Txn = {
  txnId: string;
  txnType: string;
  channel: string;
  status: string;
  previousStatus?: string;
  createdAt: string;
  updatedAt: string;
  correlationId?: string;
  amount: Amount;
  fee?: Amount;
  initiator?: Party;
  debitParty?: Party;
  creditParty?: Party;
  partner?: { partnerId?: string; productCode?: string; billerCode?: string };
  partnerRef?: string;
  coreRef?: string;
  originalTxnId?: string;
  reversalTxnId?: string;
  error?: { code?: string; message?: string; messageBn?: string; retryable?: boolean };
  attributes?: Record<string, string>;
};

export type TxnPage = { items: Txn[]; limit: number; offset: number };

export type TimelineEntry = {
  kind: "STATE" | "CORE";
  from?: string;
  to?: string;
  trigger?: string;
  command?: string;
  commandId?: string;
  sends?: number;
  result?: string;
  outcome?: "SUCCESS" | "FAILURE";
  reasonCode?: string;
  at: string;
  resultAt?: string;
};

export type PartnerCall = {
  requestId: string;
  operation: string;
  family: string;
  partnerId?: string;
  sentAt: string;
  outcome?: string;
  partnerRef?: string;
  errorCode?: string;
  answeredAt?: string;
};

export type AuditEntry = {
  auditId: string;
  at: string;
  correlationId: string;
  actor: { type: string; id: string };
  action: string;
  resource: string;
  outcome: string;
  service: string;
  txnId?: string;
  sourceIp?: string;
};

export type TxnDetail = {
  transaction: Txn;
  timeline: TimelineEntry[];
  partnerCalls: PartnerCall[];
  auditTrail: AuditEntry[];
  revealed: boolean;
};

export type TxnAction = "INQUIRE" | "FORCE_COMMIT" | "FORCE_RELEASE" | "REVERSE";
export type WalletAction = "UNLOCK";
export type ChangeRequestStatus = "PENDING" | "APPROVED" | "REJECTED" | "EXPIRED";

export type PartnerAction = "ENABLE" | "DISABLE" | "SET_TIMEOUTS" | "UPSERT";
export type ContentAction = "PUBLISH";
export type ResourceType = "transaction" | "wallet" | "partner" | "ussd_menu" | "notify_template";

/**
 * One side of a change request's diff: a transaction / wallet `status`; a partner's changed config
 * fields; a menu / template `configKey` and `version`.
 */
export type ChangeSide = { status?: string; configKey?: string; version?: number } & Record<string, unknown>;

/** An `admin.change_request` (spec 15.3, tickets 37, 38, 39, 52); a wallet's resourceId arrives masked. */
export type ChangeRequest = {
  id: string;
  resourceType: ResourceType;
  resourceId: string;
  action: TxnAction | WalletAction | PartnerAction | ContentAction;
  payload: Record<string, unknown>;
  diff: { before: ChangeSide; after: ChangeSide };
  makerId: string;
  status: ChangeRequestStatus;
  checkerId?: string;
  reason: string;
  decisionReason?: string;
  createdAt: string;
  decidedAt?: string;
  expiresAt: string;
};

export type ChangeRequestPage = { items: ChangeRequest[]; limit: number; offset: number };

// --- partners (ticket 38) ---
export type PartnerFamily = "telco" | "billpay" | "bank";
export type PartnerTimeouts = { connectTimeoutMs?: number; timeoutMs?: number; readTimeoutMs?: number };
export type PartnerOperation = { method: string; path: string; definiteStatuses?: number[]; request?: string; response: string };
/** The adapters' PartnerConfig (never a secret: `auth.credentialsRef` names one). */
export type PartnerConfig = PartnerTimeouts & {
  enabled?: boolean;
  template?: string;
  baseUrl: string;
  headers?: Record<string, string>;
  auth?: { scheme?: string; credentialsRef?: string; params?: Record<string, string> };
  operations: Record<string, PartnerOperation>;
  errorMap?: Record<string, { suffix: string; retryable?: boolean }>;
  ambiguousCodes?: string[];
  sensitiveFields?: Record<string, string>;
  resilience?: string;
};
export type Partner = {
  partnerId: string;
  family: PartnerFamily;
  version: number;
  config: PartnerConfig;
  updatedAt: string;
  updatedBy: string;
  approvedBy?: string;
};
export type PartnerPage = { items: Partner[] };

// --- USSD menus and notification templates (ticket 39) ---
export type ContentVersion = {
  configKey: string;
  version: number;
  status: "PENDING" | "PUBLISHED" | "REJECTED" | "EXPIRED";
  changeRequestId: string;
  makerId: string;
  checkerId?: string;
  createdAt: string;
  decidedAt?: string;
  value?: Record<string, unknown>;
};
export type ContentVersionPage = { items: ContentVersion[]; limit: number; offset: number };

export type ErrorEnvelope = {
  code: string;
  message: string;
  messageBn?: string;
  txnId?: string;
  correlationId: string;
  retryable: boolean;
  details: { field?: string; issue?: string }[];
};

export type ApiResult<T> = { ok: true; data: T } | { ok: false; status: number; error: ErrorEnvelope };
