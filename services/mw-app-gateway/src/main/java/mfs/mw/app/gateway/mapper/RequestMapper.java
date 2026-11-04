package mfs.mw.app.gateway.mapper;

import java.time.Instant;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.Party;
import mfs.mw.app.gateway.dto.TransactionRequest;
import mfs.mw.app.gateway.dto.TransactionView;
import mfs.mw.app.gateway.dto.TxnRequest;
import org.springframework.stereotype.Component;

/**
 * App request to the canonical {@code mw.txn.request.v1} (spec 5.1, 9). The payer is the token's
 * wallet; the PIN ciphertext is copied as received, never inspected or changed (18.3).
 */
@Component
public class RequestMapper {

  /** The status the app sees for an accepted request until the first status record arrives. */
  public static final String ACCEPTED_STATUS = "RECEIVED";

  public TxnRequest toRequest(
      AppUser user,
      String idempotencyKey,
      TransactionRequest body,
      String txnId,
      String correlationId,
      Instant now) {
    Party payer = Party.wallet(user.msisdn(), user.walletType());
    return new TxnRequest(
        1,
        txnId,
        idempotencyKey,
        correlationId,
        user.channel(),
        body.txnType(),
        new Party("MSISDN", user.msisdn(), null, null),
        payer,
        Party.wallet(body.creditParty().id(), "CUSTOMER"),
        body.amount(),
        body.encryptedPin(),
        body.locale(),
        null,
        body.attributes() == null || body.attributes().isEmpty() ? null : body.attributes(),
        now);
  }

  /** What {@code GET} shows right after acceptance, before the orchestrator's first status. */
  public TransactionView accepted(TxnRequest req) {
    return new TransactionView(
        req.txnId(), ACCEPTED_STATUS, req.txnType(), req.debitParty(), req.creditParty(),
        req.amount(), null, null, null, req.requestedAt(), req.requestedAt());
  }
}
