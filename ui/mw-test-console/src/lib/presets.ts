// Ready-made simulator and core-mock behaviours for the failure tests (Simulators page), each with
// what it proves. Bodies are the simulators' control API (services/mw-simulators CLAUDE.md) and
// core-mock's fault API; mirrored from the API suite's scenarios (tests/api/src).

export type Preset = {
  id: string;
  title: string;
  proves: string;
  /** what to do afterwards */
  then: string;
  calls: { method: "POST" | "DELETE"; path: string; json?: unknown }[];
};

const BILLAGG_CB = "http://apisix:9080/partner/billagg/callback/payments";
const BANK_CB = "http://apisix:9080/partner/c2wbank/callback/debits";

export const PRESETS: readonly Preset[] = [
  {
    id: "reset-all", title: "Reset everything to normal",
    proves: "every partner answers normally, no core fault, merchant accepts every IPN, SMPP gateway normal",
    then: "use after each failure test",
    calls: [
      { method: "DELETE", path: "/test/sim/ocs/scenario" }, { method: "DELETE", path: "/test/sim/billagg/scenario" },
      { method: "DELETE", path: "/test/sim/c2wbank/scenario" }, { method: "DELETE", path: "/test/core-mock/v1/faults" },
      { method: "POST", path: "/test/sim/merchant/ipn/scenario", json: { failNext: 0 } },
      { method: "POST", path: "/test/sim/ussdgw/smpp/scenario", json: {} },
    ],
  },
  {
    id: "core-lost-reply", title: "Core: the reply of the next transfer is lost (TSQ -> COMPLETED)",
    proves: "a P2P / cash / merchant payment whose core answer never comes is resolved by the status inquiry",
    then: "App: send a P2P; it answers 202 PROCESSING, then COMPLETED after the inquiry (TIMERS=fast: about 6-10 s); money moved once",
    calls: [{ method: "POST", path: "/test/core-mock/v1/faults", json: { mode: "DROP_REPLY", commandType: "PostTransfer", times: 1 } }],
  },
  {
    id: "core-lost-refusal", title: "Core: the next transfer is refused and the refusal lost (TSQ -> FAILED)",
    proves: "timeout -> TSQ -> release for flows without a hold: the inquiry finds nothing applied, the txn ends FAILED MW-TMO-003",
    then: "App: send a P2P; it ends FAILED and no balance moves",
    calls: [
      { method: "POST", path: "/test/core-mock/v1/faults", json: { mode: "REJECT", commandType: "PostTransfer", times: 1 } },
      { method: "POST", path: "/test/core-mock/v1/faults", json: { mode: "DROP_REPLY", commandType: "PostTransfer", times: 1 } },
    ],
  },
  {
    id: "core-insufficient", title: "Core: the next validation says insufficient balance",
    proves: "a business failure from the core comes back as MW-CORE-* with the core's reason",
    then: "App: any money flow is REJECTED with the core reason",
    calls: [{ method: "POST", path: "/test/core-mock/v1/faults", json: { mode: "REJECT", commandType: "ValidateTransaction", times: 1, reasonCode: "INSUFFICIENT_BALANCE" } }],
  },
  {
    id: "core-hold-lost", title: "Core: the reply of the next hold is lost",
    proves: "a top-up / bill pay whose hold answer is lost is resolved by the status inquiry",
    then: "App: send a top-up or bill payment",
    calls: [{ method: "POST", path: "/test/core-mock/v1/faults", json: { mode: "DROP_REPLY", commandType: "HoldFunds", times: 1 } }],
  },
  {
    id: "core-commit-refused", title: "Core: the next hold commit is refused (-> MANUAL_REVIEW)",
    proves: "a top-up / bill payment the partner applied but the core will not commit stops in MANUAL_REVIEW for an operator",
    then: "App: send a top-up; it ends MANUAL_REVIEW. Ops console: request FORCE_COMMIT as ops-maker01, approve as ops-checker01 -> COMPLETED",
    calls: [{ method: "POST", path: "/test/core-mock/v1/faults", json: { mode: "REJECT", commandType: "CommitHold", times: 1, reasonCode: "INJECTED_REJECT" } }],
  },
  {
    id: "core-reversal-refused", title: "Core: the next reversal is refused (-> MANUAL_REVIEW)",
    proves: "a reversal the core refuses goes to manual review with an audit entry",
    then: "Ops console: request a reversal of a COMPLETED P2P and approve it; the reversal ends MANUAL_REVIEW",
    calls: [{ method: "POST", path: "/test/core-mock/v1/faults", json: { mode: "REJECT", commandType: "ReverseTransaction", times: 1, reasonCode: "INSUFFICIENT_BALANCE" } }],
  },
  {
    id: "ocs-outage", title: "OCS (top-up) outage",
    proves: "the partner answers 503 unapplied: the hold is released, the top-up FAILED with MW-PTR-*",
    then: "App: send a top-up; then reset",
    calls: [{ method: "POST", path: "/test/sim/ocs/scenario", json: { outage: true } }],
  },
  {
    id: "ocs-timeout", title: "OCS: every top-up times out (applied)",
    proves: "UNKNOWN -> the status inquiry finds the top-up applied and commits the hold",
    then: "App: send a top-up (any amount); the same as amount .13",
    calls: [{ method: "POST", path: "/test/sim/ocs/scenario", json: { forceMode: "TIMEOUT" } }],
  },
  {
    id: "ocs-late-unapplied", title: "OCS: answers too late and refuses (not applied)",
    proves: "UNKNOWN -> the status inquiry finds nothing -> hold released, FAILED",
    then: "App: send a top-up (it answers 202 PROCESSING); about 25 s later, once Simulators -> ocs shows the request answered, press 'Reset ocs' so the status inquiry gets the real answer (NOT_FOUND): the hold is released, FAILED",
    calls: [{ method: "POST", path: "/test/sim/ocs/scenario", json: { latencyMs: 25000, errorRate: 1.0 } }],
  },
  {
    id: "billagg-callback-only", title: "Bill aggregator: callback-only payments (callback ONCE)",
    proves: "the payment is answered 202 PENDING and completed by the aggregator's signed callback",
    then: "App: pay bill 1234567; it completes when the callback arrives",
    calls: [{ method: "POST", path: "/test/sim/billagg/scenario", json: { callbackOnly: true, callbackMode: "ONCE", callbackUrl: BILLAGG_CB } }],
  },
  {
    id: "billagg-duplicate", title: "Bill aggregator: callback-only, every callback sent twice",
    proves: "a duplicate callback has no second effect",
    then: "App: pay a bill; check the balance moved once and the audit trail",
    calls: [{ method: "POST", path: "/test/sim/billagg/scenario", json: { callbackOnly: true, callbackMode: "DUPLICATE", callbackUrl: BILLAGG_CB } }],
  },
  {
    id: "billagg-out-of-order", title: "Bill aggregator: callback-only, outcome then a stale PENDING",
    proves: "an out-of-order callback keeps the terminal state",
    then: "App: pay a bill",
    calls: [{ method: "POST", path: "/test/sim/billagg/scenario", json: { callbackOnly: true, callbackMode: "OUT_OF_ORDER", callbackUrl: BILLAGG_CB } }],
  },
  {
    id: "bank-once", title: "Bank (C2W): normal callbacks ONCE",
    proves: "add money: the bank debits the account and its signed callback credits the wallet",
    then: "App: add money from account 1234567890123",
    calls: [{ method: "POST", path: "/test/sim/c2wbank/scenario", json: { callbackMode: "ONCE", callbackUrl: BANK_CB } }],
  },
  {
    id: "bank-duplicate", title: "Bank (C2W): every callback sent twice",
    proves: "a callback the bank sends twice credits the wallet once",
    then: "App: add money; the wallet is credited once",
    calls: [{ method: "POST", path: "/test/sim/c2wbank/scenario", json: { callbackMode: "DUPLICATE", callbackUrl: BANK_CB } }],
  },
  {
    id: "bank-late", title: "Bank (C2W): the callback comes 20 s late",
    proves: "a late callback still completes the add money (or the status inquiry first)",
    then: "App: add money and watch the status",
    calls: [{ method: "POST", path: "/test/sim/c2wbank/scenario", json: { callbackMode: "LATE", callbackDelayMs: 20000, callbackUrl: BANK_CB } }],
  },
  {
    id: "ipn-fail-twice", title: "Merchant IPN endpoint fails the next 2 IPNs (503)",
    proves: "mw-checkout retries the signed IPN until the merchant answers 200",
    then: "Checkout: pay a session; the IPN list shows 503, 503, 200",
    calls: [{ method: "POST", path: "/test/sim/merchant/ipn/scenario", json: { failNext: 2, failStatus: 503 } }],
  },
  {
    id: "smpp-drop", title: "USSD SMPP gateway: drop every bind",
    proves: "mw-ussd re-binds to the gp SMPP gateway on its own",
    then: "USSD: dial on gp after a few seconds; it works again",
    calls: [{ method: "POST", path: "/test/sim/ussdgw/smpp/drop", json: {} }],
  },
];
