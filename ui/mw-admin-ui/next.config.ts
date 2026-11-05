import type { NextConfig } from "next";
import { STATIC_SECURITY_HEADERS } from "./src/lib/security-headers";

const config: NextConfig = {
  // Docker image (spec 15.1): `.next/standalone` holds a self-contained server.js
  output: "standalone",
  // APISIX routes /console/* to this app (deploy/apisix/routes/admin-ui.json); BASE_PATH in src/lib/server/env.ts
  basePath: "/console",
  poweredByHeader: false,
  reactStrictMode: true,
  async headers() {
    return [{ source: "/:path*", headers: STATIC_SECURITY_HEADERS }];
  },
};

export default config;
