package mfs.mw.adapter.billpay.service;

import java.math.BigDecimal;
import java.util.List;
import mfs.mw.adapter.billpay.dto.BillView;
import mfs.mw.adapter.billpay.dto.BillerView;
import mfs.mw.adapter.billpay.dto.Money;
import mfs.mw.adapter.billpay.exception.ErrorCodes;
import mfs.mw.adapter.billpay.exception.MwException;
import mfs.mw.adapter.billpay.mapper.PartnerCallMapper;
import mfs.mw.adapter.billpay.mapper.PartnerReplyMapper;
import mfs.mw.adapter.billpay.partner.PartnerResponse;
import mfs.mw.adapter.billpay.partner.route.PartnerGateway;
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

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BillFetchService.class);

  /** Not-sent reasons that move a bill fetch to the catalogue's fallback aggregator (ticket 114). */
  private static final java.util.Set<PartnerResponse.NotSentReason> FAILOVER = java.util.EnumSet.of(
      PartnerResponse.NotSentReason.CIRCUIT_OPEN, PartnerResponse.NotSentReason.CONNECT_FAILED,
      PartnerResponse.NotSentReason.DISABLED, PartnerResponse.NotSentReason.MAINTENANCE,
      PartnerResponse.NotSentReason.BULKHEAD_FULL);

  private final BillerCatalog billers;
  private final PartnerCallMapper calls;
  private final PartnerGateway partners;

  public BillFetchService(BillerCatalog billers, PartnerCallMapper calls, PartnerGateway partners) {
    this.billers = billers;
    this.calls = calls;
    this.partners = partners;
  }

  public List<BillerView> billers() {
    return billers.list().stream().map(BillFetchService::view).toList();
  }

  /** The catalogue entry as the internal API shows it (ticket 113 fields left out for a seed). */
  static BillerView view(BillerCatalog.Biller b) {
    if (b.version() == 0) {
      return new BillerView(b.code(), b.nameEn(), b.category(), b.partnerId());
    }
    return new BillerView(
        b.code(),
        b.nameEn(),
        b.category(),
        b.partnerId(),
        b.nameBn(),
        b.fallbackPartnerId(),
        b.fields().stream()
            .map(f -> new BillerView.FieldView(f.name(), f.labelEn(), f.labelBn(), f.regex().pattern(), f.required()))
            .toList(),
        b.amountRule(),
        b.minAmountMinor(),
        b.maxAmountMinor(),
        b.billMonthRequired(),
        b.enabled(),
        b.maintenanceEn(),
        b.maintenanceBn());
  }

  public BillView fetch(String billerCode, String billNumber) {
    return fetch(billerCode, billNumber, null);
  }

  /**
   * Ticket 113: the catalogue entry's rules first (disabled -> 503 {@code MW-SYS-BILLER_DISABLED}
   * with its bn/en text; field regex, {@code billMonth} -> 400 {@code MW-VAL-001}), then the call.
   */
  public BillView fetch(String billerCode, String billNumber, String billMonth) {
    BillerCatalog.Biller biller =
        billers.find(billerCode)
            .orElseThrow(() -> new MwException(ErrorCodes.VAL_NOT_FOUND, "Unknown biller").withStatus(HttpStatus.NOT_FOUND));
    biller.checkFetch(billNumber, billMonth);
    String partnerId = biller.partnerId();
    PartnerResponse response =
        partners.call(calls.fetchCall(partnerId, billerCode, billNumber, MDC.get("correlationId")));
    String fallback = biller.fallbackPartnerId();
    if (response instanceof PartnerResponse.NotSent n && FAILOVER.contains(n.reason())
        && fallback != null && !fallback.isBlank() && !fallback.equals(partnerId)) {
      // Ticket 114 (FRS PLT-032, T8): the primary aggregator's circuit is open, it is DOWN, disabled or
      // in maintenance, and nothing was sent: the bill fetch (a read) asks the catalogue's fallback
      // once. A payment is never moved here: it goes through the orchestrator to one aggregator.
      log.atInfo()
          .addKeyValue("event", "billpay.fetch.failover")
          .addKeyValue("partnerId", fallback)
          .log("bill fetch for {} not sent to {} ({}); asking the fallback", billerCode, partnerId, n.reason());
      partnerId = fallback;
      response = partners.call(calls.fetchCall(partnerId, billerCode, billNumber, MDC.get("correlationId")));
    }
    return answer(billerCode, billNumber, partnerId, response);
  }

  private static BillView answer(String billerCode, String billNumber, String partnerId, PartnerResponse response) {
    return switch (response) {
      case PartnerResponse.Success s -> bill(billerCode, billNumber, s);
      case PartnerResponse.Rejected r ->
          throw new MwException("MW-PTR-" + segment(partnerId) + "-" + r.errorSuffix(),
                  r.message() == null ? "Bill fetch refused" : r.message())
              .withStatus(HttpStatus.BAD_GATEWAY)
              .withRetryable(r.retryable());
      case PartnerResponse.Ambiguous a -> throw timeout("AMBIGUOUS_CODE");
      case PartnerResponse.Unknown u -> throw timeout(u.reason().name());
      case PartnerResponse.NotSent n when n.reason() == PartnerResponse.NotSentReason.UNKNOWN_PARTNER ->
          throw new MwException(PartnerReplyMapper.UNKNOWN_PARTNER_CODE, "No partner serves this biller")
              .withStatus(HttpStatus.BAD_GATEWAY)
              .withRetryable(false);
      case PartnerResponse.NotSent n when n.reason() == PartnerResponse.NotSentReason.MAINTENANCE ->
          throw maintenance(n);
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

  /** Upper case, anything outside A-Z, 0-9 and underscore turned into an underscore. */
  private static String segment(String partnerId) {
    return partnerId.toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
  }
}
