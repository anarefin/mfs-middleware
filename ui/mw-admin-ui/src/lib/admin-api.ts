// Client of mw-admin-api (contract api/mw-admin-api/admin.v1), used server-side only (route
// handlers and server components): the operator's access token is sent from this server, never
// from the browser (spec 15.1). The wiring with the token and base URL is src/lib/server/admin.ts.

import type { ApiResult, ErrorEnvelope, TxnDetail, TxnPage } from "./types";

export type AdminApiOptions = { baseUrl: string; fetch?: typeof fetch; timeoutMs?: number };

/** Our own code when the admin API could not be reached or did not answer with an envelope. */
export const UNREACHABLE = "MW-SYS-041";

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

  async function get<T>(token: string, path: string): Promise<ApiResult<T>> {
    const correlationId = crypto.randomUUID();
    let res: Response;
    try {
      res = await doFetch(`${base}${path}`, {
        method: "GET",
        headers: { authorization: `Bearer ${token}`, accept: "application/json", "x-correlation-id": correlationId },
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
  };
}

export type AdminApi = ReturnType<typeof createAdminApi>;
