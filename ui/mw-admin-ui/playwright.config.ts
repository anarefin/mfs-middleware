import { defineConfig, devices } from "@playwright/test";

import { BASE_URL } from "./e2e/support/env";

/**
 * Browser tests of the console against the running, seeded stack (ticket 59): the mw-admin-ui
 * container behind APISIX, real Keycloak realm ops, the real mw-admin-api. `make up && make seed`,
 * then `make ui-e2e` (exports the deploy/.env credentials and the seed manifest path). Specs share
 * seeded personas and the maker-checker inbox and hand ids to each other (e2e/support/state.ts), so
 * they run in file order on one worker.
 */
export default defineConfig({
  testDir: "./e2e",
  testMatch: "**/*.spec.ts",
  globalSetup: "./e2e/support/global-setup.ts",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 90_000,
  expect: { timeout: 15_000 },
  reporter: [["list"], ["html", { open: "never" }], ["junit", { outputFile: "test-results/junit.xml" }]],
  use: {
    baseURL: BASE_URL,
    // the dev stack's APISIX certificate is self-signed
    ignoreHTTPSErrors: true,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "retain-on-failure",
    viewport: { width: 1440, height: 1000 },
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"], viewport: { width: 1440, height: 1000 } } }],
});
