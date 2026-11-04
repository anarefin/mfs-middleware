package mfs.mw.app.gateway.mapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import mfs.mw.app.gateway.dto.AppUser;
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
 * wallet named in the body (an agent for CASH_OUT). MSISDNs go out in the canonical form
 * {@code 01XNNNNNNNN} only; the PIN ciphertext is
 * copied as received, never inspected or changed (18.3).
 */
@Component
public class RequestMapper {

  /** The status the app sees for an accepted request until the first status record arrives. */
  public static final String ACCEPTED_STATUS = "RECEIVED";

  private static final String CASH_OUT = "CASH_OUT";

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
    // The token's wallet always pays the wallet named in the body; for CASH_OUT that payee is an
    // agent (the core checks the real wallet types per txnType).
    String payeeType = CASH_OUT.equals(body.txnType()) ? "AGENT" : "CUSTOMER";
    return new TxnRequest(
        1,
        txnId,
        idempotencyKey,
        correlationId,
        user.channel(),
        body.txnType(),
        new Party("MSISDN", user.msisdn(), null, null),
        Party.wallet(user.msisdn(), user.walletType()),
        Party.wallet(canonical(body.creditParty().id()), payeeType),
        body.amount(),
        body.encryptedPin(),
        body.locale(),
        null,
        forwardable(body.attributes()),
        now);
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
