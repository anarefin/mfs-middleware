// Ready-made simulator and core-mock behaviours for the failure tests (Simulators page), each with
// what it proves. Bodies are mw-simulators' mock-control API (/test/mock-control/*, ADR D33, tickets
// 102a/b; contracts/api/mw-simulators/mock-control.v1.openapi.yaml) and core-mock's fault API; mirrored
// from the API suite's scenarios (tests/api/src). Each preset first puts its party back to normal.

import { normalCalls, PARTY_NAMES, type MockPartyName } from "./mock-parties";

export type Preset = {
  id: string;
  title: string;
  proves: string;
  /** what to do afterwards */
  then: string;
  calls: { method: "POST" | "DELETE" | "PUT"; path: string; json?: unknown }[];
};

const MC = "/test/mock-control/";
const scenario = (party: MockPartyName, json: Record<string, unknown>) => ({ method: "POST" as const, path: `${MC}${party}/scenarios`, json });
const switchTo = (party: MockPartyName, json: Record<string, unknown>) => ({ method: "PUT" as const, path: `${MC}${party}/switch`, json });
const settings = (party: MockPartyName, json: Record<string, unknown>) => ({ method: "PUT" as const, path: `${MC}${party}/settings`, json });

const BILLAGG_CB = "http://apisix:9080/partner/billagg/callback/payments";
const BANK_CB = "http://apisix:9080/partner/c2wbank/callback/debits";

export const PRESETS: readonly Preset[] = [
  {
    id: "reset-all", title: "Reset everything to normal",
    proves: "every mock party answers normally (no scenario, switch UP, default settings), no core fault",
    then: "use after each failure test",
    calls: [...PARTY_NAMES.flatMap((p) => normalCalls(p)), { method: "DELETE", path: "/test/core-mock/v1/faults" }],
  },
  {
    id: "core-lost-reply", title: "Core: the reply of the next transfer is lost (TSQ -> COMPLETED)",
    proves: "a P2P / cash / merchant payment whose core answer never comes is resolved by the status inquiry",
    then: "App: send a P2P; it answers 202 PROCESSING, then COMPLETED after the inquiry (test timers: about 6-10 s); money moved once",
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
    proves: "a top-up / bill pay whose hold answer never comes is refused safely: nothing moves, MW-TMO-002",
    then: "App: send a top-up or bill payment; it ends REJECTED MW-TMO-002 (HTTP 504) and no balance moves",
    calls: [{ method: "POST", path: "/test/core-mock/v1/faults", json: { mode: "DROP_REPLY", commandType: "HoldFunds", times: 1 } }],
  },
  {
    id: "core-commit-refused", title: "Core: the next hold commit is refused (-> MANUAL_REVIEW)",
    proves: "a top-up / bill payment the partner applied but the core will not commit stops in MANUAL_REVIEW for an operator",
    then: "App: send a top-up; it ends MANUAL_REVIEW. Ops console: request FORCE_COMMIT as seed-ops-maker, approve as seed-ops-checker -> COMPLETED",
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
    proves: "a partner 503 is an unknown outcome (fail closed): the top-up stays UNKNOWN with the amount held while the status inquiry also gets 503",
    then: "App: send a top-up (it ends UNKNOWN, amount held); then reset: the next status inquiry finds nothing, the hold is released, FAILED",
    calls: [...normalCalls("ocs"), switchTo("ocs", { state: "MAINTENANCE" })],
  },
  {
    id: "ocs-timeout", title: "OCS: every top-up times out (applied)",
    proves: "UNKNOWN -> the status inquiry finds the top-up applied and commits the hold",
    then: "App: send a top-up (any amount); the same as amount .13",
    calls: [...normalCalls("ocs"), scenario("ocs", { operation: "topup", behaviour: "TIMEOUT" })],
  },
  {
    id: "ocs-late-unapplied", title: "OCS: answers too late and refuses (not applied)",
    proves: "UNKNOWN -> the status inquiry finds nothing -> hold released, FAILED",
    then: "App: send a top-up (it answers 202 PROCESSING); about 25 s later, once Simulators -> ocs shows the request answered, press 'Normal' for ocs in the Mock control panel so the status inquiry gets the real answer (NOT_FOUND): the hold is released, FAILED",
    calls: [...normalCalls("ocs"), switchTo("ocs", { state: "MAINTENANCE", latencyMinMs: 25000, latencyMaxMs: 25000 })],
  },
  {
    id: "billagg-callback-only", title: "Bill aggregator: callback-only payments (callback ONCE)",
    proves: "the payment is answered 202 PENDING and completed by the aggregator's signed callback",
    then: "App: pay bill 1234567; it completes when the callback arrives",
    calls: [...normalCalls("billagg"), settings("billagg", { callbackOnly: true, callbackUrl: BILLAGG_CB })],
  },
  {
    id: "billagg-duplicate", title: "Bill aggregator: callback-only, every callback sent twice",
    proves: "a duplicate callback has no second effect",
    then: "App: pay a bill; check the balance moved once and the audit trail",
    calls: [...normalCalls("billagg"), settings("billagg", { callbackOnly: true, callbackUrl: BILLAGG_CB }), scenario("billagg", { behaviour: "DUPLICATE_CALLBACK" })],
  },
  {
    id: "billagg-out-of-order", title: "Bill aggregator: callback-only, outcome then a stale PENDING",
    proves: "an out-of-order callback keeps the terminal state",
    then: "App: pay a bill",
    calls: [...normalCalls("billagg"), settings("billagg", { callbackOnly: true, callbackUrl: BILLAGG_CB }), scenario("billagg", { behaviour: "OUT_OF_ORDER_CALLBACK" })],
  },
  {
    id: "bank-once", title: "Bank (C2W): normal callbacks ONCE",
    proves: "add money: the bank debits the account and its signed callback credits the wallet",
    then: "App: add money from account 1234567890123",
    calls: [...normalCalls("c2wbank"), settings("c2wbank", { callbackUrl: BANK_CB })],
  },
  {
    id: "bank-duplicate", title: "Bank (C2W): every callback sent twice",
    proves: "a callback the bank sends twice credits the wallet once",
    then: "App: add money; the wallet is credited once",
    calls: [...normalCalls("c2wbank"), settings("c2wbank", { callbackUrl: BANK_CB }), scenario("c2wbank", { behaviour: "DUPLICATE_CALLBACK" })],
  },
  {
    id: "bank-late", title: "Bank (C2W): the callback comes 20 s late",
    proves: "a late callback still completes the add money (or the status inquiry first)",
    then: "App: add money and watch the status",
    calls: [...normalCalls("c2wbank"), settings("c2wbank", { callbackUrl: BANK_CB }), scenario("c2wbank", { behaviour: "LATE_CALLBACK", delayMs: 20000 })],
  },
  {
    id: "ipn-fail-twice", title: "Merchant IPN endpoint fails the next 2 IPNs (503)",
    proves: "mw-checkout retries the signed IPN until the merchant answers 200",
    then: "Checkout: pay a session; the IPN list shows 503, 503, 200",
    calls: [...normalCalls("merchant"), scenario("merchant", { operation: "ipn", behaviour: "HTTP_5XX", httpStatus: 503, nextN: 2 })],
  },
  {
    id: "smpp-drop", title: "USSD SMPP gateway: drop every bind",
    proves: "mw-ussd re-binds to the gp SMPP gateway on its own",
    then: "USSD: dial on gp after a few seconds; it works again",
    calls: [{ method: "POST", path: "/test/sim/ussdgw/smpp/drop", json: {} }],
  },
  {
    id: "smpp-throttle", title: "USSD SMPP gateway: the next 2 screens refused with ESME_RTHROTTLED (0x58)",
    proves: "mw-ussd retries a throttled screen after a delay and the session goes on",
    then: "USSD: dial on gp; the menu still comes (a little later)",
    calls: [...normalCalls("ussdgw"), scenario("ussdgw", { operation: "submit", behaviour: "BUSINESS_ERROR", code: "0x58", nextN: 2 })],
  },
  {
    id: "smsc-no-dlr", title: "SMSC: no delivery receipt for any SMS",
    proves: "an SMS whose delivery receipt never comes stays sent but not delivered (the DLR is the SMSC's callback)",
    then: "App: send a P2P; Inbox shows the SMS without a DLR status",
    calls: [...normalCalls("smsc"), scenario("smsc", { operation: "submit", behaviour: "NO_CALLBACK" })],
  },
  {
    id: "smsc-throttle", title: "SMSC: the next 2 submits refused with ESME_RTHROTTLED (0x58)",
    proves: "mw-notify retries a throttled SMS and it is delivered once",
    then: "App: send a P2P; Inbox shows each SMS once, delivered",
    calls: [...normalCalls("smsc"), scenario("smsc", { operation: "submit", behaviour: "BUSINESS_ERROR", code: "0x58", nextN: 2 })],
  },
  {
    id: "payshield-down", title: "payShield HSM down (connections closed unanswered)",
    proves: "mw-crypto's payShield provider fails closed: its health probe goes down, PIN operations on it are refused",
    then: "reset afterwards (Reset everything)",
    calls: [...normalCalls("payshield"), switchTo("payshield", { state: "DOWN" })],
  },
];
