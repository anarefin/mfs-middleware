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
