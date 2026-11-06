// The fault case the Playwright mock used to play (`/__test/fail-change-request-list`, commit 0da2ea9):
// the transaction loads but the check for an open request fails. With fetch mocked, the detail
// loader and the actions area must offer no button, only the note. Fake values only.
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import { createAdminApi } from "@/lib/admin-api";
import { completedP2p, page, pendingReversal, TXN_ID, unavailable } from "@/lib/testing/admin-fixtures";
import { loadTxnView } from "@/lib/txn-view";
import { TxnActions } from "./txn-actions";

const MAKER = ["viewer", "ops-maker"];

/** mw-admin-api behind a mocked fetch: the detail answers 200, the change-request list `list`. */
function api(list: { status: number; body: unknown }) {
  const fetch = vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    const answer = url.includes("/change-requests") ? list : { status: 200, body: completedP2p };
    return new Response(JSON.stringify(answer.body), { status: answer.status, headers: { "content-type": "application/json" } });
  });
  return { fetch, api: createAdminApi({ baseUrl: "http://apisix:9080", fetch: fetch as unknown as typeof globalThis.fetch }) };
}

async function render(list: { status: number; body: unknown }, roles = MAKER) {
  const { api: a, fetch } = api(list);
  const view = await loadTxnView(a, "fake-token", TXN_ID, roles, false);
  if (!view.ok) throw new Error(`detail failed: ${view.error.code}`);
  return { view, fetch, html: renderToStaticMarkup(<TxnActions gate={view.gate} action={async () => null} />) };
}

describe("transaction actions when the open-request check fails", () => {
  it("shows no action buttons, only the note", async () => {
    const { view, fetch, html } = await render({ status: 503, body: unavailable });
    expect(view.data.transaction.status).toBe("COMPLETED");
    expect(String(fetch.mock.calls[1]?.[0])).toBe(
      `http://apisix:9080/admin/v1/change-requests?resourceId=${TXN_ID}&status=PENDING&limit=1`,
    );
    expect(html).toContain('data-testid="pending-check-failed"');
    expect(html).toContain("Could not check for open requests");
    expect(html).not.toContain('aria-label="Actions"');
    expect(html).not.toContain("Request reversal");
  });

  it("offers the reversal once the check found no open request", async () => {
    const { html } = await render({ status: 200, body: page([]) });
    expect(html).toContain('aria-label="Actions"');
    expect(html).toContain("Request reversal");
    expect(html).not.toContain("pending-check-failed");
  });

  it("shows the open request instead of the buttons", async () => {
    const { html } = await render({ status: 200, body: page([pendingReversal]) });
    expect(html).toContain('data-testid="pending-request"');
    expect(html).toContain("REVERSE requested by seed-ops-maker");
    expect(html).not.toContain("Request reversal");
  });

  it("tells a viewer nothing about a failed check and offers nothing", async () => {
    const { html } = await render({ status: 503, body: unavailable }, ["viewer"]);
    expect(html).not.toContain("pending-check-failed");
    expect(html).not.toContain("Request reversal");
  });
});
