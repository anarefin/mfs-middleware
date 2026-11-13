import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

export default defineConfig({
  resolve: { alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) } },
  test: {
    // e2e/support: the Playwright suite's own logic (TOTP, stack check), no browser and no stack
    include: ["src/**/*.test.{ts,tsx}", "e2e/support/**/*.test.ts"],
    environment: "node",
    // next-auth imports "next/server" without an extension, which Node's ESM resolver refuses;
    // inlined, Vite resolves it (src/auth.test.ts runs the real Auth.js callbacks)
    server: { deps: { inline: ["next-auth"] } },
  },
});
