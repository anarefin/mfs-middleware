import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

export default defineConfig({
  resolve: { alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) } },
  test: {
    // e2e/support: the Playwright suite's own logic (TOTP, stack check), no browser and no stack
    include: ["src/**/*.test.{ts,tsx}", "e2e/support/**/*.test.ts"],
    environment: "node",
  },
});
