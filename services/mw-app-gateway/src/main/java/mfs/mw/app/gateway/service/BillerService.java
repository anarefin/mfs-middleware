package mfs.mw.app.gateway.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import mfs.mw.app.gateway.client.BillPayClient;
import mfs.mw.app.gateway.config.BillPayProperties;
import mfs.mw.app.gateway.dto.Bill;
import mfs.mw.app.gateway.dto.Biller;
import mfs.mw.app.gateway.dto.ErrorDetail;
import mfs.mw.app.gateway.dto.PartnerRef;
import mfs.mw.app.gateway.exception.BillPayRefusedException;
import mfs.mw.app.gateway.exception.BillPayUnavailableException;
import mfs.mw.app.gateway.exception.ErrorCategory;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Billers and bill fetch for the app (spec 9.1, ticket 21), both answered by mw-adapter-billpay
 * over its internal HTTP API (read-only, no money moves; the payment itself goes through Kafka
 * like every other transaction). The catalogue is reused for {@code catalogue-ttl} and kept when
 * the adapter cannot be asked; it also tells a BILL_PAY which aggregator serves its biller.
 */
@Service
public class BillerService {

  /** Canonical product code of a bill payment. */
  public static final String PRODUCT = "BILL";

  private static final Logger log = LoggerFactory.getLogger(BillerService.class);

  private record Cached(List<Biller> billers, Instant at) {}

  private final BillPayClient client;
  private final BillPayProperties props;
  private final Clock clock;
  private volatile Cached cache;

  public BillerService(BillPayClient client, BillPayProperties props, Clock clock) {
    this.client = client;
    this.props = props;
    this.clock = clock;
  }

  /** The catalogue as the app sees it (no aggregator). */
  public List<Biller> billers() {
    return catalogue().stream().map(Biller::withoutPartner).toList();
  }

  /** The canonical partner of a BILL_PAY to {@code billerCode}; unknown biller MW-VAL-001. */
  public PartnerRef partnerFor(String billerCode) {
    return catalogue().stream()
        .filter(b -> b.code().equals(billerCode) && b.partnerId() != null)
        .findFirst()
        .map(b -> new PartnerRef(b.partnerId(), PRODUCT, b.code()))
        .orElseThrow(
            () ->
                new MwException(ErrorCodes.VAL_INVALID, "Request validation failed")
                    .withDetails(List.of(new ErrorDetail("partner.billerCode", "is not a known biller"))));
  }

  /** The amount due; the adapter's refusals keep their code and status (MW-PTR-*, MW-TMO-*). */
  public Bill fetch(String billerCode, String billNumber) {
    try {
      return client.fetch(billerCode, billNumber);
    } catch (BillPayRefusedException e) {
      throw refused(e);
    } catch (BillPayUnavailableException e) {
      throw unavailable(e);
    } catch (RuntimeException e) {
      // open circuit (CallNotPermittedException) and the like
      throw unavailable(e);
    }
  }

  private List<Biller> catalogue() {
    Cached c = cache;
    Instant now = clock.instant();
    if (c != null && c.at().plus(props.catalogueTtl()).isAfter(now)) {
      return c.billers();
    }
    try {
      List<Biller> fresh = List.copyOf(client.billers());
      cache = new Cached(fresh, now);
      return fresh;
    } catch (RuntimeException e) {
      if (c != null) {
        log.atWarn()
            .addKeyValue("event", "billpay.catalogue.stale")
            .addKeyValue("errorClass", e.getClass().getName())
            .log("biller catalogue could not be refreshed, using the last one");
        return c.billers();
      }
      throw e instanceof BillPayRefusedException r ? refused(r) : unavailable(e);
    }
  }

  private static MwException refused(BillPayRefusedException e) {
    String code = e.envelope().code();
    HttpStatus status = HttpStatus.resolve(e.status());
    try {
      ErrorCategory.of(code);
    } catch (IllegalArgumentException bad) {
      return unavailable(e);
    }
    if (status == null || !(status.is4xxClientError() || status.is5xxServerError())) {
      return unavailable(e);
    }
    MwException out =
        new MwException(code, e.envelope().message() == null ? "Bill fetch refused" : e.envelope().message())
            .withStatus(status)
            .withRetryable(e.envelope().retryable());
    return e.envelope().messageBn() == null ? out : out.withMessageBn(e.envelope().messageBn());
  }

  private static MwException unavailable(RuntimeException e) {
    return new MwException(ErrorCodes.SYS_BILLPAY_UNAVAILABLE, "Bill service temporarily unavailable, try again", e)
        .withStatus(HttpStatus.SERVICE_UNAVAILABLE)
        .withRetryable(true);
  }
}
