import { defineConfig, devices } from "@playwright/test";

/**
 * The test console in a browser against the running, seeded stack: the stack started with deploy/.env (its test timers) and the seed
 * profile, then scripts/tester.sh, then scripts/tester-e2e.sh (E2E_BASE_URL https://localhost; against `pnpm dev`
 * E2E_BASE_URL=http://localhost:3001). Each spec drives one page as a tester would and checks the
 * middleware's answer; specs run in file order on one worker (they share the simulators).
 */
const base = (process.env.E2E_BASE_URL || "https://localhost").replace(/\/+$/, "");

export default defineConfig({
  testDir: "./e2e",
  testMatch: "**/*.spec.ts",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 150_000,
  expect: { timeout: 70_000 },
  reporter: [["list"], ["junit", { outputFile: "test-results/junit.xml" }]],
  use: {
    baseURL: `${base}/tester/`,
    ignoreHTTPSErrors: true,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    viewport: { width: 1440, height: 1000 },
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"], viewport: { width: 1440, height: 1000 } } }],
});
