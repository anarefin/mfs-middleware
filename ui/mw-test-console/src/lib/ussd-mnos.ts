// The MNOs the USSD handset can dial through (pure). Each has its own short code, as mw-ussd's
// mw.ussd.mno.<mno>.short-codes: a BEGIN with another MNO's code ends at once with the wrong-code
// screen. gp runs over SMPP (mw-ussd is bound to the simulator), the others over HTTP (APISIX
// /ussd/<mno>, basic auth as ussd-<mno>).

export type UssdTransport = "HTTP" | "SMPP";
export type UssdMno = { id: string; name: string; transport: UssdTransport; code: string };

export const USSD_MNOS: readonly UssdMno[] = [
  { id: "robi", name: "Robi", transport: "HTTP", code: "*269#" },
  { id: "gp", name: "Grameenphone", transport: "SMPP", code: "*268#" },
  { id: "bl", name: "Banglalink", transport: "HTTP", code: "*270#" },
  { id: "teletalk", name: "Teletalk", transport: "HTTP", code: "*271#" },
];

export const DEFAULT_USSD_MNO = "robi";

export function mnoOf(id: string): UssdMno | undefined {
  return USSD_MNOS.find((m) => m.id === id);
}

/** The option text of the MNO picker, e.g. "robi (HTTP) *269#". */
export function mnoLabel(m: UssdMno): string {
  return `${m.id} (${m.transport}) ${m.code}`;
}
