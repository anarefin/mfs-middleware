import { describe, expect, it } from "vitest";
import { createAdminApi } from "./admin-api";

type Seen = { url: string; headers: Headers };

function fakeFetch(status: number, body: unknown, seen: Seen[] = []): typeof fetch {
  return (async (input: RequestInfo | URL, init?: RequestInit) => {
    seen.push({ url: String(input), headers: new Headers(init?.headers) });
    return new Response(typeof body === "string" ? body : JSON.stringify(body), {
      status,
      headers: { "content-type": "application/json" },
    });
  }) as typeof fetch;
}

const TXN = "3f1c2b9e-7a4d-4c1e-9b8a-2d6f0e5a1c33";
const envelope = {
  code: "MW-VAL-003", message: "Transaction not found", correlationId: "c-1", retryable: false, details: [],
};

describe("admin API client", () => {
  it("searches with the operator's bearer token and a correlation id", async () => {
    const seen: Seen[] = [];
    const api = createAdminApi({ baseUrl: "http://apisix:9080/", fetch: fakeFetch(200, { items: [], limit: 50, offset: 0 }, seen) });
    const r = await api.searchTransactions("tok-1", new URLSearchParams("msisdn=01712345678&limit=50"));
    expect(r).toEqual({ ok: true, data: { items: [], limit: 50, offset: 0 } });
    expect(seen[0]?.url).toBe("http://apisix:9080/admin/v1/transactions?msisdn=01712345678&limit=50");
    expect(seen[0]?.headers.get("authorization")).toBe("Bearer tok-1");
    expect(seen[0]?.headers.get("x-correlation-id")).toMatch(/^[0-9a-f-]{36}$/);
  });

  it("asks for the detail, with reveal only when requested", async () => {
    const seen: Seen[] = [];
    const api = createAdminApi({ baseUrl: "http://x", fetch: fakeFetch(200, { revealed: false }, seen) });
    await api.getTransaction("t", TXN, false);
    await api.getTransaction("t", TXN, true);
    expect(seen.map((s) => s.url)).toEqual([
      `http://x/admin/v1/transactions/${TXN}`,
      `http://x/admin/v1/transactions/${TXN}?reveal=true`,
    ]);
  });

  it("hands back the error envelope of a refused call", async () => {
    const api = createAdminApi({ baseUrl: "http://x", fetch: fakeFetch(404, envelope) });
    expect(await api.getTransaction("t", TXN, false)).toEqual({ ok: false, status: 404, error: envelope });
  });

  it("makes an envelope up when the answer is not one (edge HTML, network down)", async () => {
    const html = createAdminApi({ baseUrl: "http://x", fetch: fakeFetch(502, "<html>bad gateway</html>") });
    const r = await html.searchTransactions("t", new URLSearchParams());
    expect(r).toMatchObject({ ok: false, status: 502, error: { code: "MW-SYS-041", retryable: true } });

    const down = createAdminApi({
      baseUrl: "http://x",
      fetch: (async () => { throw new TypeError("fetch failed"); }) as typeof fetch,
    });
    expect(await down.searchTransactions("t", new URLSearchParams())).toMatchObject({
      ok: false, status: 503, error: { code: "MW-SYS-041", retryable: true },
    });
  });
});

describe("admin API client, change requests (tickets 41, 52)", () => {
  type Sent = { url: string; method: string; headers: Headers; body: string | null };
  function recorder(status: number, body: unknown, sent: Sent[]): typeof fetch {
    return (async (input: RequestInfo | URL, init?: RequestInit) => {
      sent.push({
        url: String(input), method: init?.method ?? "GET", headers: new Headers(init?.headers),
        body: typeof init?.body === "string" ? init.body : null,
      });
      return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
    }) as typeof fetch;
  }
  const CR = "0193a1b2-0000-7000-8000-000000000001";

  it("files a transaction action and a wallet unlock as JSON POSTs with the bearer token", async () => {
    const sent: Sent[] = [];
    const api = createAdminApi({ baseUrl: "http://x", fetch: recorder(201, { id: CR }, sent) });
    expect(await api.requestTxnAction("tok", TXN, "REVERSE", "duplicate debit")).toEqual({ ok: true, data: { id: CR } });
    await api.requestWalletAction("tok", "01712345678", "UNLOCK", "customer called");
    expect(sent.map((s) => [s.method, s.url, s.body])).toEqual([
      ["POST", `http://x/admin/v1/transactions/${TXN}/change-requests`, JSON.stringify({ action: "REVERSE", reason: "duplicate debit" })],
      ["POST", "http://x/admin/v1/wallets/01712345678/change-requests", JSON.stringify({ action: "UNLOCK", reason: "customer called" })],
    ]);
    expect(sent[0]?.headers.get("authorization")).toBe("Bearer tok");
    expect(sent[0]?.headers.get("content-type")).toBe("application/json");
    expect(sent[0]?.headers.get("x-correlation-id")).toMatch(/^[0-9a-f-]{36}$/);
  });

  it("lists, approves (no body) and rejects (with a reason)", async () => {
    const sent: Sent[] = [];
    const api = createAdminApi({ baseUrl: "http://x", fetch: recorder(200, { items: [], limit: 50, offset: 0 }, sent) });
    await api.listChangeRequests("tok", new URLSearchParams("status=PENDING"));
    await api.approveChangeRequest("tok", CR);
    await api.rejectChangeRequest("tok", CR, "not justified");
    expect(sent.map((s) => [s.method, s.url, s.body])).toEqual([
      ["GET", "http://x/admin/v1/change-requests?status=PENDING", null],
      ["POST", `http://x/admin/v1/change-requests/${CR}/approve`, null],
      ["POST", `http://x/admin/v1/change-requests/${CR}/reject`, JSON.stringify({ reason: "not justified" })],
    ]);
  });

  it("hands back the envelope of a refused write", async () => {
    const own = { code: "MW-AUTH-030", message: "The maker cannot decide", correlationId: "c", retryable: false, details: [] };
    const api = createAdminApi({ baseUrl: "http://x", fetch: recorder(403, own, []) });
    expect(await api.approveChangeRequest("t", CR)).toEqual({ ok: false, status: 403, error: own });
  });
});
