package mfs.mw.orchestrator.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import mfs.mw.orchestrator.client.DlqClient;
import mfs.mw.orchestrator.config.MwServiceProperties;
import mfs.mw.orchestrator.dto.DlqRecord;
import mfs.mw.orchestrator.dto.Party;
import mfs.mw.orchestrator.dto.PoisonAccepted;
import mfs.mw.orchestrator.dto.TestReversalAccepted;
import mfs.mw.orchestrator.dto.TxnRequest;
import mfs.mw.orchestrator.dto.TxnRequestHandled;
import mfs.mw.orchestrator.entity.TransactionRecord;
import mfs.mw.orchestrator.exception.ErrorCodes;
import mfs.mw.orchestrator.exception.MwException;
import mfs.mw.orchestrator.messaging.EventMetadata;
import mfs.mw.orchestrator.messaging.EventPublisher;
import mfs.mw.orchestrator.messaging.MwTopics;
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

  static final String INITIATOR = "bits-ops-test";
  static final String DEFAULT_REASON = "test reversal";

  private static final Logger log = LoggerFactory.getLogger(TestApiService.class);
  private static final SecureRandom RANDOM = new SecureRandom();

  private final TransactionRepository transactions;
  private final IdempotencyRepository idempotency;
  private final EventPublisher publisher;
  private final DlqClient dlq;
  private final String dlqTopic;
  private final Clock clock;

  public TestApiService(
      TransactionRepository transactions,
      IdempotencyRepository idempotency,
      EventPublisher publisher,
      DlqClient dlq,
      MwServiceProperties service,
      Clock clock) {
    this.transactions = transactions;
    this.idempotency = idempotency;
    this.publisher = publisher;
    this.dlq = dlq;
    // the consumer group is the service name (application.yml camel.component.kafka.group-id)
    this.dlqTopic = MwTopics.dlqTopic(MwTopics.TXN_REQUEST, service.name());
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
    String correlationId = UUID.randomUUID().toString();
    TxnRequest o = original.request();
    TxnRequest reversal =
        new TxnRequest(
            1, txnId, key, correlationId, "SYSTEM", "REVERSAL", new Party("ORG", INITIATOR, null, null),
            o.creditParty(), o.debitParty(), o.amount(), null, originalTxnId.toString(), null, null, null,
            Map.of("reason", reason == null || reason.isBlank() ? DEFAULT_REASON : reason), now);
    publisher.publish(MwTopics.TXN_REQUEST, txnId, new EventMetadata(correlationId, txnId, key, 1), reversal);
    log.atInfo()
        .addKeyValue("event", "test.reversal.published")
        .addKeyValue("txnId", txnId)
        .addKeyValue("correlationId", correlationId)
        .log("test-only REVERSAL published");
    return new TestReversalAccepted(txnId, key);
  }

  /**
   * That the orchestrator recorded {@code idempotencyKey} (any channel and initiator); 404
   * {@code MW-VAL-003} while it has not (yet).
   */
  public TxnRequestHandled handled(String idempotencyKey) {
    if (!idempotency.exists(idempotencyKey)) {
      throw new MwException(ErrorCodes.VAL_NOT_FOUND, "Request not handled (yet)").withStatus(HttpStatus.NOT_FOUND);
    }
    return new TxnRequestHandled(idempotencyKey, true);
  }

  /**
   * Publishes a request the listener can read but always refuses (no initiator, amount or
   * requestedAt), keyed by a fresh UUIDv7 txnId: it travels every retry topic and ends on the
   * DLQ (spec 19.2). Nothing is stored: the listener throws before the intake.
   */
  public PoisonAccepted poison() {
    String txnId = uuidV7(clock.instant()).toString();
    String correlationId = UUID.randomUUID().toString();
    publisher.publish(
        MwTopics.TXN_REQUEST, txnId, new EventMetadata(correlationId, txnId, "test-poison-" + txnId, 1),
        Map.of("schemaVersion", 1, "txnId", txnId, "txnType", "P2P"));
    log.atInfo()
        .addKeyValue("event", "test.poison.published")
        .addKeyValue("txnId", txnId)
        .addKeyValue("correlationId", correlationId)
        .log("test-only poison request published");
    return new PoisonAccepted(txnId, dlqTopic);
  }

  /** That the request keyed {@code txnId} is parked on the DLQ; 404 {@code MW-VAL-003} while it is not. */
  public DlqRecord dlqRecord(String txnId) {
    if (!dlq.holds(dlqTopic, txnId)) {
      throw new MwException(ErrorCodes.VAL_NOT_FOUND, "Not on the DLQ (yet)").withStatus(HttpStatus.NOT_FOUND);
    }
    return new DlqRecord(txnId, dlqTopic, true);
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
