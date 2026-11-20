package mfs.mw.app.gateway.mapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import mfs.mw.app.gateway.dto.Amount;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.CardSessionRequest;
import mfs.mw.app.gateway.dto.EncryptedPin;
import mfs.mw.app.gateway.dto.TxnAuthorisationRequest;
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
 * {@code 01XNNNNNNNN} only. The PIN ciphertext never goes into the request: it goes to mw-auth
 * ({@link #toAuthorisation}), whose assertion the request carries (ticket 72).
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
  /** Transfer to bank (ticket 117c): from the caller's wallet to a linked bank account. */
  public static final String W2B = "W2B";

  /** Balance inquiry (ADR D26): moves no money, never in the app history. */
  public static final String BALANCE = "BALANCE";
  /** Partner-app disbursement: one organisation wallet pays one customer. */
  public static final String BULK_DISBURSE = "BULK_DISBURSE";
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

  /** The postpaid bill the quote stored (ticket 112b). A client cannot set these. */
  public static final String BILL_OUTSTANDING = "billOutstandingMinor";

  public static final String BILL_MINIMUM = "billMinimumMinor";

  /** Attribute keys set by the middleware (orchestrator reply matching); never from a client. */
  public static final Set<String> RESERVED_ATTRIBUTES =
      Set.of(StatusMessage.REQUEST_TXN_ID, StatusMessage.DUPLICATE, BILL_OUTSTANDING, BILL_MINIMUM);

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
    return toRequest(user, idempotencyKey, body, txnId, correlationId, now, partner, Map.of());
  }

  /**
   * As above, with the postpaid bill the preview stored. Those keys are reserved, so a copy the
   * client sent is dropped and only this map is published.
   */
  public TxnRequest toRequest(
      AppUser user,
      String idempotencyKey,
      TransactionRequest body,
      String txnId,
      String correlationId,
      Instant now,
      PartnerRef partner,
      Map<String, String> bill) {
    return toRequest(user, idempotencyKey, body, txnId, correlationId, now, partner, bill, null);
  }

  /**
   * As above, with the account number of the linked account the body names ({@code linkedAccountId},
   * ticket 117c), decrypted by the caller from the wallet's own stored row; never from the body. It is
   * W2B's payee and a linked add money's payer.
   */
  public TxnRequest toRequest(
      AppUser user,
      String idempotencyKey,
      TransactionRequest body,
      String txnId,
      String correlationId,
      Instant now,
      PartnerRef partner,
      Map<String, String> bill,
      String linkedAccount) {
    Party own = Party.wallet(user.msisdn(), user.walletType());
    boolean addMoney = ADD_MONEY_BANK.equals(body.txnType());
    boolean w2b = W2B.equals(body.txnType());
    Party bankAccount = linkedAccount != null
        ? new Party("BANK_ACCOUNT", linkedAccount, null, null)
        : addMoney ? new Party("BANK_ACCOUNT", body.debitParty().id(), null, null) : null;
    boolean bulk = BULK_DISBURSE.equals(body.txnType());
    // ADD_MONEY_BANK: the bank account pays, the caller's own wallet (its PIN) receives.
    // BULK_DISBURSE: the partner app keeps the role PARTNER; the core posts the existing
    // organisation wallet (party type ORG, stored as ORGANISATION) to a customer. The core
    // contract has no PARTNER wallet type.
    Party debit = addMoney
        ? bankAccount
        : bulk ? new Party("ORG", user.msisdn(), null, null) : own;
    Party credit = addMoney ? own : w2b ? bankAccount : creditParty(body, user.walletType());
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
        null,
        body.locale(),
        null,
        forwardable(body.attributes(), bill),
        now,
        Boolean.TRUE.equals(body.confirmDuplicate()) ? Boolean.TRUE : null);
  }

  /** Add money from a card (ticket 118b). */
  public static final String ADD_MONEY_CARD = "ADD_MONEY_CARD";
  /** The card acquirer (mw-adapter-card's partner record) and its product. */
  public static final PartnerRef CARD_ACQUIRER = new PartnerRef("cybersource", "CARD");

  /**
   * ADD_MONEY_CARD as the canonical request (ticket 118b): the card is a token at the acquirer (debit
   * party {@code CARD_TOKEN cybersource}; no card data ever), the caller's own wallet (its PIN) receives.
   */
  public TxnRequest toCardRequest(
      AppUser user, String idempotencyKey, CardSessionRequest body, String txnId, String correlationId, Instant now) {
    return new TxnRequest(
        1,
        txnId,
        idempotencyKey,
        correlationId,
        user.channel(),
        ADD_MONEY_CARD,
        new Party("MSISDN", user.msisdn(), null, null),
        new Party("CARD_TOKEN", CARD_ACQUIRER.partnerId(), null, null),
        Party.wallet(user.msisdn(), user.walletType()),
        body.amount(),
        CARD_ACQUIRER,
        null,
        body.locale(),
        null,
        null,
        now);
  }

  /**
   * A balance check (ADR D26) as the canonical request: {@code BALANCE}, amount 0 BDT, payer =
   * payee = the token's own wallet (its PIN authorises), no partner and no attributes.
   */
  public TxnRequest toBalanceRequest(
      AppUser user, String idempotencyKey, String locale, String txnId, String correlationId, Instant now) {
    Party own = Party.wallet(user.msisdn(), user.walletType());
    return new TxnRequest(
        1,
        txnId,
        idempotencyKey,
        correlationId,
        user.channel(),
        BALANCE,
        new Party("MSISDN", user.msisdn(), null, null),
        own,
        own,
        new Amount(0L, Amount.BDT),
        null,
        null,
        locale,
        null,
        null,
        now);
  }

  /**
   * The payee type {@code mw.txn.request.v1} publishes, and the one the preview quotes and hashes.
   * The txnType decides it; a client-supplied {@code creditParty.type} is ignored (BILL_PAY a
   * biller account, TOPUP an MSISDN, every wallet payment a WALLET — an agent's for CASH_OUT, a
   * merchant's for MERCHANT_PAY, an agent's for a distributor or DSO float, a distributor's when an
   * agent returns float, a customer's otherwise).
   */
  public static String creditPartyType(String txnType) {
    if (BILL_PAY.equals(txnType)) {
      return "BILLER_ACCOUNT";
    }
    if (W2B.equals(txnType)) {
      return "BANK_ACCOUNT";
    }
    return TOPUP.equals(txnType) ? "MSISDN" : "WALLET";
  }

  /**
   * A wallet (canonical MSISDN as wallet id; an agent's for CASH_OUT, a merchant's for
   * MERCHANT_PAY, a customer's otherwise; the
   * core checks the real wallet types per txnType), for TOPUP the bare MSISDN to recharge, for
   * BILL_PAY the bill number as a {@code BILLER_ACCOUNT}.
   */
  private static Party creditParty(TransactionRequest body, String payerType) {
    String type = creditPartyType(body.txnType());
    if ("BILLER_ACCOUNT".equals(type)) {
      return new Party(type, body.creditParty().id(), null, null);
    }
    String id = canonical(body.creditParty().id());
    if ("MSISDN".equals(type)) {
      return new Party(type, id, null, null);
    }
    return Party.wallet(id, payeeWalletType(body.txnType(), payerType));
  }

  private static String payeeWalletType(String txnType, String payerType) {
    if ("B2B_TRANSFER".equals(txnType)) {
      return "AGENT".equals(payerType) ? "DISTRIBUTOR" : "AGENT";
    }
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

  /** The app's attributes without the keys the middleware sets itself, then the stored bill. */
  private static Map<String, String> forwardable(Map<String, String> attributes, Map<String, String> bill) {
    Map<String, String> out = new LinkedHashMap<>();
    if (attributes != null) {
      out.putAll(attributes);
      out.keySet().removeAll(RESERVED_ATTRIBUTES);
    }
    if (bill != null) {
      bill.forEach((key, value) -> {
        if (value != null) {
          out.put(key, value);
        }
      });
    }
    return out.isEmpty() ? null : out;
  }

  /**
   * mw-auth's txn-authorisation for {@code req} (ticket 72, decision of ticket 71): exactly the
   * values published on {@code mw.txn.request.v1} (txnType, amount in minor units, currency, payer =
   * debitParty, payee = creditParty, idempotencyKey, channel), else the core's txnHash differs. The
   * wallet is the token's (mw-auth's walletId is the MSISDN), the device the app's bound one.
   */
  public TxnAuthorisationRequest toAuthorisation(
      AppUser user, String deviceId, TxnRequest req, EncryptedPin encryptedPin) {
    return new TxnAuthorisationRequest(
        user.msisdn(),
        req.channel(),
        deviceId,
        encryptedPin,
        new TxnAuthorisationRequest.Txn(
            req.txnType(),
            req.amount().valueMinor(),
            req.amount().currency(),
            new TxnAuthorisationRequest.TxnParty(req.debitParty().type(), req.debitParty().id()),
            new TxnAuthorisationRequest.TxnParty(req.creditParty().type(), req.creditParty().id()),
            req.idempotencyKey()));
  }

  /** What {@code GET} shows right after acceptance, before the orchestrator's first status. */
  public TransactionView accepted(TxnRequest req) {
    return new TransactionView(
        req.txnId(), ACCEPTED_STATUS, req.txnType(), req.debitParty(), req.creditParty(),
        req.amount(), null, null, null, req.requestedAt(), req.requestedAt());
  }
}
