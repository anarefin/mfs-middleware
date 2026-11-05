package mfs.mw.checkout.mapper;

import java.time.Clock;
import mfs.mw.checkout.dto.Amount;
import mfs.mw.checkout.dto.IpnPayload;
import mfs.mw.checkout.dto.SessionStatus;
import mfs.mw.checkout.entity.CheckoutSession;
import mfs.mw.checkout.entity.IpnDelivery;
import mfs.mw.checkout.util.UuidV7;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** A finished session -> the IPN body and its first attempt (ticket 32). */
@Component
public class IpnMapper {

  private final JsonMapper json;
  private final Clock clock;

  public IpnMapper(JsonMapper json, Clock clock) {
    this.json = json;
    this.clock = clock;
  }

  public IpnPayload payload(CheckoutSession s) {
    return new IpnPayload(
        s.sessionId().toString(),
        s.orderId(),
        s.status(),
        s.txnId() == null ? null : s.txnId().toString(),
        new Amount(s.amountMinor(), s.currency()),
        s.status() == SessionStatus.FAILED ? s.errorCode() : null,
        s.updatedAt().toString());
  }

  /** Attempt 1, PENDING, due at {@code dueAt}; the body is the exact JSON text to sign and send. */
  public IpnDelivery firstAttempt(CheckoutSession s, java.time.Instant dueAt) {
    return new IpnDelivery(
        UuidV7.next(clock), s.sessionId(), s.merchantId(), 1, s.ipnUrl(), json.writeValueAsString(payload(s)),
        IpnDelivery.Status.PENDING, dueAt, null, null, null, null, clock.instant());
  }
}
