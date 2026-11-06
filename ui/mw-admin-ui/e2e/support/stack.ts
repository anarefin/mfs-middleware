import { execFile } from "node:child_process";
import { readFileSync } from "node:fs";
import { request as httpRequest } from "node:http";
import { request as httpsRequest } from "node:https";
import { USSD_SECRET_ENV } from "./env";
import { parseManifest } from "./personas";

/**
 * The readiness check of global setup: the edge URLs through APISIX, the containers the console and
 * the specs' own data need (mw-admin-api has no public health route, so Docker reports it), the seed
 * manifest and the credentials it names. Every probe is bounded (5 s), so a stopped stack fails in
 * seconds, with the fix.
 */
export type StackTarget = { baseURL: string; project: string; manifestPath: string; env: Record<string, string | undefined> };

export type Probes = {
  http(url: string): Promise<{ status: number } | { error: string }>;
  /** service name -> `docker ps` status of the running containers of the compose project */
  containers(project: string): Promise<Map<string, string>>;
  readFile(path: string): string | undefined;
};

/** What the console and the specs talk to: the edge, the IdP, the console, the admin API and the txn path. */
export const SERVICES = [
  "apisix", "keycloak", "mw-admin-api", "mw-admin-ui", "mw-orchestrator", "mw-core-mock", "mw-ussd", "mw-audit-dw",
] as const;


export async function stackProblems(t: StackTarget, p: Probes): Promise<string[]> {
  const edge: [string, string][] = [
    ["APISIX", `${t.baseURL}/healthz`],
    ["Keycloak realm ops", `${t.baseURL}/realms/ops/.well-known/openid-configuration`],
    ["admin UI", `${t.baseURL}/console/api/health`],
  ];
  const [answers, containers] = await Promise.all([
    Promise.all(edge.map(([, url]) => p.http(url))),
    p.containers(t.project).then((m) => m, (e: unknown) => (e instanceof Error ? e : new Error(String(e)))),
  ]);
  const problems: string[] = [];
  answers.forEach((a, i) => {
    const [name, url] = edge[i]!;
    if ("error" in a) problems.push(`${name} ${url} did not answer (${a.error})`);
    else if (a.status !== 200) problems.push(`${name} ${url} answered ${a.status}`);
  });
  if (containers instanceof Error) {
    problems.push(`docker ps failed: ${containers.message}`);
  } else {
    for (const s of SERVICES) {
      const status = containers.get(s);
      if (!status) problems.push(`${s}: no running container in compose project ${t.project}`);
      // a service without a healthcheck shows plain "Up ..."
      else if (!status.startsWith("Up") || (status.includes("(health") && !status.includes("(healthy)"))) problems.push(`${s}: ${status}`);
    }
  }

  const text = p.readFile(t.manifestPath);
  const needed = new Set<string>([USSD_SECRET_ENV]);
  if (text === undefined) {
    problems.push(`seed manifest ${t.manifestPath} is missing (make seed writes it)`);
  } else {
    try {
      const { personas, missing } = parseManifest(text);
      for (const m of missing) problems.push(`seed manifest ${t.manifestPath} has no ops persona ${m} (make seed writes it)`);
      for (const mp of personas.values()) {
        needed.add(mp.passwordEnv);
        if (mp.totpSecretEnv) needed.add(mp.totpSecretEnv);
      }
    } catch (e) {
      problems.push(`seed manifest ${t.manifestPath} is not JSON (${(e as Error).message}); run make seed`);
    }
  }
  for (const v of [...needed].sort()) {
    if (!t.env[v]) problems.push(`${v} is not set (make ui-e2e reads it from deploy/.env)`);
  }
  return problems;
}

export function notReadyMessage(problems: string[]): string {
  return [
    "The stack is not ready for the admin UI browser tests:",
    ...problems.map((x) => `  - ${x}`),
    "Start and seed it from the repo root, then run the suite again:",
    "  make up && make seed",
    "  make ui-e2e",
  ].join("\n");
}

const TIMEOUT_MS = 5_000;

/** The real probes: HTTPS with the dev stack's self-signed certificate accepted, `docker ps`. */
export const liveProbes: Probes = {
  http(url) {
    return new Promise((resolve) => {
      const u = new URL(url);
      const req = (u.protocol === "https:" ? httpsRequest : httpRequest)(u, { method: "GET", rejectUnauthorized: false, timeout: TIMEOUT_MS }, (res) => {
        res.resume();
        resolve({ status: res.statusCode ?? 0 });
      });
      req.on("timeout", () => req.destroy(new Error(`no answer within ${TIMEOUT_MS} ms`)));
      req.on("error", (e: NodeJS.ErrnoException) => resolve({ error: e.code ?? e.message }));
      req.end();
    });
  },
  containers(project) {
    return new Promise((resolve, reject) => {
      execFile(
        "docker",
        ["ps", "--filter", `label=com.docker.compose.project=${project}`, "--format", '{{.Label "com.docker.compose.service"}}\t{{.Status}}'],
        { timeout: TIMEOUT_MS },
        (err, stdout, stderr) => {
          if (err) return reject(new Error((stderr || err.message).trim().split("\n")[0]));
          const m = new Map<string, string>();
          for (const line of stdout.split("\n")) {
            const [svc, status] = line.split("\t");
            if (svc && status) m.set(svc, status);
          }
          resolve(m);
        },
      );
    });
  },
  readFile(path) {
    try {
      return readFileSync(path, "utf8");
    } catch {
      return undefined;
    }
  },
};
