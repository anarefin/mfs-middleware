package mfs.mw.orchestrator.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import mfs.mw.orchestrator.dto.Party;
import mfs.mw.orchestrator.dto.TestReversalAccepted;
import mfs.mw.orchestrator.dto.TxnRequest;
import mfs.mw.orchestrator.entity.TransactionRecord;
import mfs.mw.orchestrator.exception.ErrorCodes;
import mfs.mw.orchestrator.exception.MwException;
import mfs.mw.orchestrator.messaging.EventMetadata;
import mfs.mw.orchestrator.messaging.EventPublisher;
import mfs.mw.orchestrator.repository.IdempotencyRepository;
import mfs.mw.orchestrator.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Test-only (ticket 58): plays operations' REVERSAL producer for the host API suite. A REVERSAL of
 * a stored transaction is built as operations sends it (channel SYSTEM, initiator ORG, the
 * original's parties swapped, its full amount) and published on {@code mw.txn.request.v1} keyed by
 * its txnId, so it takes the same path as every other request. It changes no local state, so it is
 * published directly ({@link EventPublisher}, root CLAUDE.md exception), not through the outbox.
 * Only with {@code mw.orchestrator.test-api.enabled=true}.
 */
@Service
@ConditionalOnProperty(name = "mw.orchestrator.test-api.enabled", havingValue = "true")
public class TestApiService {

  static final String TOPIC = "mw.txn.request.v1";
  static final String INITIATOR = "bits-ops-test";
  static final String DEFAULT_REASON = "test reversal";

  private static final Logger log = LoggerFactory.getLogger(TestApiService.class);
  private static final SecureRandom RANDOM = new SecureRandom();

  private final TransactionRepository transactions;
  private final IdempotencyRepository idempotency;
  private final EventPublisher publisher;
  private final Clock clock;

  public TestApiService(
      TransactionRepository transactions, IdempotencyRepository idempotency, EventPublisher publisher, Clock clock) {
    this.transactions = transactions;
    this.idempotency = idempotency;
    this.publisher = publisher;
    this.clock = clock;
  }

  /** Publishes a REVERSAL of {@code originalTxnId}; 404 {@code MW-VAL-003} when no such transaction. */
  public TestReversalAccepted reverse(UUID originalTxnId, String reason) {
    TransactionRecord original =
        transactions
            .find(originalTxnId)
            .orElseThrow(
                () -> new MwException(ErrorCodes.VAL_NOT_FOUND, "Transaction not found").withStatus(HttpStatus.NOT_FOUND));
    Instant now = clock.instant();
    String txnId = uuidV7(now).toString();
    String key = "test-rev-" + uuidV7(now);
    String correlationId = "test-" + UUID.randomUUID();
    TxnRequest o = original.request();
    TxnRequest reversal =
        new TxnRequest(
            1, txnId, key, correlationId, "SYSTEM", "REVERSAL", new Party("ORG", INITIATOR, null, null),
            o.creditParty(), o.debitParty(), o.amount(), null, originalTxnId.toString(), null, null, null,
            Map.of("reason", reason == null || reason.isBlank() ? DEFAULT_REASON : reason), now);
    publisher.publish(TOPIC, txnId, new EventMetadata(correlationId, txnId, key, 1), reversal);
    log.atInfo()
        .addKeyValue("event", "test.reversal.published")
        .addKeyValue("txnId", txnId)
        .addKeyValue("correlationId", correlationId)
        .log("test-only REVERSAL published");
    return new TestReversalAccepted(txnId, key);
  }

  /** Whether the orchestrator recorded {@code idempotencyKey} (any channel and initiator). */
  public boolean handled(String idempotencyKey) {
    return idempotency.exists(idempotencyKey);
  }

  /** A time-ordered UUID (RFC 9562 version 7), the format the contract's TxnId requires. */
  static UUID uuidV7(Instant now) {
    byte[] b = new byte[16];
    RANDOM.nextBytes(b);
    long ms = now.toEpochMilli();
    for (int i = 0; i < 6; i++) {
      b[i] = (byte) (ms >>> (40 - 8 * i));
    }
    b[6] = (byte) ((b[6] & 0x0f) | 0x70);
    b[8] = (byte) ((b[8] & 0x3f) | 0x80);
    String h = HexFormat.of().formatHex(b);
    return UUID.fromString(
        h.substring(0, 8) + "-" + h.substring(8, 12) + "-" + h.substring(12, 16) + "-" + h.substring(16, 20) + "-"
            + h.substring(20));
  }
}
