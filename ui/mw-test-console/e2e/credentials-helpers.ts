import { expect, type APIRequestContext, type Page } from "@playwright/test";

// Helpers of the logins and seed data specs (not a spec: the suite matches *.spec.ts only).

const edgeOrigin = () => new URL((process.env.E2E_BASE_URL || "https://localhost").replace(/\/+$/, "")).origin;

export async function portalsGrant(request: APIRequestContext, username: string, password: string, totp: string) {
  return request.post(`${edgeOrigin()}/auth/realms/portals/protocol/openid-connect/token`, {
    form: { client_id: "mw-portal", grant_type: "password", username, password, totp },
    ignoreHTTPSErrors: true,
  });
}

/** A code with at least 5 s of its step left (a code is refused once it is used, or after its step). */
export async function freshCode(page: Page, testId: string): Promise<string> {
  const code = page.getByTestId(testId);
  await expect(code).toHaveText(/^\d{6}$/, { timeout: 20_000 });
  const left = Number((await page.getByLabel("seconds left").first().textContent())?.replace(/\D/g, "") || "30");
  if (left < 5) await page.waitForTimeout((left + 1) * 1000);
  await expect(code).toHaveText(/^\d{6}$/);
  return (await code.textContent())!.trim();
}

