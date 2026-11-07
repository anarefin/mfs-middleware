import "server-only";
// The edge client: every call goes through APISIX like any client's (no direct service call), with
// a fresh X-Correlation-Id. `Trace` records each call as a step for the page.

import { redact } from "@/lib/redact";
import type { Step } from "@/lib/types";
import { e2eApiKey, edgeUrl } from "./env";

export type EdgeResponse = { status: number; body: unknown; raw: string; headers: Headers; ms: number };

export type CallOpts = {
  headers?: Record<string, string>;
  json?: unknown;
  raw?: string;
  contentType?: string;
  form?: Record<string, string>;
  /** the test-only key-auth header (routes under /test/*) */
  apikey?: boolean;
  timeoutMs?: number;
};

export class EdgeError extends Error {}

export async function edge(method: string, path: string, opts: CallOpts = {}): Promise<EdgeResponse> {
  const headers: Record<string, string> = { "X-Correlation-Id": crypto.randomUUID(), ...(opts.headers ?? {}) };
  if (opts.apikey) headers.apikey = e2eApiKey();
  let body: string | undefined;
  if (opts.form) {
    headers["Content-Type"] = "application/x-www-form-urlencoded";
    body = new URLSearchParams(opts.form).toString();
  } else if (opts.json !== undefined) {
    headers["Content-Type"] = "application/json";
    body = JSON.stringify(opts.json);
  } else if (opts.raw !== undefined) {
    headers["Content-Type"] = opts.contentType ?? "application/json";
    body = opts.raw;
  }
  const started = Date.now();
  let res: Response;
  try {
    res = await fetch(edgeUrl() + path, {
      method, headers, body, cache: "no-store", redirect: "manual",
      signal: AbortSignal.timeout(opts.timeoutMs ?? 40000),
    });
  } catch (e) {
    throw new EdgeError(`${method} ${path}: the edge did not answer (${(e as Error).message}); is the stack up (make up && make tester)?`);
  }
  const raw = await res.text();
  let parsed: unknown = null;
  try {
    parsed = raw ? JSON.parse(raw) : null;
  } catch {
    parsed = null;
  }
  return { status: res.status, body: parsed, raw, headers: res.headers, ms: Date.now() - started };
}

/** Records the calls of one action as steps. */
export class Trace {
  readonly steps: Step[] = [];

  async call(label: string, method: string, path: string, opts: CallOpts = {}, ok: number[] = [200]): Promise<EdgeResponse> {
    const r = await edge(method, path, opts);
    this.steps.push({
      label, call: `${method} ${path}`, status: r.status, ok: ok.includes(r.status),
      body: redact(r.body ?? (r.raw ? r.raw.slice(0, 2000) : undefined)), ms: r.ms,
    });
    return r;
  }

  note(label: string, note: string, ok = true, body?: unknown): void {
    this.steps.push({ label, note, ok, body: body === undefined ? undefined : redact(body) });
  }

  get failed(): boolean {
    return this.steps.some((s) => !s.ok);
  }
}

export const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** Calls fn until until(value) holds or timeoutMs passes; returns the last value either way. */
export async function poll<T>(fn: () => Promise<T>, until: (v: T) => boolean, timeoutMs = 20000, delayMs = 700): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  let last = await fn();
  while (!until(last) && Date.now() < deadline) {
    await sleep(delayMs);
    last = await fn();
  }
  return last;
}

/** The answer's envelope code, when it is one. */
export const codeOf = (r: EdgeResponse): string | undefined => (r.body as { code?: string } | null)?.code;
