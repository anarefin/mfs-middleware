package mfs.mw.simulators.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import mfs.mw.simulators.dto.SimMode;
import mfs.mw.simulators.util.Sensitive;
import mfs.mw.simulators.util.SensitiveType;

/**
 * What the simulated bank gateway really did with one transfer (ticket 117d): the answer to a status
 * inquiry and the body of its callback. {@code pending}: answered 202 PENDING, the outcome follows by
 * callback (the status inquiry reports it at once).
 */
public record BankTransfer(
    String bank,
    String transactionId,
    @Sensitive(SensitiveType.ACCOUNT) String accountNumber,
    BigDecimal amount,
    String direction,
    String status,
    String code,
    String message,
    String bankRef,
    SimMode mode,
    boolean pending) {

  public static final String SUCCESS = "SUCCESS";
  public static final String FAILED = "FAILED";
  public static final String CREDIT = "CREDIT";

  public boolean succeeded() {
    return SUCCESS.equals(status);
  }

  /** The bank's wire body of the outcome ({@code {transactionId, status, code, message, bankRef}}). */
  public Map<String, Object> body() {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("transactionId", transactionId);
    b.put("status", status);
    b.put("code", code);
    b.put("message", message);
    b.put("bankRef", bankRef);
    return b;
  }

  /** The 202 body: accepted, outcome by callback. */
  public Map<String, Object> pendingBody() {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("transactionId", transactionId);
    b.put("status", "PENDING");
    b.put("code", "100");
    b.put("message", "Accepted, outcome by callback");
    b.put("bankRef", bankRef);
    return b;
  }
}
