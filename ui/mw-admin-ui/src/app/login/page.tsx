import { CircleAlert, LockKeyhole } from "lucide-react";
import { signIn } from "@/auth";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
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
    <main className="grid min-h-svh place-items-center bg-sidebar p-4">
      <div className="flex w-full max-w-sm flex-col gap-6 rounded-xl border bg-card p-8 shadow-sm">
        <div className="flex items-center gap-2.5">
          <div className="grid size-9 place-items-center rounded-lg bg-primary text-sm font-bold text-primary-foreground">B</div>
          <div className="leading-tight">
            <div className="font-semibold">Bits ops console</div>
            <div className="text-xs text-muted-foreground">MFS middleware operations</div>
          </div>
        </div>
        <div>
          <h1 className="m-0 text-xl font-semibold tracking-tight">Operations console</h1>
          <p className="m-0 mt-1 text-muted-foreground">Sign in with your operator account (password and one-time code).</p>
        </div>
        {error ? (
          <Alert variant="destructive" className="error" role="alert">
            <CircleAlert />
            <AlertDescription>{MESSAGES[error] ?? "Sign-in failed. Try again."}</AlertDescription>
          </Alert>
        ) : null}
        <form action={login}>
          <Button type="submit" size="lg" className="w-full"><LockKeyhole />Sign in with Keycloak</Button>
        </form>
      </div>
    </main>
  );
}
