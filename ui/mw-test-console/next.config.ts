import type { NextConfig } from "next";
import { STATIC_SECURITY_HEADERS } from "./src/lib/security-headers";

const config: NextConfig = {
  // Docker image: `.next/standalone` holds a self-contained server.js
  output: "standalone",
  // APISIX routes /tester/* here (deploy/apisix/routes-test/test-console.json, only with MW_E2E_ROUTES=1)
  basePath: "/tester",
  poweredByHeader: false,
  reactStrictMode: true,
  // no generated AGENTS.md: this module is documented in its CLAUDE.md
  agentRules: false,
  async headers() {
    return [{ source: "/:path*", headers: STATIC_SECURITY_HEADERS }];
  },
};

export default config;
