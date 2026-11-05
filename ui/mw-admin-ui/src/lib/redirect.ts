/** Where to go after login: a page of this console only (no open redirect). */
export function safeCallback(raw: string | undefined, basePath: string): string {
  const fallback = `${basePath}/transactions`;
  if (!raw || !raw.startsWith("/") || raw.startsWith("//") || raw.includes("\\")) return fallback;
  if (raw === "/login" || raw.startsWith("/login?") || raw.startsWith("/api/")) return fallback;
  return `${basePath}${raw}`;
}

/**
 * The path inside the console. Next.js strips the basePath from `nextUrl`, but Auth.js rebuilds the
 * request from AUTH_URL without it knowing the basePath, so the prefix may still be there.
 */
export function consolePath(pathname: string, basePath: string): string {
  if (pathname === basePath) return "/";
  return pathname.startsWith(`${basePath}/`) ? pathname.slice(basePath.length) : pathname;
}
