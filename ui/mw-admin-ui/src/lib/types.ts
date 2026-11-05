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
