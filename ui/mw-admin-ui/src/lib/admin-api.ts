// Client of mw-admin-api (contract api/mw-admin-api/admin.v1), used server-side only (route
// handlers and server components): the operator's access token is sent from this server, never
// from the browser (spec 15.1). The wiring with the token and base URL is src/lib/server/admin.ts.

import type { NotifyRule, NotifyRuleProposal } from "./notify-rules";
import type { TemplateKey } from "./templates";
import type { Menu } from "./ussd-menu";
import type {
  ApiResult, ChangeRequest, ChangeRequestPage, ContentVersion, ContentVersionPage, CredentialPage, ErrorEnvelope, KeyCeremony, KeyPage, MenuPreview, Partner,
  PartnerAction, PartnerConfig, PartnerFamily, PartnerPage, PartnerTimeouts, TxnAction, TxnDetail, TxnPage, WalletAction,
} from "./types";

export type AdminApiOptions = { baseUrl: string; fetch?: typeof fetch; timeoutMs?: number };

export type PartnerChangeBody = {
  action: PartnerAction; reason: string; family?: PartnerFamily; timeouts?: PartnerTimeouts; config?: PartnerConfig;
};
/** POST /admin/v1/partners/{partnerId}/credentials/{name}/change-requests (ticket 131). Write-only. */
export type CredentialUploadBody = {
  action: "UPLOAD";
  reason: string;
  type: "API_KEY" | "HMAC_SECRET" | "CLIENT_CERT";
  secret?: string;
  certificatePem?: string;
  privateKeyPem?: string;
  pkcs12?: string;
  pkcs12Password?: string;
  notAfter?: string;
};
/** POST /admin/v1/partners/{partnerId}/error-map/change-requests (ticket 131). */
export type ErrorMapBody = {
  action: "SET_ERROR_MAP";
  reason: string;
  errorMap: NonNullable<PartnerConfig["errorMap"]>;
  ambiguousCodes: string[];
};
export type MenuProposal = { menu: Menu; activations: { mno: string; goLiveAt?: string }[]; reason: string };
/** POST /admin/v1/ussd/menus/preview (ticket 133b): a draft tree checked and walked on one MNO. */
export type MenuPreviewBody = {
  menu: Menu; menuId?: string; mno: string; language?: "bn" | "en"; inputs?: string[]; outcome?: "COMPLETED" | "FAILED" | "PROCESSING";
};
export type TemplateProposal = {
  body: string; subject?: string; htmlBody?: string; senderId?: string; validFrom?: string; reason: string;
};

const templatePath = (k: TemplateKey) =>
  `/admin/v1/notify-templates/${encodeURIComponent(k.code)}/${k.channel}/${k.locale}`;

/** Our own code when the admin API could not be reached or did not answer with an envelope. */
export const UNREACHABLE = "MW-SYS-041";

/** `POST /admin/v1/keys/change-requests` (ticket 75). */
export type KeyChangeBody = {
  action: "KEY_GENERATE" | "KEY_ROTATE" | "KEY_RETIRE";
  keyId: string;
  purpose?: string;
  version?: number;
  reason: string;
};

function isEnvelope(v: unknown): v is ErrorEnvelope {
  return typeof v === "object" && v !== null && typeof (v as ErrorEnvelope).code === "string"
    && typeof (v as ErrorEnvelope).message === "string";
}

function unreachable(status: number, correlationId: string): ApiResult<never> {
  return {
    ok: false,
    status,
    error: {
      code: UNREACHABLE, message: "The admin API is not available, try again", correlationId,
      retryable: true, details: [],
    },
  };
}

export function createAdminApi(opts: AdminApiOptions) {
  const base = opts.baseUrl.replace(/\/+$/, "");
  const doFetch = opts.fetch ?? fetch;
  const timeoutMs = opts.timeoutMs ?? 15_000;

  function get<T>(token: string, path: string): Promise<ApiResult<T>> {
    return call<T>(token, "GET", path);
  }

  function post<T>(token: string, path: string, body?: unknown): Promise<ApiResult<T>> {
    return call<T>(token, "POST", path, body);
  }

  async function call<T>(token: string, method: "GET" | "POST", path: string, json?: unknown): Promise<ApiResult<T>> {
    const correlationId = crypto.randomUUID();
    const headers: Record<string, string> = {
      authorization: `Bearer ${token}`, accept: "application/json", "x-correlation-id": correlationId,
    };
    if (json !== undefined) headers["content-type"] = "application/json";
    let res: Response;
    try {
      res = await doFetch(`${base}${path}`, {
        method,
        headers,
        ...(json !== undefined ? { body: JSON.stringify(json) } : {}),
        cache: "no-store",
        signal: AbortSignal.timeout(timeoutMs),
      });
    } catch {
      return unreachable(503, correlationId);
    }
    let body: unknown;
    try {
      body = await res.json();
    } catch {
      return unreachable(res.status >= 400 ? res.status : 502, correlationId);
    }
    if (res.ok) return { ok: true, data: body as T };
    return isEnvelope(body) ? { ok: false, status: res.status, error: body } : unreachable(res.status, correlationId);
  }

  return {
    searchTransactions(token: string, query: URLSearchParams): Promise<ApiResult<TxnPage>> {
      const q = query.toString();
      return get<TxnPage>(token, `/admin/v1/transactions${q ? `?${q}` : ""}`);
    },
    getTransaction(token: string, txnId: string, reveal: boolean): Promise<ApiResult<TxnDetail>> {
      const path = `/admin/v1/transactions/${encodeURIComponent(txnId)}${reveal ? "?reveal=true" : ""}`;
      return get<TxnDetail>(token, path);
    },
    listChangeRequests(token: string, query: URLSearchParams): Promise<ApiResult<ChangeRequestPage>> {
      const q = query.toString();
      return get<ChangeRequestPage>(token, `/admin/v1/change-requests${q ? `?${q}` : ""}`);
    },
    requestTxnAction(token: string, txnId: string, action: TxnAction, reason: string): Promise<ApiResult<ChangeRequest>> {
      return post<ChangeRequest>(token, `/admin/v1/transactions/${encodeURIComponent(txnId)}/change-requests`, { action, reason });
    },
    requestWalletAction(token: string, walletId: string, action: WalletAction, reason: string): Promise<ApiResult<ChangeRequest>> {
      return post<ChangeRequest>(token, `/admin/v1/wallets/${encodeURIComponent(walletId)}/change-requests`, { action, reason });
    },
    approveChangeRequest(token: string, id: string): Promise<ApiResult<ChangeRequest>> {
      return post<ChangeRequest>(token, `/admin/v1/change-requests/${encodeURIComponent(id)}/approve`);
    },
    rejectChangeRequest(token: string, id: string, reason: string): Promise<ApiResult<ChangeRequest>> {
      return post<ChangeRequest>(token, `/admin/v1/change-requests/${encodeURIComponent(id)}/reject`, { reason });
    },
    // keys and the key ceremony (ticket 75)
    listKeys(token: string): Promise<ApiResult<KeyPage>> {
      return get<KeyPage>(token, "/admin/v1/keys");
    },
    requestKeyChange(token: string, body: KeyChangeBody): Promise<ApiResult<ChangeRequest>> {
      return post<ChangeRequest>(token, "/admin/v1/keys/change-requests", body);
    },
    startKeyCeremony(token: string, body: { keyId: string; purpose: string; reason: string }): Promise<ApiResult<ChangeRequest>> {
      return post<ChangeRequest>(token, "/admin/v1/key-ceremonies", body);
    },
    getKeyCeremony(token: string, id: string): Promise<ApiResult<KeyCeremony>> {
      return get<KeyCeremony>(token, `/admin/v1/key-ceremonies/${encodeURIComponent(id)}`);
    },
    enterKeyComponent(token: string, id: string, component: string, checkValue: string): Promise<ApiResult<KeyCeremony>> {
      return post<KeyCeremony>(token, `/admin/v1/key-ceremonies/${encodeURIComponent(id)}/components`, { component, checkValue });
    },
    // partners (ticket 38)
    listPartners(token: string): Promise<ApiResult<PartnerPage>> {
      return get<PartnerPage>(token, "/admin/v1/partners");
    },
    getPartner(token: string, partnerId: string): Promise<ApiResult<Partner>> {
      return get<Partner>(token, `/admin/v1/partners/${encodeURIComponent(partnerId)}`);
    },
    requestPartnerChange(token: string, partnerId: string, body: PartnerChangeBody): Promise<ApiResult<ChangeRequest>> {
      return post<ChangeRequest>(token, `/admin/v1/partners/${encodeURIComponent(partnerId)}/change-requests`, body);
    },
    listCredentials(token: string, partnerId?: string): Promise<ApiResult<CredentialPage>> {
      const q = partnerId ? `?partnerId=${encodeURIComponent(partnerId)}` : "";
      return get<CredentialPage>(token, `/admin/v1/credentials${q}`);
    },
    listExpiringCredentials(token: string, days = 30): Promise<ApiResult<CredentialPage>> {
      return get<CredentialPage>(token, `/admin/v1/credentials/expiring?days=${days}`);
    },
    uploadCredential(token: string, partnerId: string, name: string, body: CredentialUploadBody): Promise<ApiResult<ChangeRequest>> {
      const path = `/admin/v1/partners/${encodeURIComponent(partnerId)}/credentials/${encodeURIComponent(name)}/change-requests`;
      return post<ChangeRequest>(token, path, body);
    },
    requestErrorMapChange(token: string, partnerId: string, body: ErrorMapBody): Promise<ApiResult<ChangeRequest>> {
      return post<ChangeRequest>(token, `/admin/v1/partners/${encodeURIComponent(partnerId)}/error-map/change-requests`, body);
    },
    // USSD menus (ticket 39)
    listMenuVersions(token: string, menuId: string, limit = 20): Promise<ApiResult<ContentVersionPage>> {
      return get<ContentVersionPage>(token, `/admin/v1/ussd-menus/${encodeURIComponent(menuId)}/versions?limit=${limit}`);
    },
    getMenuVersion(token: string, menuId: string, version: number): Promise<ApiResult<ContentVersion>> {
      return get<ContentVersion>(token, `/admin/v1/ussd-menus/${encodeURIComponent(menuId)}/versions/${version}`);
    },
    proposeMenu(token: string, menuId: string, body: MenuProposal): Promise<ApiResult<ChangeRequest>> {
      return post<ChangeRequest>(token, `/admin/v1/ussd-menus/${encodeURIComponent(menuId)}/change-requests`, body);
    },
    previewMenu(token: string, body: MenuPreviewBody): Promise<ApiResult<MenuPreview>> {
      return post<MenuPreview>(token, "/admin/v1/ussd/menus/preview", body);
    },
    // notification templates (ticket 39)
    listTemplateVersions(token: string, k: TemplateKey, limit = 20): Promise<ApiResult<ContentVersionPage>> {
      return get<ContentVersionPage>(token, `${templatePath(k)}/versions?limit=${limit}`);
    },
    getTemplateVersion(token: string, k: TemplateKey, version: number): Promise<ApiResult<ContentVersion>> {
      return get<ContentVersion>(token, `${templatePath(k)}/versions/${version}`);
    },
    proposeTemplate(token: string, k: TemplateKey, body: TemplateProposal): Promise<ApiResult<ChangeRequest>> {
      return post<ChangeRequest>(token, `${templatePath(k)}/change-requests`, body);
    },
    // notification rules (ticket 133c)
    listNotifyRules(token: string): Promise<ApiResult<{ items: NotifyRule[] }>> {
      return get<{ items: NotifyRule[] }>(token, "/admin/v1/notify-rules");
    },
    proposeNotifyRule(token: string, ruleId: string, body: NotifyRuleProposal): Promise<ApiResult<ChangeRequest>> {
      return post<ChangeRequest>(token, `/admin/v1/notify-rules/${encodeURIComponent(ruleId)}/change-requests`, body);
    },
  };
}

export type AdminApi = ReturnType<typeof createAdminApi>;
