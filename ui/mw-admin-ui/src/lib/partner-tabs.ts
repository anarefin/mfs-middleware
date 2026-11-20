// Partner detail tabs (ticket 131c): what the forms may send, checked here as admin.v1 does so a
// malformed form never reaches mw-admin-api. A credential's secret is never put in an error
// (the issue text is fixed); the upload itself is write-only and the page never renders it back.

import type { CredentialType, PartnerConfig } from "./types";

export const PARTNER_TABS = [
  { id: "config", label: "Config" },
  { id: "maintenance", label: "Maintenance" },
  { id: "endpoint", label: "Endpoint (mock/real)" },
  { id: "credentials", label: "Credentials" },
  { id: "error-codes", label: "Error codes" },
] as const;

export type PartnerTab = (typeof PARTNER_TABS)[number]["id"];

export function partnerTab(raw: string | undefined): PartnerTab {
  return PARTNER_TABS.some((t) => t.id === raw) ? (raw as PartnerTab) : "config";
}

export function partnerTabHref(partnerId: string, tab: PartnerTab): string {
  return tab === "config" ? `/partners/${partnerId}` : `/partners/${partnerId}?tab=${tab}`;
}

export type ParseResult<T> = { ok: true; value: T } | { ok: false; field: string; issue: string };

const fail = <T>(field: string, issue: string): ParseResult<T> => ({ ok: false, field, issue });

/** A datetime-local value, read as UTC (every time in the console is UTC). */
function utcInstant(raw: string): string | null {
  const d = new Date(/[zZ]|[+-]\d\d:\d\d$/.test(raw) ? raw : `${raw}Z`);
  return Number.isNaN(d.getTime()) ? null : d.toISOString();
}

export type MaintenanceDraft = {
  maintenance: boolean;
  maintenanceWindow?: { start?: string; end?: string; messageEn?: string; messageBn?: string };
};

/**
 * SET_MAINTENANCE: the flag (`maintenance` is the clicked button, on or off) and/or a window.
 * An empty window is left out; the API keeps the held window when the field is absent, so ending
 * one is a new window whose end is in the past.
 */
export function parseMaintenance(input: {
  maintenance?: string;
  start?: string;
  end?: string;
  messageEn?: string;
  messageBn?: string;
}): ParseResult<MaintenanceDraft> {
  if (input.maintenance !== "true" && input.maintenance !== "false") {
    return fail("maintenance", "on or off");
  }
  const window: NonNullable<MaintenanceDraft["maintenanceWindow"]> = {};
  const start = input.start?.trim();
  const end = input.end?.trim();
  if (start) {
    const iso = utcInstant(start);
    if (!iso) return fail("start", "a date and time (UTC)");
    window.start = iso;
  }
  if (end) {
    const iso = utcInstant(end);
    if (!iso) return fail("end", "a date and time (UTC)");
    window.end = iso;
  }
  if (window.start && window.end && window.end <= window.start) return fail("end", "must be after start");
  for (const key of ["messageEn", "messageBn"] as const) {
    const message = input[key]?.trim();
    if (!message) continue;
    if (message.length > 500) return fail(key, "1 to 500 characters");
    window[key] = message;
  }
  const hasWindow = Object.keys(window).length > 0;
  return {
    ok: true,
    value: { maintenance: input.maintenance === "true", ...(hasWindow ? { maintenanceWindow: window } : {}) },
  };
}

/** admin.v1 PartnerEndpoints: http(s), host, port and plain path segments. */
const ENDPOINT_URL = /^https?:\/\/[A-Za-z0-9.-]{1,253}(:[0-9]{1,5})?(\/[A-Za-z0-9._~-]+)*\/?$/;

export type EndpointDraft = { endpoint: "MOCK" | "REAL"; endpoints: { MOCK: string; REAL: string } };

/** SET_ENDPOINT: both profiles, and the selected one's host is in the partner's allowed hosts. */
export function parseEndpoint(
  input: { endpoint?: string; mockUrl?: string; realUrl?: string; allowedHosts?: string },
): ParseResult<EndpointDraft> {
  if (input.endpoint !== "MOCK" && input.endpoint !== "REAL") return fail("endpoint", "MOCK or REAL");
  const hosts = (input.allowedHosts ?? "").split(",").map((h) => h.trim()).filter(Boolean);
  const urls = { MOCK: input.mockUrl?.trim() ?? "", REAL: input.realUrl?.trim() ?? "" };
  for (const profile of ["MOCK", "REAL"] as const) {
    const url = urls[profile];
    if (!url || !ENDPOINT_URL.test(url) || url.includes("/../") || url.endsWith("/..")) {
      return fail(profile === "MOCK" ? "mockUrl" : "realUrl", "an http(s) URL of host, port and plain path segments");
    }
    const host = new URL(url).hostname;
    if (hosts.length > 0 && !hosts.some((h) => h.toLowerCase() === host.toLowerCase())) {
      return fail(profile === "MOCK" ? "mockUrl" : "realUrl", "its host must be in allowedHosts");
    }
  }
  return { ok: true, value: { endpoint: input.endpoint, endpoints: urls } };
}

const CODE = /^[A-Za-z0-9_-]{1,64}$/;
const SUFFIX = /^[A-Z0-9_]{1,64}$/;

export type ErrorMapDraft = {
  errorMap: Record<string, { suffix: string; retryable?: boolean }>;
  ambiguousCodes: string[];
};

/** One line `CODE SUFFIX` or `CODE SUFFIX true`. `retryable: false` is left out (the contract's default). */
export function formatErrorMap(map: PartnerConfig["errorMap"]): string {
  if (!map) return "";
  return Object.entries(map).map(([code, m]) => (m.retryable ? `${code} ${m.suffix} true` : `${code} ${m.suffix}`)).join("\n");
}

export function formatAmbiguous(codes: string[] | undefined): string {
  return (codes ?? []).join(", ");
}

/** SET_ERROR_MAP: the whole map, plus the ambiguous codes (empty clears them). */
export function parseErrorMap(input: { errorMap?: string; ambiguousCodes?: string }): ParseResult<ErrorMapDraft> {
  const errorMap: ErrorMapDraft["errorMap"] = {};
  const lines = (input.errorMap ?? "").split(/\r?\n/);
  for (const raw of lines) {
    const line = raw.trim();
    if (!line) continue;
    const parts = line.split(/\s+/);
    if (parts.length < 2 || parts.length > 3) return fail("errorMap", "one line per code: CODE SUFFIX, or CODE SUFFIX true");
    const [code, suffix, flag] = parts;
    if (!code || !CODE.test(code)) return fail("errorMap", "a partner code is 1 to 64 letters, digits, _ or -");
    if (!suffix || !SUFFIX.test(suffix)) return fail("errorMap", "suffix is A-Z, 0-9 and _");
    if (flag !== undefined && flag !== "true" && flag !== "false") return fail("errorMap", "retryable is true or false");
    if (errorMap[code]) return fail("errorMap", `${code} is listed twice`);
    errorMap[code] = flag === "true" ? { suffix, retryable: true } : { suffix };
  }
  const ambiguousCodes: string[] = [];
  for (const raw of (input.ambiguousCodes ?? "").split(/[\s,]+/)) {
    if (!raw) continue;
    if (raw.length > 64) return fail("ambiguousCodes", "a code is at most 64 characters");
    ambiguousCodes.push(raw);
  }
  return { ok: true, value: { errorMap, ambiguousCodes } };
}

const CREDENTIAL_NAME = /^[a-z0-9][a-z0-9-]{0,63}$/;
const CERT_NAME = /^[a-z0-9][a-z0-9-]{0,45}$/;
const TYPES: readonly CredentialType[] = ["API_KEY", "HMAC_SECRET", "CLIENT_CERT"];

export type CredentialDraft = {
  name: string;
  type: CredentialType;
  secret?: string;
  certificatePem?: string;
  privateKeyPem?: string;
  pkcs12?: string;
  pkcs12Password?: string;
  notAfter?: string;
};

/**
 * UPLOAD. The returned issue never contains what was typed: a short secret is "16 to 4096
 * characters", not an echo of the secret. `now` decides whether notAfter is still in the future.
 */
export function parseCredentialUpload(input: {
  name?: string;
  type?: string;
  secret?: string;
  certificatePem?: string;
  privateKeyPem?: string;
  pkcs12?: string;
  pkcs12Password?: string;
  notAfter?: string;
}, now: Date = new Date()): ParseResult<CredentialDraft> {
  const name = input.name?.trim() ?? "";
  if (!CREDENTIAL_NAME.test(name)) return fail("name", "lower-case letters, digits and hyphens, at most 64");
  if (!input.type || !(TYPES as readonly string[]).includes(input.type)) return fail("type", "API_KEY, HMAC_SECRET or CLIENT_CERT");
  const type = input.type as CredentialType;
  if (type === "CLIENT_CERT") {
    if (!CERT_NAME.test(name)) return fail("name", "at most 46 characters for a certificate");
    const pem = Boolean(input.certificatePem?.trim() || input.privateKeyPem?.trim());
    const p12 = Boolean(input.pkcs12?.trim() || input.pkcs12Password?.trim());
    if (pem && p12) return fail("certificatePem", "PEM or PKCS#12, not both");
    if (!pem && !p12) return fail("certificatePem", "a PEM certificate and its key, or a PKCS#12 and its password");
    if (pem) {
      if (!input.certificatePem?.trim() || !input.privateKeyPem?.trim()) {
        return fail("privateKeyPem", "a PEM certificate and its PKCS#8 key");
      }
      return { ok: true, value: { name, type, certificatePem: input.certificatePem, privateKeyPem: input.privateKeyPem } };
    }
    if (!input.pkcs12?.trim() || !input.pkcs12Password?.trim()) {
      return fail("pkcs12", "a PKCS#12 (base64) and its password");
    }
    return { ok: true, value: { name, type, pkcs12: input.pkcs12, pkcs12Password: input.pkcs12Password } };
  }
  const secret = input.secret ?? "";
  if (secret.length < 16 || secret.length > 4096) return fail("secret", "16 to 4096 characters");
  const draft: CredentialDraft = { name, type, secret };
  const expiry = input.notAfter?.trim();
  if (expiry) {
    const iso = utcInstant(expiry);
    if (!iso) return fail("notAfter", "a date and time (UTC)");
    if (new Date(iso).getTime() <= now.getTime()) return fail("notAfter", "must be in the future");
    draft.notAfter = iso;
  }
  return { ok: true, value: draft };
}
