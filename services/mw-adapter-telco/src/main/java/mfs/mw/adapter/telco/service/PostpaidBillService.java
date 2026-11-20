package mfs.mw.adapter.telco.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import mfs.mw.adapter.telco.dto.Money;
import mfs.mw.adapter.telco.dto.PostpaidBillView;
import mfs.mw.adapter.telco.exception.MwException;
import mfs.mw.adapter.telco.partner.PartnerCall;
import mfs.mw.adapter.telco.partner.PartnerResponse;
import mfs.mw.adapter.telco.partner.route.PartnerGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * One postpaid INQUIRY through the operator profile (ticket 112b, FRS FL-03). Runs on the HTTP
 * request's thread; nothing is written. The bill comes back as poisha. A definite refusal is
 * {@code MW-PTR-<PROFILE>-<suffix>} (502); no usable answer is {@code MW-TMO-PARTNER_*} (504);
 * a call that never left is {@code MW-SYS-PARTNER_*} (503).
 */
@Service
public class PostpaidBillService {

  private static final Logger log = LoggerFactory.getLogger(PostpaidBillService.class);

  private final PartnerGateway partners;

  public PostpaidBillService(PartnerGateway partners) {
    this.partners = partners;
  }

  public PostpaidBillView fetch(String partnerId, String msisdn) {
    PartnerResponse response = partners.call(call(partnerId, msisdn));
    return switch (response) {
      case PartnerResponse.Success s -> bill(partnerId, s);
      case PartnerResponse.Rejected r ->
          throw new MwException("MW-PTR-" + segment(partnerId) + "-" + r.errorSuffix(),
                  r.message() == null ? "Bill fetch refused" : r.message())
              .withStatus(HttpStatus.BAD_GATEWAY)
              .withRetryable(r.retryable());
      case PartnerResponse.Ambiguous a -> throw timeout("AMBIGUOUS_CODE");
      case PartnerResponse.Unknown u -> throw timeout(u.reason().name());
      case PartnerResponse.NotSent n when n.reason() == PartnerResponse.NotSentReason.UNKNOWN_PARTNER ->
          throw new MwException("MW-PTR-UNKNOWN", "No partner serves this operator")
              .withStatus(HttpStatus.BAD_GATEWAY)
              .withRetryable(false);
      case PartnerResponse.NotSent n when n.reason() == PartnerResponse.NotSentReason.MAINTENANCE ->
          throw maintenance(n);
      case PartnerResponse.NotSent n ->
          throw new MwException("MW-SYS-PARTNER_" + notSent(n.reason()), "Operator not reachable, try again")
              .withStatus(HttpStatus.SERVICE_UNAVAILABLE)
              .withRetryable(true);
      case PartnerResponse.NotFound n -> throw timeout("MALFORMED_REPLY");
      case PartnerResponse.Pending p -> throw timeout("MALFORMED_REPLY");
    };
  }

  private static PartnerCall call(String partnerId, String msisdn) {
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("txnType", "TOPUP");
    input.put("creditParty", Map.of("type", "MSISDN", "id", msisdn));
    input.put("partner", Map.of("partnerId", partnerId, "productCode", "POSTPAID"));
    return new PartnerCall(partnerId, "INQUIRY", "TOPUP", null, MDC.get("correlationId"), null, input);
  }

  private static PostpaidBillView bill(String partnerId, PartnerResponse.Success success) {
    try {
      long outstanding = minor(success.attributes().get("outstanding"));
      long minimum = minor(success.attributes().get("minimum"));
      if (minimum < 0 || outstanding < minimum) {
        throw timeout("MALFORMED_REPLY");
      }
      log.atInfo()
          .addKeyValue("event", "partner.call.completed")
          .addKeyValue("partnerId", partnerId)
          .addKeyValue("operation", "INQUIRY")
          .log("postpaid bill fetched");
      return new PostpaidBillView(new Money(outstanding, "BDT"), new Money(minimum, "BDT"));
    } catch (ArithmeticException | NumberFormatException e) {
      throw timeout("MALFORMED_REPLY");
    }
  }

  /** A major-unit decimal such as {@code 450.00} or {@code 320.50}, as poisha. */
  static long minor(String major) {
    if (major == null || major.isBlank()) {
      throw new NumberFormatException("blank amount");
    }
    BigDecimal value = new BigDecimal(major.trim());
    if (value.signum() < 0 || value.scale() > 2) {
      throw new NumberFormatException("not poisha");
    }
    return value.movePointRight(2).longValueExact();
  }

  private static MwException timeout(String reason) {
    return new MwException("MW-TMO-PARTNER_" + reason, "No usable answer from the operator, try again")
        .withStatus(HttpStatus.GATEWAY_TIMEOUT)
        .withRetryable(true);
  }

  private static MwException maintenance(PartnerResponse.NotSent sent) {
    PartnerResponse.Notice notice =
        PartnerResponse.Notice.orDefault(
            sent.notice() == null ? null : sent.notice().en(), sent.notice() == null ? null : sent.notice().bn());
    return new MwException("MW-SYS-PARTNER_MAINTENANCE", notice.en())
        .withStatus(HttpStatus.SERVICE_UNAVAILABLE)
        .withRetryable(true)
        .withMessageBn(notice.bn());
  }

  private static String notSent(PartnerResponse.NotSentReason reason) {
    return switch (reason) {
      case CIRCUIT_OPEN -> "CIRCUIT_OPEN";
      case BULKHEAD_FULL -> "BUSY";
      case CONNECT_FAILED -> "UNAVAILABLE";
      case DISABLED -> "DISABLED";
      case MISCONFIGURED, UNKNOWN_PARTNER -> "MISCONFIGURED";
      case MAINTENANCE -> throw new IllegalStateException("maintenance is answered by its own arm");
    };
  }

  private static String segment(String partnerId) {
    return partnerId.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
  }
}
