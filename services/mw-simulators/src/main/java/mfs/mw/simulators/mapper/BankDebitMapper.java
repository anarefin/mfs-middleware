package mfs.mw.simulators.mapper;

import mfs.mw.simulators.dto.BankDebitRequest;
import mfs.mw.simulators.dto.BankDebitResponse;
import mfs.mw.simulators.dto.SimMode;
import mfs.mw.simulators.service.BankDebitRecord;
import org.springframework.stereotype.Component;

/** C2W bank wire bodies; code {@code 000} debited, {@code 100} accepted, {@code 4002} refused. */
@Component
public class BankDebitMapper {

  public static final String DEBITED = "000";
  public static final String ACCEPTED = "100";
  public static final String DEBIT_REFUSED = "4002";
  public static final String UNMAPPED = "9876";

  /** Only business failure and unknown code refuse the debit; every other mode applies it. */
  public BankDebitRecord toRecord(BankDebitRequest req, SimMode mode, String ref) {
    return switch (mode) {
      case BUSINESS_FAILURE -> record(req, "FAILED", DEBIT_REFUSED, "Debit refused by the bank", ref, mode);
      case UNKNOWN_CODE -> record(req, "FAILED", UNMAPPED, "Unmapped bank error", ref, mode);
      default -> record(req, "SUCCESS", DEBITED, "Account debited", ref, mode);
    };
  }

  private static BankDebitRecord record(
      BankDebitRequest req, String status, String code, String message, String ref, SimMode mode) {
    return new BankDebitRecord(req.transactionId(), req.accountNumber(), req.amount(), status, code, message, ref, mode);
  }

  /** The 202 answer (and stale callback): accepted, the outcome follows by callback. */
  public BankDebitResponse pending(BankDebitRecord r) {
    return new BankDebitResponse(
        r.transactionId(), "PENDING", ACCEPTED, "Accepted; the outcome follows by callback", r.debitRef());
  }

  public BankDebitResponse toResponse(BankDebitRecord r) {
    return new BankDebitResponse(r.transactionId(), r.status(), r.code(), r.message(), r.debitRef());
  }

  public BankDebitResponse notFound(String transactionId) {
    return new BankDebitResponse(transactionId, "NOT_FOUND", "4040", "Unknown transaction", null);
  }

  public BankDebitResponse unavailable(String transactionId) {
    return new BankDebitResponse(transactionId, "UNAVAILABLE", "5030", "Service unavailable", null);
  }

  public BankDebitResponse internalError(String transactionId) {
    return new BankDebitResponse(transactionId, "ERROR", "5000", "Internal error", null);
  }
}
