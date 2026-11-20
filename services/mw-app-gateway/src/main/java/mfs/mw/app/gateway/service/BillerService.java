package mfs.mw.app.gateway.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import mfs.mw.app.gateway.client.BillPayClient;
import mfs.mw.app.gateway.config.BillPayProperties;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.Bill;
import mfs.mw.app.gateway.dto.Biller;
import mfs.mw.app.gateway.dto.ErrorDetail;
import mfs.mw.app.gateway.dto.PartnerRef;
import mfs.mw.app.gateway.exception.BillPayRefusedException;
import mfs.mw.app.gateway.exception.BillPayUnavailableException;
import mfs.mw.app.gateway.exception.ErrorCategory;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.repository.BillFetchStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Billers and bill fetch for the app (spec 9.1, ticket 21), both answered by mw-adapter-billpay
 * over its internal HTTP API (read-only, no money moves; the payment itself goes through Kafka
 * like every other transaction). The catalogue is reused for {@code catalogue-ttl} and kept when
 * the adapter cannot be asked; it also tells a BILL_PAY which aggregator serves its biller.
 *
 * <p>Ticket 113b: once mw-admin-api's catalogue has published a BILLER record, the list and the
 * aggregator come from this gateway's own projection ({@link BillerCatalog}); the adapter's list is
 * the fallback while the projection is empty. A disabled biller is refused for payment with its
 * bn/en maintenance text (503 {@code MW-SYS-BILLER_DISABLED}, FRS PLT-043; the adapter answers a
 * bill fetch the same way). A fetched bill's amount is kept ({@link BillFetchStore}) for the BILL_PAY
 * preview of the same wallet, biller and bill number.
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
  private final BillerCatalog projection;
  private final BillFetchStore fetches;
  private volatile Cached cache;

  public BillerService(
      BillPayClient client, BillPayProperties props, Clock clock, BillerCatalog projection, BillFetchStore fetches) {
    this.client = client;
    this.props = props;
    this.clock = clock;
    this.projection = projection;
    this.fetches = fetches;
  }

  /** The catalogue as the app sees it (no aggregator). */
  public List<Biller> billers() {
    return catalogue().stream().map(Biller::withoutPartner).toList();
  }

  /**
   * The canonical partner of a BILL_PAY to {@code billerCode}; unknown biller MW-VAL-001, a disabled
   * one 503 {@code MW-SYS-BILLER_DISABLED} with its bn/en maintenance text.
   */
  public PartnerRef partnerFor(String billerCode) {
    Biller biller =
        catalogue().stream()
            .filter(b -> b.code().equals(billerCode) && b.partnerId() != null)
            .findFirst()
            .orElseThrow(
                () ->
                    new MwException(ErrorCodes.VAL_INVALID, "Request validation failed")
                        .withDetails(List.of(new ErrorDetail("partner.billerCode", "is not a known biller"))));
    if (!biller.isEnabled()) {
      throw disabled(biller);
    }
    return new PartnerRef(biller.partnerId(), PRODUCT, biller.code());
  }

  /** The amount due; the adapter's refusals keep their code and status (MW-PTR-*, MW-TMO-*). */
  public Bill fetch(String billerCode, String billNumber) {
    return fetch(null, billerCode, billNumber, null);
  }

  /**
   * The amount due for {@code user} (kept for its BILL_PAY preview, {@link BillFetchStore}); the
   * adapter's refusals keep their code and status (MW-PTR-*, MW-TMO-*, MW-SYS-BILLER_DISABLED).
   */
  public Bill fetch(AppUser user, String billerCode, String billNumber, String billMonth) {
    Bill bill = ask(billerCode, billNumber, billMonth);
    if (user != null && bill != null && bill.amount() != null && bill.amount().valueMinor() != null) {
      try {
        fetches.save(user.channel(), user.msisdn(), billerCode, billNumber, bill.amount().valueMinor());
      } catch (RuntimeException e) {
        log.atWarn()
            .addKeyValue("event", "billpay.fetch.store_unavailable")
            .addKeyValue("errorClass", e.getClass().getName())
            .log("fetched bill not kept; its payment goes without the fetched amount");
      }
    }
    return bill;
  }

  /** The amount this wallet last fetched for the bill, if still kept (never from a client). */
  public Optional<Long> fetchedAmount(AppUser user, String billerCode, String billNumber) {
    try {
      return fetches.find(user.channel(), user.msisdn(), billerCode, billNumber);
    } catch (RuntimeException e) {
      log.atWarn()
          .addKeyValue("event", "billpay.fetch.store_unavailable")
          .addKeyValue("errorClass", e.getClass().getName())
          .log("fetched bill could not be read");
      return Optional.empty();
    }
  }

  private Bill ask(String billerCode, String billNumber, String billMonth) {
    try {
      return client.fetch(billerCode, billNumber, billMonth);
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
    List<Biller> projected = projection.list();
    if (!projected.isEmpty()) {
      return projected;
    }
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

  private static MwException disabled(Biller b) {
    return new MwException(
            ErrorCodes.SYS_BILLER_DISABLED,
            b.maintenanceEn() == null ? "This biller is not available now" : b.maintenanceEn())
        .withStatus(HttpStatus.SERVICE_UNAVAILABLE)
        .withRetryable(false)
        .withMessageBn(b.maintenanceBn() == null ? "এই বিলার এখন পাওয়া যাচ্ছে না" : b.maintenanceBn());
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
