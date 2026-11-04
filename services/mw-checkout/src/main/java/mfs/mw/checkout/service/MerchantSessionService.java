package mfs.mw.checkout.service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import mfs.mw.checkout.config.CheckoutProperties;
import mfs.mw.checkout.dto.Amount;
import mfs.mw.checkout.dto.CreateSessionRequest;
import mfs.mw.checkout.dto.MerchantSessionView;
import mfs.mw.checkout.dto.SessionCreated;
import mfs.mw.checkout.dto.SessionStatus;
import mfs.mw.checkout.entity.CheckoutSession;
import mfs.mw.checkout.exception.ErrorCodes;
import mfs.mw.checkout.exception.MwException;
import mfs.mw.checkout.repository.SessionRepository;
import mfs.mw.checkout.util.MaskingPolicy;
import mfs.mw.checkout.util.OtpCodes;
import mfs.mw.checkout.util.SensitiveType;
import mfs.mw.checkout.util.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The merchant API (spec 9.3 steps 1 and 4): sessions are created and read by the merchant APISIX
 * authenticated with {@code hmac-auth}; the consumer name arrives as the merchant id. A merchant
 * only ever sees its own sessions.
 */
@Service
public class MerchantSessionService {

  private static final Logger log = LoggerFactory.getLogger(MerchantSessionService.class);

  private final SessionRepository sessions;
  private final CheckoutProperties props;
  private final Clock clock;

  public MerchantSessionService(SessionRepository sessions, CheckoutProperties props, Clock clock) {
    this.sessions = sessions;
    this.props = props;
    this.clock = clock;
  }

  /** A created session (201), or the existing one of the same order and amount (200). */
  public record Created(SessionCreated session, boolean isNew) {}

  public Created create(String merchantId, CreateSessionRequest req) {
    CheckoutProperties.Merchant merchant = merchant(merchantId);
    Instant now = clock.instant();
    CheckoutSession s =
        new CheckoutSession(
            UuidV7.next(clock), OtpCodes.newPayToken(), merchantId, merchant.wallet(), req.orderId(),
            req.amount().valueMinor(), req.amount().currency(), req.description(),
            req.locale() == null ? "bn" : req.locale(), req.successUrl(), req.failUrl(), req.cancelUrl(),
            req.ipnUrl(), SessionStatus.OPEN, null, null, null, 0, 0, null, null, now,
            now.plus(props.sessionTtl()), now);
    if (sessions.insert(s)) {
      log.atInfo()
          .addKeyValue("event", "checkout.session.created")
          .addKeyValue("sessionId", s.sessionId().toString())
          .addKeyValue("merchantId", merchantId)
          .log("checkout session created");
      return new Created(created(s), true);
    }
    CheckoutSession existing = sessions.findByMerchantOrder(merchantId, req.orderId()).orElseThrow();
    if (existing.amountMinor() != s.amountMinor() || !existing.currency().equals(s.currency())) {
      throw new MwException(ErrorCodes.IDEM_ORDER_CONFLICT, "This order already has a session with another amount");
    }
    return new Created(created(existing), false);
  }

  public MerchantSessionView find(String merchantId, String sessionId) {
    merchant(merchantId);
    UUID id;
    try {
      id = UUID.fromString(sessionId);
    } catch (IllegalArgumentException e) {
      throw notFound();
    }
    CheckoutSession s =
        sessions.findById(id).filter(x -> x.merchantId().equals(merchantId)).orElseThrow(MerchantSessionService::notFound);
    return new MerchantSessionView(
        s.sessionId().toString(), s.orderId(), s.statusAt(clock.instant()), new Amount(s.amountMinor(), s.currency()),
        s.txnId() == null ? null : s.txnId().toString(),
        s.payerMsisdn() == null ? null : MaskingPolicy.mask(SensitiveType.MSISDN, s.payerMsisdn()),
        s.errorCode(), s.createdAt(), s.expiresAt(), s.updatedAt());
  }

  private SessionCreated created(CheckoutSession s) {
    return new SessionCreated(
        s.sessionId().toString(), props.pageBaseUrl() + s.payToken(), s.statusAt(clock.instant()), s.expiresAt());
  }

  /** The configured merchant behind the APISIX consumer: 401 without one, 403 for an unknown one. */
  private CheckoutProperties.Merchant merchant(String merchantId) {
    if (merchantId == null || merchantId.isBlank()) {
      throw new MwException(ErrorCodes.AUTH_NO_MERCHANT, "Merchant authentication required");
    }
    CheckoutProperties.Merchant m = props.merchant(merchantId);
    if (m == null) {
      throw new MwException(ErrorCodes.AUTH_UNKNOWN_MERCHANT, "Not a checkout merchant")
          .withStatus(HttpStatus.FORBIDDEN);
    }
    return m;
  }

  private static MwException notFound() {
    return new MwException(ErrorCodes.VAL_NOT_FOUND, "Not found").withStatus(HttpStatus.NOT_FOUND);
  }
}
