import { Cpu, Network, Zap } from "lucide-react";
import { FillScope } from "@/components/fill-scope";
import { HowToTest } from "@/components/how-to-test";
import { PageHeader } from "@/components/page-header";
import { Hint, Panel } from "@/components/section";
import { PRESETS } from "@/lib/presets";
import { CoreForms, PartnerForms } from "./forms";
import { PresetButton } from "./preset-button";

function GroupTitle({ icon: Icon, children }: { icon: typeof Zap; children: React.ReactNode }) {
  return (
    <h2 className="m-0 mt-2 flex items-center gap-2 text-base font-semibold tracking-tight">
      <Icon className="size-4 text-muted-foreground" />
      {children}
    </h2>
  );
}

export default function Simulators() {
  return (
    <>
      <PageHeader
        title="Simulators & faults"
        description={
          <>
            The partners (OCS, bill aggregator, bank, merchant), the SMSC, the USSD gateway, the payShield HSM and the core are mocks:
            set how they behave (one mock-control API for every mw-simulators party), run a flow on the App, USSD or Checkout page,
            then come back and reset. A preset is a ready-made failure test; the forms below set anything else. Changes
            last until reset (or a restart of mw-simulators / mw-core-mock), so always end with &quot;Reset everything&quot;.
          </>
        }
      />
      <HowToTest page="simulators" />
      <GroupTitle icon={Zap}>Presets</GroupTitle>
      <div className="grid items-start gap-4 md:grid-cols-2 xl:grid-cols-3">
        {PRESETS.map((p) => (
          <Panel key={p.id} className="h-full">
            <h3 className="m-0 text-sm font-semibold">{p.title}</h3>
            <p className="m-0 text-sm"><strong className="font-medium">Proves:</strong> {p.proves}</p>
            <Hint><strong className="font-medium">Then:</strong> {p.then}</Hint>
            <PresetButton id={p.id} label={p.id === "reset-all" ? "Reset everything" : "Apply"} danger={p.id !== "reset-all"} />
          </Panel>
        ))}
      </div>
      <GroupTitle icon={Network}>Mock control</GroupTitle>
      <Hint className="text-sm">
        Magic values work without any scenario: a number / account / bill ending 999 is refused; an amount with .13 times out (applied),
        .77 gives 500 after send (applied), .99 an unknown code, .55 a malformed answer. Bills: due = Tk 100 + the last 3 digits; ending 404
        not found, 000 already paid. SMSC: a number ending 997 is throttled twice, 996 refused, 995 gets no DLR, 999 / 998 an
        undeliverable / expired DLR.
      </Hint>
      <FillScope page="simulators">
        <PartnerForms />
        <GroupTitle icon={Cpu}>Core and USSD gateway</GroupTitle>
        <CoreForms />
      </FillScope>
    </>
  );
}
