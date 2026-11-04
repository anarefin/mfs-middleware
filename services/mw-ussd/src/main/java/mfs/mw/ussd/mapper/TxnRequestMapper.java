package mfs.mw.ussd.mapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import mfs.mw.ussd.dto.Amount;
import mfs.mw.ussd.dto.FlowRequest;
import mfs.mw.ussd.dto.Party;
import mfs.mw.ussd.dto.TxnRequest;
import mfs.mw.ussd.dto.UssdSession;
import org.springframework.stereotype.Component;

/**
 * An ACTION's flow request to the canonical {@code mw.txn.request.v1} (spec 5.1, 9.2). The
 * initiator and payer is the dialling subscriber's wallet; P2P pays the customer wallet entered
 * on the menu. The PIN ciphertext is the one encrypted on receipt; it is passed on once.
 */
@Component
public class TxnRequestMapper {

  public static final String CHANNEL = "USSD";
  public static final String P2P = "P2P";

  /** Flows an ACTION node may name. */
  public static final Set<String> FLOWS = Set.of(P2P);

  public TxnRequest toRequest(
      FlowRequest req, String txnId, String idempotencyKey, String correlationId, Instant now) {
    if (!FLOWS.contains(req.flow())) {
      throw new IllegalArgumentException("unsupported flow " + req.flow());
    }
    UssdSession s = req.session();
    return new TxnRequest(
        1,
        txnId,
        idempotencyKey,
        correlationId,
        CHANNEL,
        req.flow(),
        new Party("MSISDN", s.msisdn(), null, null),
        Party.wallet(s.msisdn(), "CUSTOMER"),
        Party.wallet(req.fields().get(FlowRequest.CREDIT_PARTY), "CUSTOMER"),
        new Amount(minor(req.fields().get(FlowRequest.AMOUNT)), Amount.BDT),
        null,
        req.pin(),
        s.language(),
        Map.of("ussdSessionId", s.sessionId(), "mno", s.mno()),
        null,
        now);
  }

  /** Taka with up to two decimals ({@code "500.00"}) to poisha. */
  public static long minor(String taka) {
    return new BigDecimal(taka).movePointRight(2).longValueExact();
  }
}
