package mfs.mw.adapter.billpay.service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import mfs.mw.adapter.billpay.config.BillerProperties;
import mfs.mw.adapter.billpay.dto.BillView;
import mfs.mw.adapter.billpay.dto.BillerView;
import mfs.mw.adapter.billpay.dto.Money;
import mfs.mw.adapter.billpay.exception.ErrorCodes;
import mfs.mw.adapter.billpay.exception.MwException;
import mfs.mw.adapter.billpay.mapper.PartnerCallMapper;
import mfs.mw.adapter.billpay.partner.PartnerResponse;
import mfs.mw.adapter.billpay.partner.template.PartnerGateway;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Internal API behind {@code GET /billpay/v1/billers} and the bill fetch (ticket 21): the biller
 * catalogue and one INQUIRY through the adapter framework to the biller's aggregator. Runs on
 * the HTTP request's (virtual) thread, never on a Kafka thread; nothing is written (a fetch moves
 * no money, so no reference, inbox or outbox). Outcomes: SUCCESS -> the bill; a definite refusal
 * -> {@code MW-PTR-<AGGREGATOR>-<suffix>} (502); no answer / ambiguous -> {@code MW-TMO-PARTNER_*}
 * (504, retryable); not sent -> {@code MW-SYS-PARTNER_*} (503, retryable).
 */
@Service
public class BillFetchService {

  private final BillerProperties billers;
  private final PartnerCallMapper calls;
  private final PartnerGateway partners;

  public BillFetchService(BillerProperties billers, PartnerCallMapper calls, PartnerGateway partners) {
    this.billers = billers;
    this.calls = calls;
    this.partners = partners;
  }

  public List<BillerView> billers() {
    return billers.billers().entrySet().stream()
        .sorted(Comparator.comparing(java.util.Map.Entry::getKey))
        .map(e -> new BillerView(e.getKey(), e.getValue().name(), e.getValue().category(), e.getValue().partnerId()))
        .toList();
  }

  public BillView fetch(String billerCode, String billNumber) {
    BillerProperties.Biller biller = billers.billers().get(billerCode);
    if (biller == null) {
      throw new MwException(ErrorCodes.VAL_NOT_FOUND, "Unknown biller").withStatus(HttpStatus.NOT_FOUND);
    }
    String partnerId = biller.partnerId();
    PartnerResponse response =
        partners.call(calls.fetchCall(partnerId, billerCode, billNumber, MDC.get("correlationId")));
    return switch (response) {
      case PartnerResponse.Success s -> bill(billerCode, billNumber, s);
      case PartnerResponse.Rejected r ->
          throw new MwException("MW-PTR-" + segment(partnerId) + "-" + r.errorSuffix(),
                  r.message() == null ? "Bill fetch refused" : r.message())
              .withStatus(HttpStatus.BAD_GATEWAY)
              .withRetryable(r.retryable());
      case PartnerResponse.Ambiguous a -> throw timeout("AMBIGUOUS_CODE");
      case PartnerResponse.Unknown u -> throw timeout(u.reason().name());
      case PartnerResponse.NotSent n ->
          throw new MwException("MW-SYS-PARTNER_" + notSent(n.reason()), "Biller not reachable, try again")
              .withStatus(HttpStatus.SERVICE_UNAVAILABLE)
              .withRetryable(true);
      case PartnerResponse.NotFound n -> throw timeout("MALFORMED_REPLY");
      case PartnerResponse.Pending p -> throw timeout("MALFORMED_REPLY");
    };
  }

  private static BillView bill(String billerCode, String billNumber, PartnerResponse.Success s) {
    long minor;
    try {
      BigDecimal major = new BigDecimal(s.attributes().getOrDefault("amount", ""));
      minor = major.movePointRight(2).longValueExact();
    } catch (ArithmeticException | NumberFormatException e) {
      throw timeout("MALFORMED_REPLY");
    }
    if (minor < 0) {
      throw timeout("MALFORMED_REPLY");
    }
    return new BillView(
        billerCode,
        billNumber,
        new Money(minor, "BDT"),
        s.attributes().get("dueDate"),
        s.attributes().get("billPeriod"));
  }

  private static MwException timeout(String reason) {
    return new MwException("MW-TMO-PARTNER_" + reason, "No usable answer from the biller, try again")
        .withStatus(HttpStatus.GATEWAY_TIMEOUT)
        .withRetryable(true);
  }

  private static String notSent(PartnerResponse.NotSentReason reason) {
    return switch (reason) {
      case CIRCUIT_OPEN -> "CIRCUIT_OPEN";
      case BULKHEAD_FULL -> "BUSY";
      case CONNECT_FAILED -> "UNAVAILABLE";
      case DISABLED -> "DISABLED";
      case MISCONFIGURED -> "MISCONFIGURED";
    };
  }

  /** Upper case, anything outside A-Z, 0-9 and underscore turned into an underscore. */
  private static String segment(String partnerId) {
    return partnerId.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
  }
}
