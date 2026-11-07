import { Phone } from "./phone";

export default function UssdPage() {
  return (
    <>
      <h1>USSD phone</h1>
      <p className="lead">
        A handset dialling the Bits USSD service. The USSD gateway simulator plays the MNO: robi talks to mw-ussd over HTTP
        (APISIX <code>/ussd/robi</code>, basic auth), gp over SMPP (mw-ussd is bound to the simulator). Same menus, same engine;
        only the transport differs. Money moved by a USSD flow shows on the Home page balances and in the ops console.
      </p>
      <Phone />
    </>
  );
}
