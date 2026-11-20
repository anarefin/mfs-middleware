package mfs.mw.checkout.mapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import mfs.mw.checkout.dto.Amount;
import mfs.mw.checkout.dto.Party;
import mfs.mw.checkout.dto.RefundIpnPayload;
import mfs.mw.checkout.dto.RefundView;
import mfs.mw.checkout.dto.TxnRequest;
import mfs.mw.checkout.entity.CheckoutSession;
import mfs.mw.checkout.entity.IpnDelivery;
import mfs.mw.checkout.entity.Refund;
import mfs.mw.checkout.util.UuidV7;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * A merchant refund (ticket 116) to what it sends and shows: the REVERSAL request the orchestrator's
 * refund flow takes (channel WEB_CHECKOUT, initiator the merchant, debit the merchant wallet, credit
 * the payer wallet: the payment's parties reversed; {@code originalTxnId} = the payment), the
 * merchant's view and the refund IPN.
 */
@Component
public class RefundMapper {

  public static final String REVERSAL = "REVERSAL";

  private final JsonMapper json;
  private final Clock clock;

  public RefundMapper(JsonMapper json, Clock clock) {
    this.json = json;
    this.clock = clock;
  }

  /** The orchestrator scopes idempotency by (channel, merchant, key); the merchant's own key goes through. */
  public static String idempotencyKey(Refund r) {
    return "refund:" + r.idemKey();
  }

  public TxnRequest toTxnRequest(Refund r, CheckoutSession s, String correlationId, Instant now) {
    return new TxnRequest(
        1,
        r.refundId().toString(),
        idempotencyKey(r),
        correlationId,
        CheckoutMapper.CHANNEL,
        REVERSAL,
        new Party("MERCHANT", s.merchantId(), null, null),
        Party.wallet(s.merchantWallet(), "MERCHANT"),
        Party.wallet(s.payerMsisdn(), "CUSTOMER"),
        new Amount(r.amountMinor(), r.currency()),
        r.originalTxnId().toString(),
        null,
        s.locale(),
        Map.of(
            "checkoutSessionId", s.sessionId().toString(),
            "merchantId", s.merchantId(),
            "orderId", s.orderId()),
        null,
        now);
  }

  public RefundView view(Refund r, CheckoutSession s) {
    return new RefundView(
        r.refundId().toString(),
        r.sessionId().toString(),
        s.orderId(),
        r.status().name(),
        new Amount(r.amountMinor(), r.currency()),
        r.originalTxnId().toString(),
        r.reason(),
        r.status() == Refund.Status.REJECTED ? r.errorCode() : null,
        r.createdAt(),
        r.updatedAt());
  }

  public RefundIpnPayload ipnPayload(Refund r, CheckoutSession s) {
    return new RefundIpnPayload(
        RefundIpnPayload.REFUND,
        r.refundId().toString(),
        s.sessionId().toString(),
        s.orderId(),
        r.status().name(),
        r.originalTxnId().toString(),
        new Amount(r.amountMinor(), r.currency()),
        r.status() == Refund.Status.REJECTED ? r.errorCode() : null,
        r.updatedAt().toString());
  }

  /** Attempt 1 of the refund IPN, due at {@code dueAt}; the body is the exact JSON text to sign and send. */
  public IpnDelivery firstIpnAttempt(Refund r, CheckoutSession s, Instant dueAt) {
    return new IpnDelivery(
        UuidV7.next(clock), s.sessionId(), s.merchantId(), 1, s.ipnUrl(), json.writeValueAsString(ipnPayload(r, s)),
        IpnDelivery.Status.PENDING, dueAt, null, null, null, null, clock.instant(), r.refundId());
  }
}
