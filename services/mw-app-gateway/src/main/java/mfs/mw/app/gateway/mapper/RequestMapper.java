package mfs.mw.app.gateway.mapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.PartnerRef;
import mfs.mw.app.gateway.dto.Party;
import mfs.mw.app.gateway.dto.StatusMessage;
import mfs.mw.app.gateway.dto.TransactionRequest;
import mfs.mw.app.gateway.dto.TransactionView;
import mfs.mw.app.gateway.dto.TxnRequest;
import mfs.mw.app.gateway.util.Msisdn;
import org.springframework.stereotype.Component;

/**
 * App request to the canonical {@code mw.txn.request.v1} (spec 5.1, 9). The initiator and payer is
 * the token's wallet (customer for P2P and CASH_OUT, agent float for CASH_IN), the payee the
 * wallet named in the body (an agent for CASH_OUT, a merchant for MERCHANT_PAY). MSISDNs go out in the canonical form
 * {@code 01XNNNNNNNN} only; the PIN ciphertext is
 * copied as received, never inspected or changed (18.3).
 */
@Component
public class RequestMapper {

  /** The status the app sees for an accepted request until the first status record arrives. */
  public static final String ACCEPTED_STATUS = "RECEIVED";

  private static final String CASH_OUT = "CASH_OUT";
  /** In-app merchant payment (ticket 30): the customer pays the merchant wallet named in the body. */
  public static final String MERCHANT_PAY = "MERCHANT_PAY";
  /** Airtime top-up: credit party is an MSISDN, {@code partner} names the operator. */
  public static final String TOPUP = "TOPUP";
  /** Bill payment: credit party is the bill number, {@code partner} the biller's aggregator. */
  public static final String BILL_PAY = "BILL_PAY";
  /** Add money from a linked bank account (C2W, ticket 23): debit party is the bank account. */
  public static final String ADD_MONEY_BANK = "ADD_MONEY_BANK";
  /** The canonical product of a C2W debit. */
  public static final String C2W_PRODUCT = "C2W";

  /**
   * The canonical partner of the body's {@code partner}: operator and product for TOPUP, the bank
   * with product {@code C2W} for ADD_MONEY_BANK. (BILL_PAY's comes from the biller catalogue.)
   */
  public static PartnerRef partnerOf(TransactionRequest body) {
    if (ADD_MONEY_BANK.equals(body.txnType())) {
      return new PartnerRef(body.partner().partnerId(), C2W_PRODUCT);
    }
    return new PartnerRef(body.partner().partnerId(), body.partner().productCode());
  }

  /** Attribute keys set by the middleware (orchestrator reply matching); never from a client. */
  public static final Set<String> RESERVED_ATTRIBUTES =
      Set.of(StatusMessage.REQUEST_TXN_ID, StatusMessage.DUPLICATE);

  public TxnRequest toRequest(
      AppUser user,
      String idempotencyKey,
      TransactionRequest body,
      String txnId,
      String correlationId,
      Instant now) {
    PartnerRef partner =
        body.partner() == null
            ? null
            : RequestMapper.partnerOf(body);
    return toRequest(user, idempotencyKey, body, txnId, correlationId, now, partner);
  }

  /**
   * As above with the canonical partner decided by the caller (BILL_PAY: the aggregator serving
   * the biller, from the catalogue).
   */
  public TxnRequest toRequest(
      AppUser user,
      String idempotencyKey,
      TransactionRequest body,
      String txnId,
      String correlationId,
      Instant now,
      PartnerRef partner) {
    Party own = Party.wallet(user.msisdn(), user.walletType());
    boolean addMoney = ADD_MONEY_BANK.equals(body.txnType());
    // ADD_MONEY_BANK: the bank account pays, the caller's own wallet (its PIN) receives
    Party debit = addMoney ? new Party("BANK_ACCOUNT", body.debitParty().id(), null, null) : own;
    Party credit = addMoney ? own : creditParty(body);
    return new TxnRequest(
        1,
        txnId,
        idempotencyKey,
        correlationId,
        user.channel(),
        body.txnType(),
        new Party("MSISDN", user.msisdn(), null, null),
        debit,
        credit,
        body.amount(),
        partner,
        body.encryptedPin(),
        body.locale(),
        null,
        forwardable(body.attributes()),
        now);
  }

  /**
   * A wallet (canonical MSISDN as wallet id; an agent's for CASH_OUT, a merchant's for
   * MERCHANT_PAY, a customer's otherwise; the
   * core checks the real wallet types per txnType), for TOPUP the bare MSISDN to recharge, for
   * BILL_PAY the bill number as a {@code BILLER_ACCOUNT}.
   */
  private static Party creditParty(TransactionRequest body) {
    if (BILL_PAY.equals(body.txnType())) {
      return new Party("BILLER_ACCOUNT", body.creditParty().id(), null, null);
    }
    String id = canonical(body.creditParty().id());
    if (TOPUP.equals(body.txnType())) {
      return new Party("MSISDN", id, null, null);
    }
    return Party.wallet(id, payeeWalletType(body.txnType()));
  }

  private static String payeeWalletType(String txnType) {
    if (CASH_OUT.equals(txnType)) {
      return "AGENT";
    }
    return MERCHANT_PAY.equals(txnType) ? "MERCHANT" : "CUSTOMER";
  }

  private static String canonical(String msisdn) {
    String c = Msisdn.canonical(msisdn);
    if (c == null) {
      throw new IllegalArgumentException("not an accepted MSISDN");
    }
    return c;
  }

  /** The app's attributes without the keys the middleware sets itself; null when none remain. */
  private static Map<String, String> forwardable(Map<String, String> attributes) {
    if (attributes == null) {
      return null;
    }
    Map<String, String> out = new LinkedHashMap<>(attributes);
    out.keySet().removeAll(RESERVED_ATTRIBUTES);
    return out.isEmpty() ? null : out;
  }

  /** What {@code GET} shows right after acceptance, before the orchestrator's first status. */
  public TransactionView accepted(TxnRequest req) {
    return new TransactionView(
        req.txnId(), ACCEPTED_STATUS, req.txnType(), req.debitParty(), req.creditParty(),
        req.amount(), null, null, null, req.requestedAt(), req.requestedAt());
  }
}
