import { describe, expect, it } from "vitest";
import {
  formatAmbiguous, formatErrorMap, parseCredentialUpload, parseEndpoint, parseErrorMap, parseMaintenance, partnerTab, partnerTabHref,
} from "./partner-tabs";

const NOW = new Date("2026-11-20T00:00:00.000Z");
const SECRET = "leaked-credential-value";

describe("partner tabs", () => {
  it("names the four sections and keeps an unknown tab on Config", () => {
    expect(partnerTab(undefined)).toBe("config");
    expect(partnerTab("nope")).toBe("config");
    expect(partnerTabHref("ocs", "config")).toBe("/partners/ocs");
    expect(partnerTabHref("ocs", "maintenance")).toBe("/partners/ocs?tab=maintenance");
    expect(partnerTabHref("ocs", "endpoint")).toBe("/partners/ocs?tab=endpoint");
    expect(partnerTabHref("ocs", "credentials")).toBe("/partners/ocs?tab=credentials");
    expect(partnerTabHref("ocs", "error-codes")).toBe("/partners/ocs?tab=error-codes");
  });
});

describe("maintenance form", () => {
  it("reads a window as UTC and the on/off button", () => {
    expect(parseMaintenance({
      maintenance: "true", start: "2026-12-01T00:00", end: "2026-12-01T06:00",
      messageEn: "top-up paused", messageBn: "টপআপ বন্ধ",
    })).toEqual({
      ok: true,
      value: {
        maintenance: true,
        maintenanceWindow: {
          start: "2026-12-01T00:00:00.000Z", end: "2026-12-01T06:00:00.000Z",
          messageEn: "top-up paused", messageBn: "টপআপ বন্ধ",
        },
      },
    });
  });

  it("turning the flag off needs no window", () => {
    expect(parseMaintenance({ maintenance: "false" })).toEqual({ ok: true, value: { maintenance: false } });
  });

  it("refuses an end that is not after the start", () => {
    expect(parseMaintenance({ maintenance: "true", start: "2026-12-01T06:00", end: "2026-12-01T00:00" })).toEqual({
      ok: false, field: "end", issue: "must be after start",
    });
  });
});

describe("endpoint form", () => {
  it("accepts MOCK and REAL when both hosts are allowed", () => {
    expect(parseEndpoint({
      endpoint: "REAL", mockUrl: "http://mw-simulators:8080", realUrl: "http://mw-simulators:8080/ocs",
      allowedHosts: "mw-simulators",
    })).toEqual({
      ok: true,
      value: { endpoint: "REAL", endpoints: { MOCK: "http://mw-simulators:8080", REAL: "http://mw-simulators:8080/ocs" } },
    });
  });

  it("refuses a host that is not allowed, without echoing the url's path as the problem", () => {
    const refused = parseEndpoint({
      endpoint: "MOCK", mockUrl: "http://evil.example/secret-path", realUrl: "http://mw-simulators:8080",
      allowedHosts: "mw-simulators",
    });
    expect(refused).toEqual({ ok: false, field: "mockUrl", issue: "its host must be in allowedHosts" });
  });
});

describe("error-code form", () => {
  it("reads one line per code and leaves retryable out unless it is true", () => {
    const parsed = parseErrorMap({ errorMap: "2001 TOPUP_REJECTED\n4099 TIMEOUT true\n5001 SLOW false\n", ambiguousCodes: "9876, 1111" });
    expect(parsed).toEqual({
      ok: true,
      value: {
        errorMap: {
          "2001": { suffix: "TOPUP_REJECTED" },
          "4099": { suffix: "TIMEOUT", retryable: true },
          "5001": { suffix: "SLOW" },
        },
        ambiguousCodes: ["9876", "1111"],
      },
    });
    if (!parsed.ok) return;
    expect(formatErrorMap(parsed.value.errorMap)).toBe("2001 TOPUP_REJECTED\n4099 TIMEOUT true\n5001 SLOW");
    expect(formatAmbiguous(["9876"])).toBe("9876");
  });

  it("refuses a suffix that is not the canonical shape", () => {
    expect(parseErrorMap({ errorMap: "2001 bad-suffix", ambiguousCodes: "" })).toEqual({
      ok: false, field: "errorMap", issue: "suffix is A-Z, 0-9 and _",
    });
  });
});

describe("credential upload form", () => {
  it("keeps an API key and a future expiry, and a certificate as PEM or PKCS#12", () => {
    expect(parseCredentialUpload({
      name: "hmac-secret", type: "HMAC_SECRET", secret: SECRET, notAfter: "2026-12-01T00:00",
    }, NOW)).toEqual({
      ok: true,
      value: { name: "hmac-secret", type: "HMAC_SECRET", secret: SECRET, notAfter: "2026-12-01T00:00:00.000Z" },
    });
    expect(parseCredentialUpload({
      name: "client-tls", type: "CLIENT_CERT", certificatePem: "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----",
      privateKeyPem: "-----BEGIN PRIVATE KEY-----\nMIIB\n-----END PRIVATE KEY-----",
    }, NOW)).toMatchObject({ ok: true, value: { name: "client-tls", type: "CLIENT_CERT" } });
  });

  it("refuses a short secret without repeating it", () => {
    const refused = parseCredentialUpload({ name: "hmac-secret", type: "API_KEY", secret: SECRET.slice(0, 4) }, NOW);
    expect(refused).toEqual({ ok: false, field: "secret", issue: "16 to 4096 characters" });
    expect(JSON.stringify(refused)).not.toContain(SECRET.slice(0, 4));
  });

  it("refuses an expiry that is not in the future", () => {
    expect(parseCredentialUpload({
      name: "hmac-secret", type: "API_KEY", secret: SECRET, notAfter: "2020-01-01T00:00",
    }, NOW)).toEqual({ ok: false, field: "notAfter", issue: "must be in the future" });
  });
});
