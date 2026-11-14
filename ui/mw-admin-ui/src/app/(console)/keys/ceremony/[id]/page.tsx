import { CircleCheck, KeyRound, ShieldAlert, ShieldCheck } from "lucide-react";
import Link from "next/link";
import { redirect } from "next/navigation";
import { auth } from "@/auth";
import { PageHeader } from "@/components/page-header";
import { StatusBadge } from "@/components/status-badge";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { CardContent, CardDescription, CardHeader, CardSection, CardTitle } from "@/components/ui/card";
import { Field } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { formatTime } from "@/lib/format";
import { ceremonyStep, kcvMatches, mayEnterComponent, type CeremonyStep } from "@/lib/keys";
import { adminApi, operatorToken } from "@/lib/server/admin";
import { BASE_PATH } from "@/lib/server/env";
import { ActionForm } from "../../../action-form";
import { ApiError } from "../../../api-error";
import { enterKeyComponent } from "../../../actions";

// Key ceremony wizard (ticket 75, spec 18.8, OWN-08): custodian 1, custodian 2 (another
// security-admin, in their own session), KCV confirmation. A component is typed into the form and
// posted once; it is never shown back, stored or logged. The HSM combines the two and answers only
// the KCV; the checker compares it with the expected one, then approves in Approvals.

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const STEPS: { step: CeremonyStep; label: string }[] = [
  { step: "custodian1", label: "Custodian 1" },
  { step: "custodian2", label: "Custodian 2" },
  { step: "confirm", label: "KCV confirmation" },
];

type Props = {
  params: Promise<{ id: string }>;
  searchParams: Promise<Record<string, string | string[] | undefined>>;
};

export default async function CeremonyPage({ params, searchParams }: Props) {
  const { id } = await params;
  const sp = await searchParams;
  const expected = typeof sp.expected === "string" ? sp.expected : undefined;
  const entered = typeof sp.entered === "string" ? sp.entered : undefined;
  if (!UUID.test(id)) redirect("/keys");
  const session = await auth();
  const operator = session?.user?.name ?? undefined;
  const roles = session?.roles ?? [];
  const token = await operatorToken();
  if (!token) redirect("/login?error=SessionExpired");
  const result = await adminApi().getKeyCeremony(token, id);
  if (!result.ok) {
    return (
      <>
        <PageHeader title="Key ceremony" />
        <ApiError status={result.status} error={result.error} />
      </>
    );
  }
  const c = result.data;
  const step = ceremonyStep(c);
  const current = STEPS.findIndex((s) => s.step === step);
  return (
    <>
      <PageHeader
        title={`Key ceremony: ${c.keyId}`}
        description={`${c.purpose}, makes ${c.kid ?? "the next version"}. Change request ${c.changeRequestId}.`}
      />
      {entered ? (
        <Alert variant="success" role="status"><CircleCheck /><AlertDescription>Component {entered} recorded. It is not kept here.</AlertDescription></Alert>
      ) : null}

      <ol className="m-0 flex list-none gap-2 p-0" data-testid="ceremony-steps" aria-label="Ceremony steps">
        {STEPS.map((s, i) => (
          <li
            key={s.step}
            aria-current={s.step === step ? "step" : undefined}
            className={`rounded-md border px-3 py-1.5 text-sm ${s.step === step ? "border-primary font-medium" : i < current || step === "closed" ? "text-muted-foreground" : ""}`}
          >
            {i + 1}. {s.label}
          </li>
        ))}
      </ol>

      <CardSection aria-label="Shares" className="max-w-3xl">
        <CardHeader className="flex-col items-start gap-1">
          <CardTitle><KeyRound />Shares</CardTitle>
          <CardDescription>Who entered which component, and when. Never the component itself.</CardDescription>
        </CardHeader>
        <CardContent>
          <ul className="m-0 flex list-none flex-col gap-1 p-0 text-sm" data-testid="ceremony-shares">
            {c.shares.length === 0 ? <li className="text-muted-foreground">No component yet.</li> : null}
            {c.shares.map((s) => (
              <li key={s.share}>Share {s.share}: <span className="font-medium">{s.custodian}</span>{" "}
                <span className="text-muted-foreground tabular-nums">{formatTime(s.enteredAt)}</span></li>
            ))}
          </ul>
          <div className="mt-2"><StatusBadge status={c.status} /></div>
        </CardContent>
      </CardSection>

      {mayEnterComponent(c, operator, roles) ? (
        <CardSection aria-label="Enter component" className="max-w-3xl">
          <CardHeader className="flex-col items-start gap-1">
            <CardTitle><ShieldCheck />{step === "custodian1" ? "Custodian 1" : "Custodian 2"}: enter your component</CardTitle>
            <CardDescription>
              64 hex digits from your sealed share and its 6-digit check value. Needs a sign-in of the
              last 5 minutes; the second component must come from another custodian.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <ActionForm action={enterKeyComponent.bind(null, id)} label="Component" className="flex flex-col gap-3">
              <Field label="Component"><Input name="component" type="password" required autoComplete="off" spellCheck={false} className="font-mono" /></Field>
              <Field label="Check value"><Input name="checkValue" required maxLength={6} autoComplete="off" className="w-32 font-mono" /></Field>
              <Button type="submit" className="self-start"><ShieldCheck />Enter component</Button>
            </ActionForm>
          </CardContent>
        </CardSection>
      ) : step === "custodian2" ? (
        <p className="text-sm text-muted-foreground">Waiting for a second custodian (another security-admin) to enter component 2.</p>
      ) : null}

      {c.checkValue ? (
        <CardSection aria-label="KCV confirmation" className="max-w-3xl">
          <CardHeader className="flex-col items-start gap-1">
            <CardTitle><ShieldCheck />Key check value</CardTitle>
            <CardDescription>Compare it with the KCV on the key&apos;s record before you approve.</CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-3">
            <p className="m-0 font-mono text-2xl tracking-widest" data-testid="kcv">{c.checkValue}</p>
            <form method="get" action={`${BASE_PATH}/keys/ceremony/${id}`} className="flex items-end gap-2" aria-label="Compare KCV">
              <Field label="Expected KCV"><Input name="expected" defaultValue={expected} maxLength={6} className="w-32 font-mono" /></Field>
              <Button type="submit" variant="outline">Compare</Button>
            </form>
            {expected ? (
              kcvMatches(c, expected) ? (
                <Alert variant="success" role="status"><CircleCheck /><AlertDescription>The KCV matches.</AlertDescription></Alert>
              ) : (
                <Alert variant="destructive" role="alert"><ShieldAlert /><AlertDescription>The KCV does not match: reject the request.</AlertDescription></Alert>
              )
            ) : null}
            {step === "confirm" ? (
              <Link href="/approvals" className="text-sm underline">Decide it in Approvals (another security-admin)</Link>
            ) : null}
          </CardContent>
        </CardSection>
      ) : null}
    </>
  );
}
