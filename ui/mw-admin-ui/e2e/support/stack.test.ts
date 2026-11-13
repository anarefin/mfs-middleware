import { describe, expect, it } from "vitest";
import { notReadyMessage, stackProblems, type Probes, type StackTarget } from "./stack";

const target: StackTarget = {
  baseURL: "https://localhost",
  project: "mfs-mw",
  manifestPath: "/repo/build/seed/manifest.json",
  env: { KC_OPS_TEST_PASSWORD: "fake-pw", KC_OPS_TEST_TOTP_SECRET: "fake-totp", USSD_ROBI_GATEWAY_SECRET: "fake-ussd" },
};

const manifest = JSON.stringify({
  schemaVersion: 1,
  personas: ["maker", "checker", "viewer", "compliance"].map((p) => ({
    persona: p, realm: "ops", username: `seed-ops-${p}`, roles: ["viewer"], loginClient: "mw-e2e-ops",
    passwordEnv: "KC_OPS_TEST_PASSWORD", totpSecretEnv: "KC_OPS_TEST_TOTP_SECRET",
  })),
});

const HEALTHY: Record<string, string> = {
  apisix: "Up 3 minutes", keycloak: "Up 3 minutes (healthy)", "mw-admin-api": "Up 2 minutes (healthy)",
  "mw-admin-ui": "Up 2 minutes (healthy)", "mw-orchestrator": "Up 2 minutes (healthy)",
  "mw-core-mock": "Up 2 minutes (healthy)", "mw-ussd": "Up 2 minutes (healthy)", "mw-audit-dw": "Up 2 minutes (healthy)",
};

function probes(over: Partial<{ http: Record<string, number>; containers: Record<string, string> | Error; manifest: string | null }> = {}): Probes {
  const http: Record<string, number> = {
    "https://localhost/healthz": 200,
    "https://localhost/realms/ops/.well-known/openid-configuration": 200,
    "https://localhost/console/api/health": 200,
    ...over.http,
  };
  return {
    async http(url) {
      const status = http[url];
      return status === undefined ? { error: "ECONNREFUSED" } : { status };
    },
    async containers() {
      if (over.containers instanceof Error) throw over.containers;
      return new Map(Object.entries(over.containers ?? HEALTHY));
    },
    readFile() {
      return over.manifest === undefined ? manifest : over.manifest ?? undefined;
    },
  };
}

describe("stack check before the browser tests", () => {
  it("finds nothing wrong on a running, seeded stack", async () => {
    expect(await stackProblems(target, probes())).toEqual([]);
  });

  it("names a stopped mw-admin-api", async () => {
    const { "mw-admin-api": _, ...rest } = HEALTHY;
    const problems = await stackProblems(target, probes({ containers: rest }));
    expect(problems).toEqual(["mw-admin-api: no running container in compose project mfs-mw"]);
  });

  it("names a container that is not healthy yet", async () => {
    const problems = await stackProblems(target, probes({ containers: { ...HEALTHY, "mw-ussd": "Up 5 seconds (health: starting)" } }));
    expect(problems).toEqual(["mw-ussd: Up 5 seconds (health: starting)"]);
  });

  it("names every edge URL that does not answer 200", async () => {
    const problems = await stackProblems(target, probes({
      http: { "https://localhost/healthz": 503, "https://localhost/console/api/health": undefined as unknown as number },
    }));
    expect(problems).toEqual([
      "APISIX https://localhost/healthz answered 503",
      "admin UI https://localhost/console/api/health did not answer (ECONNREFUSED)",
    ]);
  });

  it("reports Docker itself when it cannot list the containers", async () => {
    const problems = await stackProblems(target, probes({ containers: new Error("Cannot connect to the Docker daemon") }));
    expect(problems).toEqual(["docker ps failed: Cannot connect to the Docker daemon"]);
  });

  it("needs the seed manifest with the four ops personas", async () => {
    expect(await stackProblems(target, probes({ manifest: null }))).toEqual([
      "seed manifest /repo/build/seed/manifest.json is missing (mw-seeder writes it; scripts/ui-e2e.sh copies it out)",
    ]);
    const noViewer = JSON.stringify({ schemaVersion: 1, personas: JSON.parse(manifest).personas.filter((p: { persona: string }) => p.persona !== "viewer") });
    expect(await stackProblems(target, probes({ manifest: noViewer }))).toEqual([
      "seed manifest /repo/build/seed/manifest.json has no ops persona viewer (mw-seeder writes it; scripts/ui-e2e.sh copies it out)",
    ]);
    // a manifest seeded before the compliance persona existed (mw-seeder of ticket 57)
    const noCompliance = JSON.stringify({ schemaVersion: 1, personas: JSON.parse(manifest).personas.slice(0, 3) });
    expect(await stackProblems(target, probes({ manifest: noCompliance }))).toEqual([
      "seed manifest /repo/build/seed/manifest.json has no ops persona compliance (mw-seeder writes it; scripts/ui-e2e.sh copies it out)",
    ]);
  });

  it("needs the credentials the manifest names, from deploy/.env", async () => {
    const problems = await stackProblems({ ...target, env: { USSD_ROBI_GATEWAY_SECRET: "x" } }, probes());
    expect(problems).toEqual([
      "KC_OPS_TEST_PASSWORD is not set (scripts/ui-e2e.sh reads it from deploy/.env)",
      "KC_OPS_TEST_TOTP_SECRET is not set (scripts/ui-e2e.sh reads it from deploy/.env)",
    ]);
  });

  it("tells how to fix it", () => {
    const m = notReadyMessage(["mw-admin-api: no running container in compose project mfs-mw"]);
    expect(m).toContain("  - mw-admin-api: no running container in compose project mfs-mw");
    expect(m).toContain("--profile seed up -d --wait");
    expect(m).toContain("scripts/ui-e2e.sh");
  });
});
