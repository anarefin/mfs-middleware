package mfs.mw.orchestrator.mapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import mfs.mw.orchestrator.dto.Amount;
import mfs.mw.orchestrator.dto.Party;
import mfs.mw.orchestrator.dto.PartnerRef;
import mfs.mw.orchestrator.dto.TxnRequest;
import mfs.mw.orchestrator.entity.TransactionRecord;
import mfs.mw.orchestrator.service.saga.Status;
import org.springframework.stereotype.Component;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Request to transaction row, and the request hash behind the idempotency check (spec 7.3). */
@Component
public class RequestMapper {

  /**
   * What makes two requests "the same" for an idempotency key: the business content, not the
   * transport fields (txnId, correlationId, requestedAt) or the PIN ciphertext, which differs on
   * every encryption.
   */
  private record Canonical(
      String channel,
      String txnType,
      Party initiator,
      Party debitParty,
      Party creditParty,
      Amount amount,
      PartnerRef partner,
      String originalTxnId,
      Map<String, String> attributes) {}

  private final JsonMapper json;

  public RequestMapper(JsonMapper json) {
    this.json = json;
  }

  /** SHA-256 of the canonical business content (map keys sorted). */
  public byte[] requestHash(TxnRequest r) {
    Canonical c =
        new Canonical(
            r.channel(), r.txnType(), r.initiator(), r.debitParty(), r.creditParty(), r.amount(),
            r.partner(), r.originalTxnId(), r.attributes());
    String text =
        json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(c);
    try {
      return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A new transaction in RECEIVED; the PIN ciphertext is held apart from the stored request. */
  public TransactionRecord toRecord(TxnRequest r, String replyTo, Instant now) {
    return new TransactionRecord(
        UUID.fromString(r.txnId()), now, now, r.withoutPin(), Status.RECEIVED, null, replyTo, null,
        null, null, r.encryptedPin(), null, null, null);
  }
}
