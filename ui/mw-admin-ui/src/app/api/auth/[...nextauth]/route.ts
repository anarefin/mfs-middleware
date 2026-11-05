import { NextRequest } from "next/server";
import { handlers } from "@/auth";
import { BASE_PATH } from "@/lib/server/env";

// Next.js hands route handlers the URL without the basePath; Auth.js matches its actions on the
// full path (its basePath is /console/api/auth), so put the prefix back.
function withBasePath(req: NextRequest): NextRequest {
  const url = new URL(req.url);
  if (url.pathname.startsWith(`${BASE_PATH}/`)) return req;
  url.pathname = `${BASE_PATH}${url.pathname}`;
  return new NextRequest(url, req);
}

export const GET = (req: NextRequest) => handlers.GET(withBasePath(req));
export const POST = (req: NextRequest) => handlers.POST(withBasePath(req));
