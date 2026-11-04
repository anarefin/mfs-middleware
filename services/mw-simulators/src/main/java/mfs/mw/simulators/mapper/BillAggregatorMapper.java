package mfs.mw.simulators.mapper;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import mfs.mw.simulators.dto.BillFetchRequest;
import mfs.mw.simulators.dto.BillPaymentRequest;
import mfs.mw.simulators.dto.BillPaymentResponse;
import mfs.mw.simulators.dto.BillResponse;
import mfs.mw.simulators.dto.SimMode;
import mfs.mw.simulators.service.BillPaymentRecord;
import org.springframework.stereotype.Component;

/** Aggregator wire bodies; code {@code 000} success, {@code 3001..3004} business refusals. */
@Component
public class BillAggregatorMapper {

  public static final String BILL_NOT_FOUND = "3001";
  public static final String BILL_ALREADY_PAID = "3002";
  public static final String PAYMENT_REFUSED = "3003";
  public static final String UNKNOWN_BILLER = "3004";
  /** Callback-only payment accepted (202 PENDING). */
  public static final String ACCEPTED = "001";

  private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM");

  private final Clock clock;

  public BillAggregatorMapper(Clock clock) {
    this.clock = clock;
  }

  /** Amount due = 100 taka + the bill number's last three digits (0 when they are not digits). */
  public BillResponse due(BillFetchRequest req) {
    String n = req.billNumber();
    String tail = n.length() > 3 ? n.substring(n.length() - 3) : n;
    int digits = tail.chars().allMatch(Character::isDigit) ? Integer.parseInt(tail) : 0;
    LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    return new BillResponse(
        req.billerCode(),
        n,
        "DUE",
        "000",
        "Bill found",
        (100 + digits) + ".00",
        today.plusDays(10).toString(),
        today.format(PERIOD));
  }

  public BillResponse refused(BillFetchRequest req, String code, String message) {
    return new BillResponse(
        req.billerCode(), req.billNumber(), "FAILED", code, message, null, null, null);
  }

  public BillResponse unavailable(BillFetchRequest req) {
    return new BillResponse(
        req.billerCode(), req.billNumber(), "UNAVAILABLE", "5030", "Service unavailable", null, null, null);
  }

  /** Only business failure and unknown code fail the payment; every other mode applies it. */
  public BillPaymentRecord toRecord(BillPaymentRequest req, SimMode mode, String ref) {
    return toRecord(req, mode, ref, false);
  }

  /** As {@link #toRecord(BillPaymentRequest, SimMode, String)}; {@code callbackOnly} answers 202 PENDING. */
  public BillPaymentRecord toRecord(BillPaymentRequest req, SimMode mode, String ref, boolean callbackOnly) {
    return switch (mode) {
      case BUSINESS_FAILURE ->
          record(req, "FAILED", PAYMENT_REFUSED, "Payment refused by biller", ref, mode, callbackOnly);
      case UNKNOWN_CODE -> record(req, "FAILED", "9876", "Unmapped aggregator error", ref, mode, callbackOnly);
      default -> record(req, "SUCCESS", "000", "Bill paid", ref, mode, callbackOnly);
    };
  }

  /** The 202 answer (and stale callback) of a callback-only payment: accepted, outcome to follow. */
  public BillPaymentResponse pending(BillPaymentRecord r) {
    return new BillPaymentResponse(r.transactionId(), "PENDING", ACCEPTED, "Accepted; the outcome follows by callback", r.paymentRef());
  }

  private static BillPaymentRecord record(
      BillPaymentRequest req, String status, String code, String message, String ref, SimMode mode,
      boolean callbackOnly) {
    return new BillPaymentRecord(
        req.transactionId(),
        req.billerCode(),
        req.billNumber(),
        req.amount(),
        status,
        code,
        message,
        ref,
        mode,
        callbackOnly);
  }

  public BillPaymentResponse toResponse(BillPaymentRecord r) {
    return new BillPaymentResponse(r.transactionId(), r.status(), r.code(), r.message(), r.paymentRef());
  }

  public BillPaymentResponse notFound(String transactionId) {
    return new BillPaymentResponse(transactionId, "NOT_FOUND", "4040", "Unknown transaction", null);
  }

  public BillPaymentResponse unavailable(String transactionId) {
    return new BillPaymentResponse(transactionId, "UNAVAILABLE", "5030", "Service unavailable", null);
  }

  public BillPaymentResponse internalError(String transactionId) {
    return new BillPaymentResponse(transactionId, "ERROR", "5000", "Internal error", null);
  }
}
