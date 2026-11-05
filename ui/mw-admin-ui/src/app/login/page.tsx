import { signIn } from "@/auth";
import { safeCallback } from "@/lib/redirect";
import { BASE_PATH } from "@/lib/server/env";

const MESSAGES: Record<string, string> = {
  SessionExpired: "Your session has ended. Sign in again.",
  AccessDenied: "Access denied.",
};

type Props = { searchParams: Promise<Record<string, string | string[] | undefined>> };

export default async function LoginPage({ searchParams }: Props) {
  const sp = await searchParams;
  const callbackUrl = typeof sp.callbackUrl === "string" ? sp.callbackUrl : undefined;
  const error = typeof sp.error === "string" ? sp.error : undefined;
  const redirectTo = safeCallback(callbackUrl, BASE_PATH);

  async function login() {
    "use server";
    await signIn("keycloak", { redirectTo });
  }

  return (
    <main className="login panel">
      <h1>Operations console</h1>
      <p className="hint">Sign in with your operator account (password and one-time code).</p>
      {error ? <p className="error" role="alert">{MESSAGES[error] ?? "Sign-in failed. Try again."}</p> : null}
      <form action={login}>
        <button className="primary" type="submit">Sign in with Keycloak</button>
      </form>
    </main>
  );
}
