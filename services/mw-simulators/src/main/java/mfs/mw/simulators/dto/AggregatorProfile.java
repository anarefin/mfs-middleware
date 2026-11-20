package mfs.mw.simulators.dto;

import java.util.List;
import java.util.Map;

/**
 * One bill aggregator profile of {@code BillAggregatorMockController} (ticket 114): the wire dialect of
 * {@code deploy/partner-routes/aggregators/generate.mjs} (keep the two in step). {@code fields} maps the
 * semantic names (billerCode, billNumber, amount, dueDate, period, status, reference, paymentRef,
 * reversalId, reversalRef, code, message) to the profile's own; {@code words} the states (unpaid, paid,
 * pending, failed, reversed); {@code codes} the refusals (NOT_FOUND, PAID, REFUSED, UNKNOWN_BILLER,
 * NOT_PAID, AMOUNT_MISMATCH); {@code envelope} the JSON wrapper key of requests and replies, else null.
 */
public record AggregatorProfile(
    String id,
    Protocol protocol,
    Auth auth,
    String envelopeRequest,
    String envelopeReply,
    Map<String, String> fields,
    Map<String, String> words,
    Map<String, String> codes) {

  public enum Protocol {
    REST,
    SOAP
  }

  public enum Auth {
    HMAC_KEY_ID,
    OAUTH2_CC,
    WS_SECURITY,
    BASIC
  }

  public String field(String name) {
    return fields.getOrDefault(name, name);
  }

  public String word(String name) {
    return words.get(name);
  }

  public String code(String name) {
    return codes.get(name);
  }

  private static Map<String, String> codes(String prefix, String... six) {
    List<String> names = List.of("NOT_FOUND", "PAID", "REFUSED", "UNKNOWN_BILLER", "NOT_PAID", "AMOUNT_MISMATCH");
    Map<String, String> m = new java.util.LinkedHashMap<>();
    for (int i = 0; i < names.size(); i++) {
      m.put(names.get(i), prefix + six[i]);
    }
    return Map.copyOf(m);
  }

  private static Map<String, String> words(String unpaid, String paid, String pending, String failed, String reversed) {
    return Map.of("unpaid", unpaid, "paid", paid, "pending", pending, "failed", failed, "reversed", reversed);
  }

  /** The four aggregators of ticket 114. */
  public static final List<AggregatorProfile> ALL = List.of(
      new AggregatorProfile("sslcommerz", Protocol.REST, Auth.HMAC_KEY_ID, null, null,
          Map.ofEntries(Map.entry("billerCode", "biller_code"), Map.entry("billNumber", "bill_no"),
              Map.entry("dueDate", "due_date"), Map.entry("period", "bill_period"), Map.entry("reference", "tran_id"),
              Map.entry("paymentRef", "val_id"), Map.entry("reversalId", "refund_ref"),
              Map.entry("reversalRef", "refund_ref_id"), Map.entry("code", "error_code"),
              Map.entry("message", "error_message")),
          words("UNPAID", "VALID", "PENDING", "FAILED", "REFUNDED"),
          codes("E10", "1", "2", "3", "4", "5", "6")),
      new AggregatorProfile("ekpay", Protocol.REST, Auth.OAUTH2_CC, null, null,
          Map.ofEntries(Map.entry("billerCode", "billerId"), Map.entry("billNumber", "billNo"),
              Map.entry("period", "billMonth"), Map.entry("reference", "requestId"), Map.entry("paymentRef", "trxId"),
              Map.entry("reversalRef", "reversalTrxId"), Map.entry("code", "respCode"), Map.entry("message", "respMsg")),
          words("DUE", "SUCCESS", "PROCESSING", "FAILED", "REVERSED"),
          codes("40", "1", "2", "3", "4", "5", "6")),
      new AggregatorProfile("agg3", Protocol.SOAP, Auth.WS_SECURITY, null, null,
          Map.of("reference", "transactionId", "paymentRef", "receiptNo"),
          words("UNPAID", "PAID", "PENDING", "FAILED", "REVERSED"),
          codes("A3-0", "1", "2", "3", "4", "5", "6")),
      new AggregatorProfile("agg4", Protocol.REST, Auth.BASIC, "req", "res",
          Map.ofEntries(Map.entry("billerCode", "svc"), Map.entry("billNumber", "acct"), Map.entry("amount", "amt"),
              Map.entry("dueDate", "due"), Map.entry("period", "month"), Map.entry("status", "state"),
              Map.entry("reference", "ref"), Map.entry("paymentRef", "rcpt"), Map.entry("reversalId", "rev_ref"),
              Map.entry("reversalRef", "rev_rcpt"), Map.entry("code", "err"), Map.entry("message", "msg")),
          words("OPEN", "DONE", "WAIT", "FAIL", "VOID"),
          codes("X0", "1", "2", "3", "4", "5", "6")));
}
