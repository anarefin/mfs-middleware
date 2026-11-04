package mfs.mw.ussd.dto;

/** Menu node types (spec 9.2). */
public enum NodeType {
  /** Numbered options. */
  MENU,
  /** Free input checked by a validator (msisdn, amount, pin, numeric, regex). */
  INPUT,
  /** Numbered options that confirm or cancel what was collected. */
  CONFIRM,
  /** Invokes a flow with a mapping from collected inputs to the canonical request. */
  ACTION,
  /** Numbered options listed by a lookup (e.g. billers). */
  DYNAMIC,
  /** Last screen; the session ends. */
  END
}
