// The "How to test" panel of every page (components/how-to-test.tsx): the goal, the steps, the
// magic values and what to expect, written from docs/testing-guide.html (sections 0, 2-8, 9.2-9.3).
// A fill pre-fills the page's form: its values are keyed by the field names in FILL_FIELDS, which
// the page's controls carry as name= or data-fill= ("scope:field" = the field inside the element
// with data-testid="scope"). Values are applied in their order (App flows: category, user, then the
// type, as each one resets the fields after it). Pure data: how-to.test.ts checks every page and every fill target.

export const PAGE_KEYS = ["home", "app", "auth", "ussd", "simulators", "inbox", "checkout", "bulk", "transactions", "credentials", "seed-data"] as const;
export type PageKey = (typeof PAGE_KEYS)[number];

export type Fill = { label: string; values: Record<string, string> };
export type HowTo = { goal: string; steps: string[]; magic?: { value: string; meaning: string }[]; expect: string[]; fills?: Fill[] };

/** The CustomEvent a fill button dispatches on window; components/fill-scope.tsx applies it. */
export const FILL_EVENT = "tester:fill";
export type FillDetail = { page: PageKey; values: Record<string, string> };

/** The form fields a fill may set, per page. */
export const FILL_FIELDS: Record<PageKey, readonly string[]> = {
  home: [],
  app: ["category", "user", "txnType", "counterparty", "amount", "pin", "idempotencyKey", "operator", "product", "biller"],
  auth: ["pin", "newPin", "msisdn", "nidLast4", "dob"],
  ussd: ["mno", "msisdn", "serviceCode", "input"],
  simulators: [
    "core-fault:mode", "core-fault:commandType", "core-fault:times", "core-fault:reasonCode", "core-fault:delayMs",
    "scenario-ocs:forceMode", "scenario-ocs:latencyMs", "scenario-billagg:forceMode", "scenario-c2wbank:forceMode", "msisdn",
  ],
  inbox: ["msisdn", "email"],
  checkout: ["amount", "locale", "wallet", "pin"],
  bulk: ["user", "rows", "taka", "csv", "batchId", "reason"],
  transactions: ["txnId", "msisdn", "status", "txnType"],
  credentials: [],
  "seed-data": [],
};

const PARTNER_MAGIC = [
  { value: "number / account / bill ending 999", meaning: "refused by the partner (business failure)" },
  { value: "amount .13 (e.g. 20.13)", meaning: "partner times out but applied: PROCESSING, then COMPLETED after the status inquiry" },
  { value: "amount .77", meaning: "partner answers 500 after applying: COMPLETED after the inquiry" },
  { value: "amount .99", meaning: "unknown code: stays UNKNOWN with the amount held" },
  { value: "amount .55", meaning: "broken body (applied): COMPLETED" },
  { value: "bill ending 404 / 000", meaning: "bill not found / already paid" },
];

export const HOW_TO: Record<PageKey, HowTo> = {
  home: {
    goal: "Check the stack is ready and see the seeded balances before testing.",
    steps: [
      "Look at the Stack panel: three checks.",
      "Press Reset every simulator and core fault before and after every failure test.",
      "Use Where to test what to open a feature page; ops features are in the ops console (/console).",
    ],
    expect: [
      "Three green ✔ (a ✖ says the fix: start the stack, or run scripts/tester.sh again after a docker compose up).",
      "Seeded wallets lists 6 wallets with Balance and Held; every seeded app wallet's PIN is 13579.",
      "The reset answers \"Reset everything to normal: applied\".",
    ],
  },
  app: {
    goal: "Play the mobile app: device binding, the seven money flows, resend with the same Idempotency-Key, lookups and the wallet history.",
    steps: [
      "Pick who is signed in: a Category (customer, agent, merchant), then one of its PIN-ready seeded wallets (or Other MSISDN…); the card shows its live balance, held amount, device and session.",
      "Pick a flow (only the role's flows: an agent cashes in, a merchant only collects), then a counterparty from the list (or Custom…) and a Scenario chip for the outcome to test.",
      "Submit: the console binds a device the first time (OTP + PIN 13579), logs in at mw-auth and signs the request.",
      "Check the result: final status, balances before / after (fee into FEE-POOL), the SMS and every step.",
      "Press Resend the last submit: same txnId, every balance change Tk 0.00.",
      "Click the txnId to open the transaction in the explorer (timeline, partner calls, audit trail).",
      "Load the History (4): the wallet's transactions sent (OUT) and received (IN) on every channel; it reloads after each submit. Switch to the payee to see the same txnId as IN.",
    ],
    magic: [
      { value: "PIN 97531", meaning: "wrong PIN: MW-AUTH-040, HTTP 401, nothing published (not 3 times in a row: that locks the PIN)" },
      { value: "amount 6000", meaning: "over the per-transaction limit: REJECTED MW-CORE-LIMIT_EXCEEDED_PER_TXN" },
      ...PARTNER_MAGIC,
    ],
    expect: [
      "P2P Tk 10: COMPLETED, payer -Tk 15.00, payee Tk 10.00, FEE-POOL Tk 5.00, two SMS with masked numbers.",
      "Cash out Tk 20: customer -Tk 30.00 (Tk 10 fee); top-up Tk 20: -Tk 22.00; bill pay Tk 100: -Tk 103.00.",
      "Add money Tk 500: the bank's signed callback completes it, wallet +Tk 500.00.",
      "Cash in to a merchant (01911000001): REJECTED MW-CORE-INVALID_COUNTERPARTY; to your own wallet: MW-VAL-012.",
      "History: after the P2P, customer01 shows it as OUT with the Tk 5 fee, customer02 as IN; a failed transfer never shows as IN; history down: 503 MW-SYS-013.",
    ],
    fills: [
      { label: "P2P Tk 10 to customer02", values: { category: "customer", user: "customer01", txnType: "P2P", counterparty: "01711000002", amount: "10", pin: "13579" } },
      { label: "P2P with a wrong PIN", values: { category: "customer", user: "customer01", txnType: "P2P", counterparty: "01711000002", amount: "10", pin: "97531" } },
      { label: "P2P over the limit (6000)", values: { category: "customer", user: "customer01", txnType: "P2P", counterparty: "01711000002", amount: "6000", pin: "13579" } },
      { label: "Cash in by agent01", values: { category: "agent", user: "agent01", txnType: "CASH_IN", counterparty: "01711000001", amount: "20", pin: "13579" } },
      { label: "Cash out to agent01", values: { category: "customer", user: "customer01", txnType: "CASH_OUT", counterparty: "01811000001", amount: "20", pin: "13579" } },
      { label: "Top-up refused (…999)", values: { category: "customer", user: "customer01", txnType: "TOPUP", counterparty: "01711000999", amount: "20", operator: "gp" } },
      { label: "Top-up times out (20.13)", values: { category: "customer", user: "customer01", txnType: "TOPUP", counterparty: "01711000002", amount: "20.13", operator: "gp" } },
      { label: "Bill pay DESCO", values: { category: "customer", user: "customer01", txnType: "BILL_PAY", biller: "DESCO", counterparty: "1234567", amount: "100" } },
      { label: "Add money from the bank", values: { category: "customer", user: "customer01", txnType: "ADD_MONEY_BANK", counterparty: "1234567890123", amount: "500" } },
    ],
  },
  auth: {
    goal: "Wallet authentication at mw-auth on fresh wallets of its own (01711000500..999): OTP, binding, PIN login, second device, PIN change and resets, lockout.",
    steps: [
      "First PIN 48207, Create a fresh wallet: the wallet field fills in.",
      "Log in with the PIN, then Bind a second device: the old one is refused.",
      "Change PIN (current 48207, new 61938), then log in with the old and the new one.",
      "Self-service reset with a new PIN; a care reset is asked and approved in the ops console, then taken up here.",
      "Three wrong PINs: the PIN locks for 1 min with the dev timers.",
    ],
    magic: [
      { value: "first PIN 11111 / 34567 / the wallet's last five digits", meaning: "400 MW-AUTH-044 (REPEATED_DIGIT, SEQUENCE, MSISDN_DIGITS)" },
      { value: "new PIN = current", meaning: "400 MW-AUTH-044 LAST_PIN" },
    ],
    expect: [
      "\"fresh wallet 01711000xyz ready: device bound, first PIN set\".",
      "Second device: the old phone's login answers 401 MW-AUTH-047; newDevice cooling for 24 h; an SMS about the new device.",
      "Wrong PINs: 401 MW-AUTH-040 with 2 and 1 tries left, the third 423 MW-AUTH-041 with retryAfter.",
    ],
    fills: [
      { label: "Good PINs (48207 / 61938)", values: { pin: "48207", newPin: "61938" } },
      { label: "Weak first PIN 11111", values: { pin: "11111" } },
      { label: "Reset to 73052", values: { pin: "48207", newPin: "73052", nidLast4: "4821", dob: "1990-05-17" } },
    ],
  },
  ussd: {
    goal: "A feature phone on any of the four MNOs, each with its own code: robi *269#, bl *270#, teletalk *271# over HTTP, gp *268# over SMPP.",
    steps: [
      "MNO robi (HTTP), Subscriber 01711000001 (the code fills in as *269#), Dial: the Bangla main menu.",
      "Send 9 for English, then 1 (Send Money), 01711000002, 10, 1 (confirm), 13579 (PIN).",
      "Repeat on bl and teletalk (HTTP) and on gp (SMPP: each step replays the session from the dial).",
      "End hangs up; changing the MNO, number or code drops the session too. Dial starts a new one, no reload needed.",
      "Dial robi with gp's *268#: the session ends at once with the wrong-code screen.",
    ],
    magic: [
      { value: "PIN 97531", meaning: "\"Wrong PIN. 2 tries left.\"; three wrong ones lock the PIN on every channel" },
      { value: "number 12345", meaning: "\"Enter a valid mobile number\", the session stays open" },
      { value: "3, 2, 01711000999, 1, 20, 1, 13579", meaning: "\"Recharge failed: Subscriber not eligible for recharge\"" },
      { value: "4, 1, 1234567, 1, 13579", meaning: "\"Tk 667.00 paid for DESCO bill 1234567\"" },
    ],
    expect: [
      "The session ends ([END] in the Transcript) with a confirmation carrying the TxnID; the PIN shows as ****.",
      "On Home: payer -Tk 15, payee +Tk 10; SMS to both.",
    ],
    fills: [
      { label: "robi, customer01", values: { mno: "robi", msisdn: "01711000001", serviceCode: "*269#" } },
      { label: "gp (SMPP), customer01", values: { mno: "gp", msisdn: "01711000001", serviceCode: "*268#" } },
      { label: "bl, customer01", values: { mno: "bl", msisdn: "01711000001", serviceCode: "*270#" } },
      { label: "teletalk, customer01", values: { mno: "teletalk", msisdn: "01711000001", serviceCode: "*271#" } },
      { label: "robi, customer02", values: { mno: "robi", msisdn: "01711000002", serviceCode: "*269#" } },
      { label: "robi with gp's code (refused)", values: { mno: "robi", msisdn: "01711000001", serviceCode: "*268#" } },
    ],
  },
  simulators: {
    goal: "Make the partners and the core fail on purpose, then prove the middleware recovers.",
    steps: [
      "Apply a preset (or set a partner scenario / inject a core fault below).",
      "Run the flow on the App, USSD or Checkout page and check the result there.",
      "Come back and press Reset everything (faults last until reset).",
    ],
    magic: PARTNER_MAGIC,
    expect: [
      "Lost core reply: a step polls until final (was PROCESSING), then COMPLETED, debited once.",
      "Lost refusal: FAILED (MW-TMO-003), nothing moved. Hold commit refused: MANUAL_REVIEW.",
      "OCS outage: UNKNOWN after about a minute with the amount held (a 503 is never a refusal).",
      "Inject: \"fault injected: it applies to the next matching command(s)\"; Clear: \"core-mock faults cleared\".",
    ],
    fills: [
      { label: "Lose the next PostTransfer reply", values: { "core-fault:mode": "DROP_REPLY", "core-fault:commandType": "PostTransfer", "core-fault:times": "1" } },
      { label: "Refuse the next validation", values: { "core-fault:mode": "REJECT", "core-fault:commandType": "ValidateTransaction", "core-fault:times": "1", "core-fault:reasonCode": "INSUFFICIENT_BALANCE" } },
      { label: "Delay PostTransfer 3 s", values: { "core-fault:mode": "DELAY", "core-fault:commandType": "PostTransfer", "core-fault:times": "1", "core-fault:delayMs": "3000" } },
      { label: "OCS: unknown code", values: { "scenario-ocs:forceMode": "UNKNOWN_CODE" } },
      { label: "OCS: 3 s latency", values: { "scenario-ocs:forceMode": "", "scenario-ocs:latencyMs": "3000" } },
      { label: "Bill aggregator refuses", values: { "scenario-billagg:forceMode": "BUSINESS_FAILURE" } },
    ],
  },
  inbox: {
    goal: "Read what mw-notify sent: SMS at the SMSC simulator and email at GreenMail.",
    steps: [
      "Enter the wallet number and/or the email address, press Read (newest first).",
      "Clear every SMS empties the SMSC inbox before a test.",
    ],
    magic: [
      { value: "number ending 999 / 998 / 995", meaning: "delivery report UNDELIV / EXPIRED / none" },
    ],
    expect: [
      "P2P: sender \"sent … fee …\", receiver \"received …\", Bangla by default (UCS2), numbers masked 0171****001, DLR DELIVRD.",
      "Bill pay and add money: an SMS plus an email receipt (multipart text + HTML).",
      "A full number or a PIN never appears in an SMS.",
    ],
    fills: [
      { label: "customer01", values: { msisdn: "01711000001", email: "customer01@example.test" } },
      { label: "customer02", values: { msisdn: "01711000002", email: "customer02@example.test" } },
      { label: "Email only (customer01)", values: { msisdn: "", email: "customer01@example.test" } },
    ],
  },
  checkout: {
    goal: "A web shop takes a Bits payment: session, hosted page, OTP + PIN, signed redirect, IPN.",
    steps: [
      "Amount 25, language en, Create checkout session: HTTP 201, OPEN.",
      "View the hosted page (API; the answer shows under Result), then Send OTP to the payer wallet (the OTP is filled in from the SMS).",
      "PIN 13579, Pay: PAID with a txnId.",
      "Return to the shop (signed redirect), then Shop server: session status and IPNs.",
    ],
    magic: [
      { value: "OTP 123 or PIN 12", meaning: "\"the OTP is 6 digits, the PIN 4 to 6\", nothing sent" },
      { value: "an edited OTP", meaning: "401 (MW-AUTH-010), nothing paid" },
      { value: "Pay twice", meaning: "the second answers 409, nothing paid again" },
      { value: "PIN 97531", meaning: "MW-AUTH-040; the session stays OPEN (send the OTP again)" },
    ],
    expect: [
      "Return: HTTP 303 to the shop's ok URL, status PAID, signature valid.",
      "Shop server: session PAID, one IPN answered 200 with a valid signature.",
      "With the IPN preset (fails the next 2): IPNs answered 503, 503, then 200.",
    ],
    fills: [
      { label: "Tk 25 by customer01 (en)", values: { amount: "25", locale: "en", wallet: "01711000001", pin: "13579" } },
      { label: "Tk 25 by customer02 (bn)", values: { amount: "25", locale: "bn", wallet: "01711000002", pin: "13579" } },
      { label: "Wrong PIN", values: { pin: "97531" } },
    ],
  },
  bulk: {
    goal: "An organisation's bulk disbursement with maker-checker, and the service provider query.",
    steps: [
      "Portal user org-maker01, Rows 5, Tk each 1.00, generate the sample CSV, then upload it.",
      "Switch to org-checker01, Approve, then Batch status until COMPLETED.",
      "Result file, Transactions and List batches show the outcome.",
      "SP query: org-checker02, empty batch id, Transactions (role PAYEE).",
    ],
    magic: [
      { value: "approve as org-maker01", meaning: "403 MW-AUTH-002 (needs the checker role)" },
      { value: "approve as org-checker02", meaning: "404 MW-VAL-003 (another org cannot see the batch)" },
      { value: "sp-viewer01", meaning: "403 MW-AUTH-002 \"Token carries no organisation\"" },
      { value: "CSV phone,amt / 123,abc", meaning: "400 MW-VAL-031 with the row details" },
    ],
    expect: [
      "Upload: a batch id (filled in) and its status; after approval it runs to COMPLETED (or COMPLETED_WITH_EXCEPTIONS).",
      "Pause stops the completed count growing; Resume (checker) continues.",
      "Reject with a reason: REJECTED, nothing paid.",
    ],
    fills: [
      { label: "Maker, 5 rows of Tk 1", values: { user: "org-maker01", rows: "5", taka: "1.00" } },
      { label: "Checker org-checker01", values: { user: "org-checker01" } },
      { label: "Broken CSV", values: { user: "org-maker01", csv: "phone,amt\n123,abc" } },
      { label: "SP query as org-checker02", values: { user: "org-checker02", batchId: "" } },
    ],
  },
  transactions: {
    goal: "Find any transaction and see what the middleware did: status, amounts, parties, the saga timeline, partner calls and the audit trail.",
    steps: [
      "Search by txnId (paste one, or click a txnId in any result) or by MSISDN, optionally with a status or a type.",
      "Click a row to open its detail.",
      "Read the timeline: one line per state change (from -> to on trigger) and per core command (sent Nx -> result).",
    ],
    magic: [
      { value: "txnId abc", meaning: "\"the txnId must be a UUID\" (nothing sent)" },
      { value: "MSISDN 12ab", meaning: "\"the MSISDN must be a phone number\"" },
      { value: "00000000-0000-4000-8000-000000000000", meaning: "an unknown id: MW-VAL-003" },
    ],
    expect: [
      "Search: the list newest first, numbers masked (0171****001).",
      "Detail of a P2P: COMPLETED, Tk 10 with fee Tk 5, the timeline ends in COMPLETED, \"No partner calls.\", audit entries.",
      "A top-up, bill pay or add money lists its partner calls; a refused one shows its error code.",
      "The explorer signs in as ops-e2e-maker01 (realm ops, viewer + ops-maker): MSISDNs stay masked.",
    ],
    fills: [
      { label: "customer01's transactions", values: { txnId: "", msisdn: "01711000001", status: "", txnType: "" } },
      { label: "customer01's top-ups", values: { txnId: "", msisdn: "01711000001", status: "", txnType: "TOPUP" } },
      { label: "Every MANUAL_REVIEW", values: { txnId: "", msisdn: "", status: "MANUAL_REVIEW", txnType: "" } },
      { label: "Unknown id", values: { txnId: "00000000-0000-4000-8000-000000000000", msisdn: "", status: "", txnType: "" } },
    ],
  },
  credentials: {
    goal: "Every dev/test login and key without a terminal: passwords, live one-time codes, PINs, OTPs.",
    steps: [
      "Copy a persona's username, password and live TOTP code (it renews every 30 s) to sign in to the ops console or the org portal.",
      "Every ops and portal user has a pre-set code: no authenticator app is needed.",
      "Look up the latest OTP an MSISDN received.",
    ],
    expect: [
      "Every value is a fixed fake dev value from deploy/.env; a missing one says \"not set\".",
      "A code copied in its 30 s step signs in; Keycloak refuses one code used twice by the same user.",
    ],
  },
  "seed-data": {
    goal: "What mw-seeder created: personas, wallets, organisations, partners, menus and templates, with their logins.",
    steps: [
      "Filter a table, copy a login or a PIN from its row.",
      "Look up a core-mock generated wallet by number.",
    ],
    expect: [
      "Without the seed profile the page says \"run the seed profile\".",
      "Balances are live from core-mock.",
    ],
  },
};
