package mfs.mw.recon.dto;

/** FRS REC-04 result classes. A missing core posting is {@link #STATUS_MISMATCH} with detail LEDGER_MISSING. */
public enum ReconClass {
  MATCHED,
  MISSING_IN_PARTNER,
  MISSING_IN_UPAY,
  AMOUNT_MISMATCH,
  STATUS_MISMATCH
}
