import { PRESETS } from "@/lib/presets";
import { CoreForms, PartnerForms } from "./forms";
import { PresetButton } from "./preset-button";

export default function Simulators() {
  return (
    <>
      <h1>Simulators & faults</h1>
      <p className="lead">
        The partners (OCS, bill aggregator, bank) and the core are simulators: set how they behave, run a flow on the App, USSD or
        Checkout page, then come back and reset. A preset is a ready-made failure test; the forms below set anything else. Changes
        last until reset (or a restart of mw-simulators / mw-core-mock), so always end with &quot;Reset everything&quot;.
      </p>
      <h2>Presets</h2>
      <div className="grid">
        {PRESETS.map((p) => (
          <section key={p.id} className="panel">
            <h3>{p.title}</h3>
            <p><strong>Proves:</strong> {p.proves}</p>
            <p className="hint"><strong>Then:</strong> {p.then}</p>
            <PresetButton id={p.id} label={p.id === "reset-all" ? "Reset everything" : "Apply"} danger={p.id !== "reset-all"} />
          </section>
        ))}
      </div>
      <h2>Partners</h2>
      <p className="hint">
        Magic values work without any scenario: a number / account / bill ending 999 is refused; an amount with .13 times out (applied),
        .77 gives 500 after send (applied), .99 an unknown code, .55 a malformed answer. Bills: due = Tk 100 + the last 3 digits; ending 404
        not found, 000 already paid.
      </p>
      <PartnerForms />
      <h2>Core and USSD gateway</h2>
      <CoreForms />
    </>
  );
}
