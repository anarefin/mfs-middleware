package mfs.mw.ussd.mapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import mfs.mw.ussd.dto.Amount;
import mfs.mw.ussd.dto.EncryptedPin;
import mfs.mw.ussd.dto.TxnAuthorisationRequest;
import mfs.mw.ussd.dto.FlowRequest;
import mfs.mw.ussd.dto.PartnerRef;
import mfs.mw.ussd.dto.Party;
import mfs.mw.ussd.dto.TxnRequest;
import mfs.mw.ussd.dto.UssdSession;
import org.springframework.stereotype.Component;

/**
 * An ACTION's flow request to the canonical {@code mw.txn.request.v1} (spec 5.1, 9.2). The
 * initiator and payer is the dialling subscriber's customer wallet (USSD serves customers; agents
 * cash in from the Agent app). The payee per flow: P2P the customer wallet entered, CASH_OUT the
 * agent's wallet, TOPUP the bare MSISDN to recharge with the operator as partner, BILL_PAY the bill
 * number as a {@code BILLER_ACCOUNT} with the biller's aggregator as partner, BALANCE (ADR D26) the
 * customer's own wallet with amount 0: a balance inquiry moves no money. The request goes out
 * with mw-auth's assertion only (ticket 72): {@link #authorisation} builds the authorisation body from
 * exactly the request's values, so the assertion's txnHash matches what the core sees.
 */
@Component
public class TxnRequestMapper {

  public static final String CHANNEL = "USSD";
  public static final String P2P = "P2P";
  public static final String CASH_OUT = "CASH_OUT";
  public static final String TOPUP = "TOPUP";
  public static final String BILL_PAY = "BILL_PAY";
  /** Balance inquiry (ADR D26): amount 0, payer = payee = the own wallet, nothing mapped. */
  public static final String BALANCE = "BALANCE";
  /** Canonical product code of a bill payment (as mw-app-gateway sends it). */
  public static final String BILL_PRODUCT = "BILL";

  private static final Set<String> BASE = Set.of(FlowRequest.CREDIT_PARTY, FlowRequest.AMOUNT);

  /** Flows an ACTION node may name, and the fields its mapping must provide. */
  public static final Map<String, Set<String>> REQUIRED_FIELDS =
      Map.of(
          P2P, BASE,
          CASH_OUT, BASE,
          TOPUP, Set.of(FlowRequest.CREDIT_PARTY, FlowRequest.AMOUNT, FlowRequest.PARTNER_ID, FlowRequest.PRODUCT_CODE),
          BILL_PAY, Set.of(FlowRequest.CREDIT_PARTY, FlowRequest.AMOUNT, FlowRequest.PARTNER_ID, FlowRequest.BILLER_CODE),
          BALANCE, Set.of());

  /** Flows an ACTION node may name. */
  public static final Set<String> FLOWS = REQUIRED_FIELDS.keySet();

  public TxnRequest toRequest(
      FlowRequest req, String txnId, String idempotencyKey, String correlationId, Instant now) {
    if (!FLOWS.contains(req.flow())) {
      throw new IllegalArgumentException("unsupported flow " + req.flow());
    }
    for (String field : REQUIRED_FIELDS.get(req.flow())) {
      if (req.fields().get(field) == null || req.fields().get(field).isBlank()) {
        throw new IllegalArgumentException(req.flow() + " needs " + field);
      }
    }
    UssdSession s = req.session();
    Map<String, String> f = req.fields();
    boolean balance = BALANCE.equals(req.flow());
    Party payer = Party.wallet(s.msisdn(), "CUSTOMER");
    return new TxnRequest(
        1,
        txnId,
        idempotencyKey,
        correlationId,
        CHANNEL,
        req.flow(),
        new Party("MSISDN", s.msisdn(), null, null),
        payer,
        balance ? payer : creditParty(req.flow(), f.get(FlowRequest.CREDIT_PARTY)),
        new Amount(balance ? 0L : minor(f.get(FlowRequest.AMOUNT)), Amount.BDT),
        partner(req.flow(), f),
        null,
        s.language(),
        Map.of("ussdSessionId", s.sessionId(), "mno", s.mno()),
        null,
        now,
        "true".equals(s.inputs().get(FlowRequest.CONFIRM_DUPLICATE)) ? Boolean.TRUE : null);
  }

  /**
   * mw-auth's authorisation body for {@code req} (decision of ticket 71): the dialling subscriber's
   * MSISDN, channel USSD, the PIN block and the transaction with the request's own values (payer =
   * debitParty, payee = creditParty, amount = valueMinor, the same idempotency key).
   */
  public TxnAuthorisationRequest authorisation(TxnRequest req, EncryptedPin pin) {
    return new TxnAuthorisationRequest(
        req.initiator().id(),
        req.channel(),
        pin,
        new TxnAuthorisationRequest.Txn(
            req.txnType(),
            req.amount().valueMinor(),
            req.amount().currency(),
            new TxnAuthorisationRequest.TxnParty(req.debitParty().type(), req.debitParty().id()),
            new TxnAuthorisationRequest.TxnParty(req.creditParty().type(), req.creditParty().id()),
            req.idempotencyKey()));
  }

  private static Party creditParty(String flow, String id) {
    return switch (flow) {
      case CASH_OUT -> Party.wallet(id, "AGENT");
      case TOPUP -> new Party("MSISDN", id, null, null);
      case BILL_PAY -> new Party("BILLER_ACCOUNT", id, null, null);
      default -> Party.wallet(id, "CUSTOMER");
    };
  }

  private static PartnerRef partner(String flow, Map<String, String> f) {
    return switch (flow) {
      case TOPUP -> new PartnerRef(f.get(FlowRequest.PARTNER_ID), f.get(FlowRequest.PRODUCT_CODE));
      case BILL_PAY ->
          new PartnerRef(f.get(FlowRequest.PARTNER_ID), BILL_PRODUCT, f.get(FlowRequest.BILLER_CODE));
      default -> null;
    };
  }

  /** Taka with up to two decimals ({@code "500.00"}) to poisha; none (a BALANCE) is 0. */
  public static long minor(String taka) {
    if (taka == null || taka.isBlank()) {
      return 0L;
    }
    return new BigDecimal(taka).movePointRight(2).longValueExact();
  }
}
