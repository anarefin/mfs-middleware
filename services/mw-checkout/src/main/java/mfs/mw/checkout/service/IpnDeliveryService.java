package mfs.mw.checkout.service;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mfs.mw.checkout.client.MerchantIpnClient;
import mfs.mw.checkout.config.CheckoutProperties;
import mfs.mw.checkout.config.IpnProperties;
import mfs.mw.checkout.entity.IpnDelivery;
import mfs.mw.checkout.repository.IpnDeliveryRepository;
import mfs.mw.checkout.security.IpnSigner;
import mfs.mw.checkout.security.IpnUrlPolicy;
import mfs.mw.checkout.util.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends the due IPN attempts (spec 9.3 step 3, ticket 32); run by {@code
 * scheduler/IpnDeliveryJob} on one instance at a time (ShedLock). For each PENDING attempt due
 * now: the URL is checked again with a DNS lookup ({@link IpnUrlPolicy}), the body is signed with
 * the merchant's current secret and the attempt's timestamp ({@link IpnSigner}) and posted once.
 * A 2xx makes it DELIVERED and ends the IPN; anything else is FAILED and, unless it was the last
 * one, writes the next attempt due after the retry delay ({@link IpnRetrySchedule}), in one
 * transaction with the result. No transaction is open during the call.
 */
@Service
public class IpnDeliveryService {

  private static final Logger log = LoggerFactory.getLogger(IpnDeliveryService.class);

  public static final String HEADER_SIGNATURE = "X-Upay-Signature";
  public static final String HEADER_TIMESTAMP = "X-Upay-Timestamp";
  public static final String HEADER_IPN_ID = "X-Upay-Ipn-Id";
  public static final String HEADER_ATTEMPT = "X-Upay-Attempt";

  private final IpnDeliveryRepository deliveries;
  private final MerchantIpnClient client;
  private final IpnUrlPolicy policy;
  private final IpnRetrySchedule schedule;
  private final CheckoutProperties checkout;
  private final IpnProperties props;
  private final TransactionTemplate tx;
  private final Clock clock;

  public IpnDeliveryService(
      IpnDeliveryRepository deliveries,
      MerchantIpnClient client,
      IpnUrlPolicy policy,
      IpnRetrySchedule schedule,
      CheckoutProperties checkout,
      IpnProperties props,
      TransactionTemplate tx,
      Clock clock) {
    this.deliveries = deliveries;
    this.client = client;
    this.policy = policy;
    this.schedule = schedule;
    this.checkout = checkout;
    this.props = props;
    this.tx = tx;
    this.clock = clock;
  }

  /** Tries every attempt due now (at most {@code batch-size}); returns how many were tried. */
  public int deliverDue() {
    List<IpnDelivery> due = deliveries.findDue(clock.instant(), props.batchSize());
    due.forEach(this::attempt);
    return due.size();
  }

  private void attempt(IpnDelivery d) {
    Instant at = clock.instant();
    MerchantIpnClient.Result result = send(d, at);
    boolean delivered = result.delivered();
    String error = delivered ? null : result.error() != null ? result.error() : "HTTP_" + result.httpStatus();
    Optional<Instant> next = delivered ? Optional.empty() : schedule.retryAfter(d.attempt());
    tx.executeWithoutResult(
        status -> {
          if (deliveries.recordAttempt(
              d.deliveryId(), delivered ? IpnDelivery.Status.DELIVERED : IpnDelivery.Status.FAILED, at,
              result.httpStatus(), error, result.durationMs())) {
            next.ifPresent(due -> deliveries.insert(d.next(UuidV7.next(clock), due, clock.instant())));
          }
        });
    log.atInfo()
        .addKeyValue("event", delivered ? "checkout.ipn.delivered" : next.isPresent() ? "checkout.ipn.failed" : "checkout.ipn.exhausted")
        .addKeyValue("sessionId", d.sessionId().toString())
        .addKeyValue("merchantId", d.merchantId())
        .addKeyValue("attempt", d.attempt())
        .addKeyValue("host", host(d.ipnUrl()))
        .addKeyValue("httpStatus", result.httpStatus())
        .addKeyValue("errorCode", error)
        .addKeyValue("durationMs", result.durationMs())
        .addKeyValue("nextAttemptAt", next.map(Instant::toString).orElse(null))
        .log(delivered ? "merchant IPN delivered" : "merchant IPN attempt failed");
  }

  private MerchantIpnClient.Result send(IpnDelivery d, Instant at) {
    Optional<String> refused = policy.refusalAfterLookup(d.ipnUrl());
    if (refused.isPresent()) {
      return new MerchantIpnClient.Result(null, "URL_REFUSED: " + refused.get(), 0);
    }
    CheckoutProperties.Merchant m = checkout.merchant(d.merchantId());
    if (m == null || m.secret() == null || m.secret().isBlank()) {
      return new MerchantIpnClient.Result(null, "NO_SECRET", 0);
    }
    long ts = at.getEpochSecond();
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put(HEADER_IPN_ID, d.sessionId().toString());
    headers.put(HEADER_ATTEMPT, Integer.toString(d.attempt()));
    headers.put(HEADER_TIMESTAMP, Long.toString(ts));
    headers.put(HEADER_SIGNATURE, IpnSigner.sign(m.secret(), ts, d.payload()));
    return client.post(URI.create(d.ipnUrl()), headers, d.payload());
  }

  /** Only the host goes to the logs: a merchant's URL may carry its own tokens in the query. */
  private static String host(String url) {
    try {
      return URI.create(url).getHost();
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
