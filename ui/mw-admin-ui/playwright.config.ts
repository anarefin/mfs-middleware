import { defineConfig, devices } from "@playwright/test";

// Self-contained: a production build of the console against the test doubles of e2e/mock/server.mjs
// (Keycloak realm ops + stub mw-admin-api). No stack needed. Fake secrets only.
import { API, API_PORT, APP, APP_PORT, IDP, IDP_PORT } from "./e2e/ports";

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [["list"]],
  timeout: 30_000,
  use: { baseURL: APP, trace: "retain-on-failure" },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: [
    {
      command: "node e2e/mock/server.mjs",
      url: `${IDP}/realms/ops/.well-known/openid-configuration`,
      reuseExistingServer: false,
      env: { MOCK_IDP_PORT: String(IDP_PORT), MOCK_API_PORT: String(API_PORT) },
    },
    {
      // the standalone server, as the Docker image runs it
      command: `${process.env.E2E_SKIP_BUILD ? "" : "pnpm build && "}rm -rf .next/standalone/.next/static && cp -R .next/static .next/standalone/.next/static && node .next/standalone/server.js`,
      url: `${APP}/console/api/health`,
      timeout: 240_000,
      reuseExistingServer: false,
      env: {
        AUTH_SECRET: "fake-e2e-secret-0123456789abcdef0123456789abcdef",
        AUTH_KEYCLOAK_ISSUER: `${IDP}/realms/ops`,
        AUTH_KEYCLOAK_ID: "mw-admin-ui",
        // the public URL of the console's auth routes (behind APISIX: https://localhost/console/api/auth)
        AUTH_URL: `${APP}/console/api/auth`,
        ADMIN_API_URL: API,
        PORT: String(APP_PORT),
        HOSTNAME: "::",
      },
    },
  ],
});
