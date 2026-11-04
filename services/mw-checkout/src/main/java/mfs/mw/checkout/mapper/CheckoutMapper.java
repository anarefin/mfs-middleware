package mfs.mw.checkout.mapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import mfs.mw.checkout.dto.Amount;
import mfs.mw.checkout.dto.EncryptedPin;
import mfs.mw.checkout.dto.NotificationRequest;
import mfs.mw.checkout.dto.Party;
import mfs.mw.checkout.dto.TxnRequest;
import mfs.mw.checkout.entity.CheckoutSession;
import org.springframework.stereotype.Component;

/**
 * A checkout session to the canonical messages it sends (spec 5.1, 9.3, 10.1): the MERCHANT_PAY
 * request (customer wallet, its own PIN, to the merchant wallet; channel WEB_CHECKOUT) and the
 * OTP SMS.
 */
@Component
public class CheckoutMapper {

  public static final String CHANNEL = "WEB_CHECKOUT";
  public static final String MERCHANT_PAY = "MERCHANT_PAY";
  public static final String OTP_TEMPLATE = "CHECKOUT_OTP";

  /** One payment per session: the orchestrator answers a repeat with the stored transaction. */
  public static String idempotencyKey(CheckoutSession s) {
    return "checkout:" + s.sessionId();
  }

  public TxnRequest toTxnRequest(
      CheckoutSession s, String txnId, String correlationId, EncryptedPin pin, Instant now) {
    return new TxnRequest(
        1,
        txnId,
        idempotencyKey(s),
        correlationId,
        CHANNEL,
        MERCHANT_PAY,
        new Party("MSISDN", s.payerMsisdn(), null, null),
        Party.wallet(s.payerMsisdn(), "CUSTOMER"),
        Party.wallet(s.merchantWallet(), "MERCHANT"),
        new Amount(s.amountMinor(), s.currency()),
        pin,
        s.locale(),
        Map.of(
            "checkoutSessionId", s.sessionId().toString(),
            "merchantId", s.merchantId(),
            "orderId", s.orderId()),
        null,
        now);
  }

  /**
   * The OTP SMS. {@code sendNo} counts the session's OTPs, so a resend is a new message and a
   * redelivery of the same one is dropped by mw-notify (messageId is its idempotency key).
   */
  public NotificationRequest toOtpNotification(
      CheckoutSession s, String merchantName, String otp, int sendNo, Instant expiresAt, String correlationId) {
    return new NotificationRequest(
        1,
        "checkout-otp:" + s.sessionId() + ":" + sendNo,
        correlationId,
        new NotificationRequest.Recipient(s.payerMsisdn()),
        OTP_TEMPLATE,
        s.locale(),
        Map.of("otp", otp, "amount", taka(s.amountMinor()), "merchant", merchantName),
        List.of("SMS"),
        "OTP",
        expiresAt);
  }

  /** Poisha to taka with two decimals ({@code 2550 -> "25.50"}). */
  public static String taka(long minor) {
    return BigDecimal.valueOf(minor).movePointLeft(2).setScale(2).toPlainString();
  }
}
