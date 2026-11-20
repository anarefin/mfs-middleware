package mfs.mw.checkout.mapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import mfs.mw.checkout.dto.Amount;
import mfs.mw.checkout.dto.EncryptedPin;
import mfs.mw.checkout.dto.Party;
import mfs.mw.checkout.dto.TxnAuthorisationRequest;
import mfs.mw.checkout.dto.TxnRequest;
import mfs.mw.checkout.entity.CheckoutSession;
import org.springframework.stereotype.Component;

/**
 * A checkout session to the canonical messages it sends (spec 5.1, 9.3): the MERCHANT_PAY request
 * (customer wallet to the merchant wallet; channel WEB_CHECKOUT) and, built from that very request,
 * the transaction mw-auth binds its assertion to (ticket 72, D22).
 */
@Component
public class CheckoutMapper {

  public static final String CHANNEL = "WEB_CHECKOUT";
  public static final String MERCHANT_PAY = "MERCHANT_PAY";

  /** One payment per session: the orchestrator answers a repeat with the stored transaction. */
  public static String idempotencyKey(CheckoutSession s) {
    return "checkout:" + s.sessionId();
  }

  /** The request without its assertion; {@link TxnRequest#withAssertion} adds mw-auth's. */
  public TxnRequest toTxnRequest(CheckoutSession s, String txnId, String correlationId, Instant now) {
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
        null,
        null,
        s.locale(),
        Map.of(
            "checkoutSessionId", s.sessionId().toString(),
            "merchantId", s.merchantId(),
            "orderId", s.orderId()),
        null,
        now);
  }

  /**
   * What mw-auth hashes into the assertion's txnHash, taken from the request to be published (decision
   * of ticket 71): payer = debitParty, payee = creditParty (type and id), amount = valueMinor, the
   * request's currency, idempotencyKey and channel.
   */
  public TxnAuthorisationRequest toAuthorisation(TxnRequest txn, EncryptedPin pin) {
    return new TxnAuthorisationRequest(
        txn.initiator().id(),
        txn.channel(),
        pin,
        new TxnAuthorisationRequest.Txn(
            txn.txnType(),
            txn.amount().valueMinor(),
            txn.amount().currency(),
            new TxnAuthorisationRequest.TxnParty(txn.debitParty().type(), txn.debitParty().id()),
            new TxnAuthorisationRequest.TxnParty(txn.creditParty().type(), txn.creditParty().id()),
            txn.idempotencyKey()));
  }

  /** Poisha to taka with two decimals ({@code 2550 -> "25.50"}): the OTP SMS amount. */
  public static String taka(long minor) {
    return BigDecimal.valueOf(minor).movePointLeft(2).setScale(2).toPlainString();
  }
}
