// App flows' pickers (pure): the counterparties each flow offers (seeded wallets of the right role,
// the simulators' magic numbers) and the scenario chips that fill amount, counterparty and PIN for
// one outcome. The magic values are the simulators' (same as lib/how-to.ts PARTNER_MAGIC).

import { usersOf, userLabel, type AppUser } from "./app-users";
import { BILLERS, protocolOf, type BillerItem } from "./seed-catalogue";
import { FLOW_HELP, type TxnType } from "./personas";

export type Option = { value: string; label: string };
export type Chip = { label: string; tone: "good" | "bad" | "warn"; values: { counterparty?: string; amount: string; pin: string } };

export const GOOD_PIN = "13579";
export const WRONG_PIN = "97531";
export const OPERATORS = ["gp", "robi", "banglalink", "teletalk"] as const;
/** A biller option's text: code, name and the catalogue's protocol (none for a code the catalogue does not know). */
export function billerLabel(b: BillerItem): string {
  const p = protocolOf(b.code);
  return `${b.code}, ${b.name}${p ? ` · ${p}` : ""}`;
}
export const BILLER_OPTIONS: readonly Option[] = BILLERS.map((b) => ({ value: b.code, label: billerLabel(b) }));
/** The biller a bill payment starts at. */
export const DEFAULT_BILLER = "DESCO";
/** DESCO when the list has it, else the list's first biller. */
export const defaultBiller = (list: readonly BillerItem[]): string =>
  list.some((b) => b.code === DEFAULT_BILLER) ? DEFAULT_BILLER : list[0]?.code ?? DEFAULT_BILLER;
/** The "Custom…" entry of a counterparty select: the text box beside it holds the value. */
export const CUSTOM = "__custom";

export const DEFAULT_AMOUNT: Record<TxnType, string> = {
  P2P: "10", CASH_IN: "20", CASH_OUT: "20", MERCHANT_PAY: "25", TOPUP: "20", BILL_PAY: "100", ADD_MONEY_BANK: "500",
};

export const COUNTERPARTY_LABEL: Record<TxnType, string> = {
  P2P: "Send to (customer wallet)", CASH_IN: "Customer wallet (credited)", CASH_OUT: "Agent wallet", MERCHANT_PAY: "Merchant wallet",
  TOPUP: "Number to top up", BILL_PAY: "Bill number", ADD_MONEY_BANK: "Bank account (debited)",
};

const wallets = (users: readonly AppUser[], role: AppUser["role"], me?: string): Option[] =>
  usersOf(users, role).filter((u) => u.msisdn !== me).map((u) => ({ value: u.msisdn, label: userLabel(u) }));

/** The counterparties of a flow for the signed-in wallet `me` (never itself), magic outcomes included. */
export function counterpartyOptions(type: TxnType, me: string | undefined, users: readonly AppUser[]): Option[] {
  switch (type) {
    case "P2P": case "CASH_IN": return wallets(users, "customer", me);
    case "CASH_OUT": return wallets(users, "agent", me);
    case "MERCHANT_PAY": return wallets(users, "merchant", me);
    case "TOPUP": return [...wallets(users, "customer"), { value: "01711000999", label: "01711000999: ending 999, the OCS refuses" }];
    case "BILL_PAY": return [
      { value: "1234567", label: "1234567: a bill due (Tk 100 + last 3 digits)" },
      { value: "1234404", label: "1234404: ending 404, not found" },
      { value: "1234000", label: "1234000: ending 000, already paid" },
      { value: "1234999", label: "1234999: ending 999, refused by the biller" },
    ];
    case "ADD_MONEY_BANK": return [
      { value: "1234567890123", label: "1234567890123: the bank debits and calls back" },
      { value: "1234567890999", label: "1234567890999: ending 999, the bank refuses" },
    ];
  }
}

/** The flow's default counterparty: FLOW_HELP's when offered (and not me), else the first option. */
export function defaultCounterparty(type: TxnType, me: string | undefined, users: readonly AppUser[]): string {
  const opts = counterpartyOptions(type, me, users);
  const d = FLOW_HELP[type].counterparty;
  if (d && d !== me && (opts.some((o) => o.value === d) || opts.length === 0)) return d;
  return opts[0]?.value ?? "";
}

/** The scenario chips of a flow: only the outcomes it supports. */
export function scenarios(type: TxnType, cp: string): Chip[] {
  const amount = DEFAULT_AMOUNT[type];
  const happy: Chip = { label: "Happy path", tone: "good", values: { counterparty: cp, amount, pin: GOOD_PIN } };
  const wrongPin: Chip = { label: "Wrong PIN (97531)", tone: "bad", values: { counterparty: cp, amount, pin: WRONG_PIN } };
  const limit: Chip = { label: "Over the per-txn limit (6000)", tone: "bad", values: { counterparty: cp, amount: "6000", pin: GOOD_PIN } };
  const at = (label: string, tone: Chip["tone"], v: Partial<Chip["values"]>): Chip => ({ label, tone, values: { counterparty: cp, amount, pin: GOOD_PIN, ...v } });
  switch (type) {
    case "P2P": case "CASH_OUT": case "MERCHANT_PAY": return [happy, wrongPin, limit];
    case "CASH_IN": return [happy, wrongPin, limit, at("Merchant as customer: invalid counterparty", "bad", { counterparty: "01911000001" })];
    case "TOPUP": return [happy, wrongPin,
      at("OCS refuses (…999)", "bad", { counterparty: "01711000999" }),
      at("Timeout -> status inquiry (.13)", "warn", { amount: "20.13" }),
      at("500 after send (.77)", "warn", { amount: "20.77" })];
    case "BILL_PAY": return [at("Happy path", "good", { counterparty: "1234567" }), wrongPin,
      at("Biller refuses (…999)", "bad", { counterparty: "1234999" }),
      at("Bill not found (…404)", "bad", { counterparty: "1234404" }),
      at("Already paid (…000)", "bad", { counterparty: "1234000" }),
      at("Timeout -> status inquiry (.13)", "warn", { counterparty: "1234567", amount: "100.13" })];
    case "ADD_MONEY_BANK": return [at("Happy path", "good", { counterparty: "1234567890123" }), wrongPin,
      at("Bank refuses (…999)", "bad", { counterparty: "1234567890999" }),
      at("Timeout -> status inquiry (.13)", "warn", { counterparty: "1234567890123", amount: "500.13" })];
  }
}
