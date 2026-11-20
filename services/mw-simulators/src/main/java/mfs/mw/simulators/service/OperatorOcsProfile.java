package mfs.mw.simulators.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One operator OCS wire profile (ticket 112a). The shapes are the recorded payloads the telco
 * adapter's JSONata mappings read. A seeded postpaid account is the bill fetch's known subscriber.
 */
public enum OperatorOcsProfile {
  GP(
      "ocs-gp",
      422,
      "code",
      "gpRef",
      "subscriber",
      "rechargeAmount",
      "clientTxnId",
      "reverseTxnId",
      "GP000",
      "GP1001",
      "Subscriber barred",
      "GP1404",
      "GP4091",
      "01711000021",
      "450.00",
      "50.00",
      "GPB-01711000021"),
  ROBI(
      "ocs-robi",
      400,
      "rc",
      "robiTxn",
      "msisdn",
      "amt",
      "ref",
      "reverseRef",
      "0",
      "RB77",
      "Barred",
      "RB1404",
      "RB4091",
      "01811000021",
      "320.50",
      "20.00",
      "RBB-01811000021"),
  TELETALK(
      "ocs-teletalk",
      422,
      "errCode",
      "ttId",
      "mobile",
      "taka",
      "transactionRef",
      "reversalRef",
      "00",
      "TT09",
      "Not eligible",
      "TT1404",
      "TT4091",
      "01511000021",
      "180.00",
      "10.00",
      "TTB-01511000021"),
  BL(
      "ocs-bl",
      403,
      "faultCode",
      "blId",
      "number",
      "value",
      "id",
      "reversalId",
      "0",
      "BL42",
      "Rejected",
      "BL1404",
      "BL4091",
      "01911000021",
      "275.75",
      "25.00",
      "BLB-01911000021");

  private final String party;
  private final int rejectStatus;
  private final String codeField;
  private final String refField;
  private final String msisdnField;
  private final String amountField;
  private final String txnField;
  private final String reverseField;
  private final String successCode;
  private final String barredCode;
  private final String barredMessage;
  private final String unknownBillCode;
  private final String notReversibleCode;
  private final String seedMsisdn;
  private final String seedOutstanding;
  private final String seedMinimum;
  private final String seedBillRef;

  OperatorOcsProfile(
      String party,
      int rejectStatus,
      String codeField,
      String refField,
      String msisdnField,
      String amountField,
      String txnField,
      String reverseField,
      String successCode,
      String barredCode,
      String barredMessage,
      String unknownBillCode,
      String notReversibleCode,
      String seedMsisdn,
      String seedOutstanding,
      String seedMinimum,
      String seedBillRef) {
    this.party = party;
    this.rejectStatus = rejectStatus;
    this.codeField = codeField;
    this.refField = refField;
    this.msisdnField = msisdnField;
    this.amountField = amountField;
    this.txnField = txnField;
    this.reverseField = reverseField;
    this.successCode = successCode;
    this.barredCode = barredCode;
    this.barredMessage = barredMessage;
    this.unknownBillCode = unknownBillCode;
    this.notReversibleCode = notReversibleCode;
    this.seedMsisdn = seedMsisdn;
    this.seedOutstanding = seedOutstanding;
    this.seedMinimum = seedMinimum;
    this.seedBillRef = seedBillRef;
  }

  public String party() {
    return party;
  }

  public int rejectStatus() {
    return rejectStatus;
  }

  public String codeField() {
    return codeField;
  }

  public String refField() {
    return refField;
  }

  public String msisdnField() {
    return msisdnField;
  }

  public String amountField() {
    return amountField;
  }

  public String txnField() {
    return txnField;
  }

  public String reverseField() {
    return reverseField;
  }

  public String successCode() {
    return successCode;
  }

  public String barredCode() {
    return barredCode;
  }

  public String barredMessage() {
    return barredMessage;
  }

  public String unknownBillCode() {
    return unknownBillCode;
  }

  public String notReversibleCode() {
    return notReversibleCode;
  }

  public String seedMsisdn() {
    return seedMsisdn;
  }

  public String seedOutstanding() {
    return seedOutstanding;
  }

  public String seedMinimum() {
    return seedMinimum;
  }

  public String seedBillRef() {
    return seedBillRef;
  }

  public static OperatorOcsProfile byParty(String party) {
    for (OperatorOcsProfile profile : values()) {
      if (profile.party.equals(party)) {
        return profile;
      }
    }
    return null;
  }

  /** Entries a successful recharge always carries (the operator reference is extra). */
  public Map<String, Object> successShape(String code) {
    return switch (this) {
      case GP -> Map.of("status", "SUCCESS", "code", code);
      case ROBI -> Map.of("ok", true, "rc", code);
      case TELETALK -> Map.of("accepted", true, "errCode", code);
      case BL -> Map.of("outcome", "OK", "faultCode", code);
    };
  }

  public Map<String, Object> failureShape(String code, String message) {
    return switch (this) {
      case GP -> Map.of("status", "FAILED", "code", code, "message", message);
      case ROBI -> Map.of("ok", false, "rc", code, "text", message);
      case TELETALK -> Map.of("accepted", false, "errCode", code, "errText", message);
      case BL -> Map.of("outcome", "NOK", "faultCode", code, "faultText", message);
    };
  }

  public Map<String, Object> billShape(String outstanding, String minimum, String billRef) {
    return switch (this) {
      case GP -> Map.of("status", "DUE", "code", successCode, "outstanding", outstanding, "minimum", minimum, "billRef", billRef);
      case ROBI -> Map.of("ok", true, "rc", successCode, "due", outstanding, "minPay", minimum, "invoice", billRef);
      case TELETALK ->
          Map.of("accepted", true, "errCode", successCode, "outstandingTaka", outstanding, "minimumTaka", minimum, "billId", billRef);
      case BL -> Map.of("outcome", "DUE", "faultCode", successCode, "dueAmount", outstanding, "minAmount", minimum, "billId", billRef);
    };
  }

  public Map<String, Object> success(String ref) {
    Map<String, Object> body = new LinkedHashMap<>(successShape(successCode));
    body.put(refField, ref);
    return Map.copyOf(body);
  }

  public Map<String, Object> failure(String code, String message) {
    return failureShape(code, message);
  }

  public Map<String, Object> bill(String outstanding, String minimum, String billRef) {
    return billShape(outstanding, minimum, billRef);
  }

  public Map<String, Object> notFound() {
    return switch (this) {
      case GP -> Map.of("status", "NOT_FOUND", "code", "GP4040", "message", "Unknown transaction");
      case ROBI -> Map.of("ok", false, "rc", "RB4040", "text", "Unknown transaction");
      case TELETALK -> Map.of("accepted", false, "errCode", "TT4040", "errText", "Unknown transaction");
      case BL -> Map.of("outcome", "UNKNOWN", "faultCode", "BL4040", "faultText", "Unknown transaction");
    };
  }

  public Map<String, Object> reversed(String ref) {
    return switch (this) {
      case GP -> Map.of("status", "REVERSED", "code", successCode, refField, ref);
      case ROBI -> Map.of("ok", true, "rc", successCode, refField, ref);
      case TELETALK -> Map.of("accepted", true, "errCode", successCode, refField, ref);
      case BL -> Map.of("outcome", "REVERSED", "faultCode", successCode, refField, ref);
    };
  }
}
