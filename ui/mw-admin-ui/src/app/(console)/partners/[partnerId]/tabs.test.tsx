import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { expiringCredential } from "@/lib/testing/admin-fixtures";
import type { ChangeRequest, PartnerConfig } from "@/lib/types";
import { CredentialsPanel, EndpointPanel, ErrorCodesPanel, MaintenancePanel, PartnerTabNav } from "./tabs";

const config: PartnerConfig = {
  enabled: true,
  allowedHosts: ["mw-simulators"],
  maintenance: false,
  maintenanceWindow: {
    start: "2020-01-01T00:00:00.000Z", end: "2020-01-02T00:00:00.000Z", messageEn: "e2e window idle", messageBn: "ই২ই",
  },
  endpoint: "MOCK",
  endpoints: { MOCK: "http://mw-simulators:8080", REAL: "http://mw-simulators:8080/ocs" },
  errorMap: { "2001": { suffix: "TOPUP_REJECTED" } },
  ambiguousCodes: ["9876"],
};

const pending: ChangeRequest = {
  id: "0193a1b2-0000-7000-8000-000000000131", resourceType: "partner", resourceId: "ocs", action: "SET_MAINTENANCE",
  payload: {}, diff: { before: {}, after: {} }, makerId: "seed-ops-maker", status: "PENDING", reason: "window",
  createdAt: "2026-11-20T01:00:00Z", expiresAt: "2026-11-23T01:00:00Z",
};

const SECRET = "leaked-credential-value";

describe("partner detail tabs", () => {
  it("links Maintenance, Endpoint (mock/real), Credentials and Error codes", () => {
    const html = renderToStaticMarkup(<PartnerTabNav partnerId="ocs" tab="maintenance" />);
    expect(html).toContain('href="/partners/ocs?tab=maintenance"');
    expect(html).toContain("Maintenance");
    expect(html).toContain("Endpoint (mock/real)");
    expect(html).toContain("Credentials");
    expect(html).toContain("Error codes");
    expect(html).toContain('aria-current="page"');
  });

  it("shows the maintenance window and, for a maker with nothing pending, the on and off requests", () => {
    const html = renderToStaticMarkup(<MaintenancePanel config={config} maker />);
    expect(html).toContain('data-testid="maintenance-state"');
    expect(html).toContain("off");
    expect(html).toContain("e2e window idle");
    expect(html).toContain("Request maintenance on");
    expect(html).toContain("Request maintenance off");
  });

  it("replaces the maintenance form with the open request", () => {
    const html = renderToStaticMarkup(<MaintenancePanel config={config} maker pending={pending} />);
    expect(html).toContain('data-testid="pending-request"');
    expect(html).toContain("SET_MAINTENANCE requested by seed-ops-maker");
    expect(html).not.toContain("Request maintenance on");
  });

  it("shows a viewer the endpoint profile and neither form", () => {
    const html = renderToStaticMarkup(<EndpointPanel config={config} maker={false} />);
    expect(html).toContain('data-testid="endpoint-profile"');
    expect(html).toContain("MOCK");
    expect(html).toContain("http://mw-simulators:8080/ocs");
    expect(html).not.toContain("Request endpoint");
  });

  it("lists credential metadata and hides the upload from an ops-maker", () => {
    const html = renderToStaticMarkup(<CredentialsPanel result={{ ok: true, data: { items: [expiringCredential] } }} canUpload={false} />);
    expect(html).toContain("hmac-secret");
    expect(html).toContain("hmac-secret-v1");
    expect(html).toContain(expiringCredential.fingerprint);
    expect(html).toContain("An integration-maker uploads a credential");
    expect(html).not.toContain("Upload credential");
    expect(html).not.toContain('type="password"');
    expect(html).not.toContain(SECRET);
  });

  it("offers the upload form to an integration-maker with the secret field empty", () => {
    const html = renderToStaticMarkup(<CredentialsPanel result={{ ok: true, data: { items: [] } }} canUpload />);
    expect(html).toContain("No credentials stored.");
    expect(html).toContain('aria-label="Upload credential"');
    expect(html).toContain('name="secret"');
    expect(html).toContain('type="password"');
    expect(html).not.toMatch(/name="secret"[^>]*value=/);
    expect(html).not.toContain(SECRET);
  });

  it("shows the error map a maker will replace", () => {
    const html = renderToStaticMarkup(<ErrorCodesPanel config={config} maker />);
    expect(html).toContain('data-testid="error-map"');
    expect(html).toContain("2001 TOPUP_REJECTED");
    expect(html).toContain("9876");
    expect(html).toContain("Request error map");
  });
});
