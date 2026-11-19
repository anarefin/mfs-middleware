package mfs.mw.simulators.service;

import mfs.mw.simulators.dto.SimMode;
import mfs.mw.simulators.util.Sensitive;
import mfs.mw.simulators.util.SensitiveType;

/** The real outcome of a top-up, as the OCS keeps it whatever the caller saw. */
public record TopupRecord(
    String transactionId,
    @Sensitive(SensitiveType.MSISDN) String msisdn,
    String amount,
    String status,
    String code,
    String message,
    String ocsRef,
    SimMode mode) {

  /** This record with {@code newCode} (a mock-control BUSINESS_ERROR code); itself when null. */
  public TopupRecord withCode(String newCode) {
    return newCode == null ? this : new TopupRecord(transactionId, msisdn, amount, status, newCode, message, ocsRef, mode);
  }
}
