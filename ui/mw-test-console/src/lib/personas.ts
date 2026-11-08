// The users the test console signs in as: the realm imports' test users (deploy/keycloak/realms),
// whose wallets core-mock seeds (PIN 1234). Any other user of the realm can be typed in as well
// (e.g. mw-seeder's seed-customer01 or seed-run-customer01).

export type AppPersona = { user: string; msisdn: string; role: "customer" | "agent" | "merchant"; label: string };

export const APP_PERSONAS: readonly AppPersona[] = [
  { user: "customer01", msisdn: "01711000001", role: "customer", label: "customer01 (customer)" },
  { user: "customer02", msisdn: "01711000002", role: "customer", label: "customer02 (customer)" },
  { user: "agent01", msisdn: "01811000001", role: "agent", label: "agent01 (agent: cash in)" },
  { user: "merchant01", msisdn: "01911000001", role: "merchant", label: "merchant01 (merchant: Demo Shop)" },
  { user: "merchant02", msisdn: "01911000002", role: "merchant", label: "merchant02 (merchant)" },
];

export type PortalPersona = { user: string; label: string };

export const PORTAL_PERSONAS: readonly PortalPersona[] = [
  { user: "org-maker01", label: "org-maker01 (ORG001 maker: uploads)" },
  { user: "org-checker01", label: "org-checker01 (ORG001 checker: approves)" },
  { user: "org-makerchecker01", label: "org-makerchecker01 (ORG001 maker and checker)" },
  { user: "org-checker02", label: "org-checker02 (ORG002 checker: other org; SP query as payee)" },
  { user: "sp-viewer01", label: "sp-viewer01 (no organisation: refused, 403)" },
];

/** Every wallet the home page shows (core-mock's seed). */
export const SEEDED_WALLETS: readonly { msisdn: string; who: string }[] = [
  ...APP_PERSONAS.map((p) => ({ msisdn: p.msisdn, who: p.label })),
  { msisdn: "01711000003", who: "core-mock seeded customer" },
];

export const TXN_TYPES = ["P2P", "CASH_IN", "CASH_OUT", "MERCHANT_PAY", "TOPUP", "BILL_PAY", "ADD_MONEY_BANK"] as const;
export type TxnType = (typeof TXN_TYPES)[number];

/** What each flow needs, its default counterparty and what to type for the magic outcomes of the simulators. */
export const FLOW_HELP: Record<TxnType, { title: string; who: string; counterparty?: string; hint: string }> = {
  P2P: { title: "Send money (P2P)", who: "customer", counterparty: "01711000002",
    hint: "Payer -> payee wallet. Wrong PIN 9999 is REJECTED; an unknown payee or more than the balance fails at the core." },
  CASH_IN: { title: "Cash in (agent -> customer)", who: "agent", counterparty: "01711000001",
    hint: "Sign in as agent01. The customer's wallet is credited, the agent's debited." },
  CASH_OUT: { title: "Cash out (customer -> agent)", who: "customer", counterparty: "01811000001",
    hint: "The counterparty must be an agent wallet (01811000001); a customer wallet is refused by the core." },
  MERCHANT_PAY: { title: "Merchant payment (in-app)", who: "customer", counterparty: "01911000001",
    hint: "The counterparty must be a merchant wallet (01911000001); see it under merchant01's collections." },
  TOPUP: { title: "Mobile top-up (OCS)", who: "customer", counterparty: "01711000002",
    hint: "Number ending 999: OCS refuses (hold released). Amount .13 (e.g. 20.13): OCS times out -> UNKNOWN -> status inquiry commits. .77: 500 after send. .99: unknown code." },
  BILL_PAY: { title: "Bill payment (aggregator)", who: "customer", counterparty: "1234567",
    hint: "Fetch the bill first (due = Tk 100 + last 3 digits). Bill ending 404: not found; 000: already paid; 999: refused by the biller. Amount .13: timeout -> TSQ." },
  ADD_MONEY_BANK: { title: "Add money from bank (C2W)", who: "customer", counterparty: "1234567890123",
    hint: "The bank debits the account and calls back. Account ending 999: refused. Amount .13: bank times out, no callback, the status inquiry resolves it. Needs the c2wbank callback preset (Simulators) unless one is already set." },
};
