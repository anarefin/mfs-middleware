package mfs.mw.compliance.dto;

/** A KYC application's state (ticket 124). FAILED (EC timeout, breaker open, EC error) may be submitted again. */
public enum KycStatus {
  SUBMITTED,
  EC_CALLED,
  MATCHED,
  MISMATCHED,
  FAILED,
  /** Held for an operator (ticket 134): e.g. the NID is already verified for another wallet. No EC call, no level. */
  MANUAL_REVIEW,
  APPROVED
}
