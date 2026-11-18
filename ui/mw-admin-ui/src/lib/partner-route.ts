// Partner routes as config (ADR D26, ticket 83): the editor's fields -> the UPSERT_ROUTE config, and
// the route status label. The route itself is checked by mw-admin-api (a copy of the adapters'
// loader rules, MW-VAL-041 with line and construct); this only shapes the request as the contract does.
import type { PartnerConfig, PartnerRouteState } from "./types";

export type RouteFormFields = {
  route?: string;
  allowedHosts?: string;
  mappings?: string;
  dryRunOperation?: string;
  dryRunSample?: string;
  errorMap?: string;
  timeoutMs?: string;
};

export type RouteFormResult = { ok: true; config: PartnerConfig } | { ok: false; field: string; issue: string };

const HOST = /^[A-Za-z0-9.-]{1,253}$/;
const SUFFIX = /^[A-Z0-9_]+$/;
const OPERATIONS = ["PAY", "INQUIRY", "STATUS_QUERY"] as const;

function json(text: string | undefined): unknown {
  if (!text || !text.trim()) return undefined;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

const isObject = (v: unknown): v is Record<string, unknown> => typeof v === "object" && v !== null && !Array.isArray(v);

export function parseRouteForm(f: RouteFormFields): RouteFormResult {
  const fail = (field: string, issue: string): RouteFormResult => ({ ok: false, field, issue });
  if (!f.route || !f.route.trim()) return fail("route", "the route YAML");
  const hosts = (f.allowedHosts ?? "").split(/[\s,]+/).map((h) => h.trim()).filter((h) => h.length > 0);
  if (hosts.length === 0) return fail("allowedHosts", "at least one host");
  if (!hosts.every((h) => HOST.test(h))) return fail("allowedHosts", "host names only");
  const mappings = json(f.mappings);
  if (!isObject(mappings) || !Object.values(mappings).every((v) => typeof v === "string")) {
    return fail("mappings", "a JSON object of JSONata strings by name");
  }
  const operation = f.dryRunOperation ?? "";
  if (!(OPERATIONS as readonly string[]).includes(operation)) return fail("dryRun.operation", "PAY, INQUIRY or STATUS_QUERY");
  const raw = json(f.dryRunSample);
  const sample = raw === undefined ? {} : raw;
  if (!isObject(sample)) return fail("dryRun.sample", "a JSON object");
  const config: PartnerConfig = {
    route: f.route,
    allowedHosts: hosts,
    mappings: mappings as Record<string, string>,
    dryRun: { operation: operation as (typeof OPERATIONS)[number], sample },
  };
  const errorMap = json(f.errorMap);
  if (errorMap !== undefined) {
    const valid = isObject(errorMap) && Object.values(errorMap).every(
      (m) => isObject(m) && typeof m.suffix === "string" && SUFFIX.test(m.suffix)
        && (m.retryable === undefined || typeof m.retryable === "boolean"));
    if (!valid) return fail("errorMap", "code -> {suffix A-Z0-9_, retryable}");
    config.errorMap = errorMap as PartnerConfig["errorMap"];
  }
  if (f.timeoutMs && f.timeoutMs.trim()) {
    const t = Number(f.timeoutMs.trim());
    if (!Number.isInteger(t) || t < 1 || t > 120000) return fail("timeoutMs", "a whole number 1 to 120000");
    config.timeoutMs = t;
  }
  return { ok: true, config };
}

/** What the adapter made of the route version last approved. */
export function routeStatusLabel(route: PartnerRouteState | undefined): string {
  if (!route) return "seed (the adapter's own, never published)";
  switch (route.status) {
    case "PENDING_DRY_RUN":
      return `v${route.version} waiting for its dry-run`;
    case "LIVE":
      return `v${route.version} LIVE`;
    case "REJECTED":
      return route.liveVersion ? `v${route.version} REJECTED (v${route.liveVersion} still serves)` : `v${route.version} REJECTED`;
  }
}
