package mfs.mw.adapter.atm.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.adapter.atm.config.AtmInboundProperties;
import mfs.mw.adapter.atm.dto.PartnerInbound;
import mfs.mw.adapter.atm.exception.MwException;
import mfs.mw.adapter.atm.filter.CorrelationIdFilter;
import mfs.mw.adapter.atm.messaging.EventMetadata;
import mfs.mw.adapter.atm.messaging.OutboxWriter;
import mfs.mw.adapter.atm.repository.AtmInboundRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inbound ATM calls (ticket 120a, FRS UC-17, EXT-16, PLT-025): each call is recorded once per
 * (operation, atmTxnRef) and handed to the orchestrator as {@code mw.partner.inbound.v1} (family
 * {@code atm}) through the outbox, in the same transaction. A repeated call (the switch retries on
 * a timeout) gets the first answer again and writes nothing. A cash-out's txnId is minted by
 * VERIFY; CONFIRM and REVERSAL name the same atmTxnRef and carry that txnId, so an advice for an
 * unknown cash-out is refused ({@code MW-VAL-ATM_UNKNOWN_TXN}).
 *
 * <p>The answer is {@code ACCEPTED}: the orchestrator's synchronous approve/decline of a cash-out
 * (token check, hold) is ticket 120b's.
 */
@Service
public class AtmInboundService {

  public static final String TOPIC = "mw.partner.inbound.v1";
  public static final String FAMILY = "atm";
  public static final String VERIFY = "ATM_VERIFY_DEBIT";
  public static final String CONFIRM = "ATM_CONFIRM_DISPENSE";
  public static final String REVERSAL = "ATM_REVERSAL_ADVICE";
  public static final String CASH_IN = "ATM_CASH_IN";
  public static final String UNKNOWN_TXN = "MW-VAL-ATM_UNKNOWN_TXN";
  /** The UCB settlement wallet a cash-in is paid from (FRS UC-17). */
  public static final String SETTLEMENT = "ucb-atm-settlement";

  private static final Logger log = LoggerFactory.getLogger(AtmInboundService.class);
  private static final SecureRandom RANDOM = new SecureRandom();

  /** The answer to the switch. */
  public record Ack(String atmTxnRef, String txnId) {}

  private final AtmInboundRepository repository;
  private final OutboxWriter outbox;
  private final AtmInboundProperties properties;
  private final Clock clock;

  public AtmInboundService(
      AtmInboundRepository repository, OutboxWriter outbox, AtmInboundProperties properties, Clock clock) {
    this.repository = repository;
    this.outbox = outbox;
    this.properties = properties;
    this.clock = clock;
  }

  /** Cash-out at the ATM: the wallet of {@code msisdn} is to be debited {@code amountMinor}. */
  @Transactional
  public Ack verifyAndDebit(String atmTxnRef, String atmId, String msisdn, long amountMinor) {
    return record(VERIFY, atmTxnRef, newTxnId(), new PartnerInbound.Payload(
        VERIFY, "ATM_CASH_OUT", new PartnerInbound.Amount(amountMinor, "BDT"),
        wallet(msisdn), new PartnerInbound.Party("ORG", SETTLEMENT, null), Map.of("atmId", atmId)));
  }

  /** The cash left the ATM: the held debit commits. */
  @Transactional
  public Ack confirmDispense(String atmTxnRef, String atmId) {
    return record(CONFIRM, atmTxnRef, cashOutTxnId(atmTxnRef), new PartnerInbound.Payload(
        CONFIRM, "ATM_CASH_OUT", null, null, null, Map.of("atmId", atmId)));
  }

  /** The dispense failed: the held debit is released. Idempotent (PLT-025). */
  @Transactional
  public Ack reversalAdvice(String atmTxnRef, String atmId, String reasonCode) {
    Map<String, String> attributes = reasonCode == null
        ? Map.of("atmId", atmId)
        : Map.of("atmId", atmId, "reasonCode", reasonCode);
    return record(REVERSAL, atmTxnRef, cashOutTxnId(atmTxnRef), new PartnerInbound.Payload(
        REVERSAL, "ATM_CASH_OUT", null, null, null, attributes));
  }

  /** Cash-in at the ATM: the wallet of {@code msisdn} is credited from the UCB settlement wallet. */
  @Transactional
  public Ack cashIn(String atmTxnRef, String atmId, String msisdn, long amountMinor) {
    return record(CASH_IN, atmTxnRef, newTxnId(), new PartnerInbound.Payload(
        CASH_IN, "ATM_CASH_IN", new PartnerInbound.Amount(amountMinor, "BDT"),
        new PartnerInbound.Party("ORG", SETTLEMENT, null), wallet(msisdn), Map.of("atmId", atmId)));
  }

  private Ack record(String operation, String atmTxnRef, String txnId, PartnerInbound.Payload payload) {
    Optional<AtmInboundRepository.Row> seen = repository.find(operation, atmTxnRef);
    if (seen.isPresent()) {
      return duplicate(operation, atmTxnRef, seen.get());
    }
    String inboundId = UUID.randomUUID().toString();
    Instant now = clock.instant();
    if (!repository.insert(operation, atmTxnRef, txnId, inboundId, now)) {
      return duplicate(operation, atmTxnRef, repository.find(operation, atmTxnRef).orElseThrow());
    }
    String correlationId = correlationId();
    PartnerInbound event = new PartnerInbound(
        1, FAMILY, inboundId, txnId, correlationId, properties.partnerId(), atmTxnRef, now, payload);
    outbox.append(TOPIC, txnId, new EventMetadata(correlationId, txnId, inboundId, 1), event);
    log.atInfo()
        .addKeyValue("event", "atm.inbound.accepted")
        .addKeyValue("txnId", txnId)
        .addKeyValue("partnerId", properties.partnerId())
        .addKeyValue("operation", operation)
        .log("ATM call accepted");
    return new Ack(atmTxnRef, txnId);
  }

  private Ack duplicate(String operation, String atmTxnRef, AtmInboundRepository.Row row) {
    log.atInfo()
        .addKeyValue("event", "atm.inbound.duplicate")
        .addKeyValue("txnId", row.txnId())
        .addKeyValue("operation", operation)
        .log("repeated ATM call answered with the first result");
    return new Ack(atmTxnRef, row.txnId());
  }

  private String cashOutTxnId(String atmTxnRef) {
    return repository.find(VERIFY, atmTxnRef)
        .map(AtmInboundRepository.Row::txnId)
        .orElseThrow(() -> new MwException(UNKNOWN_TXN, "Unknown ATM transaction").withStatus(HttpStatus.NOT_FOUND));
  }

  private static PartnerInbound.Party wallet(String msisdn) {
    return new PartnerInbound.Party("WALLET", msisdn, "CUSTOMER");
  }

  private static String correlationId() {
    String id = MDC.get(CorrelationIdFilter.MDC_KEY);
    return id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
  }

  /** A UUIDv7 (RFC 9562): 48-bit Unix milliseconds, version 7, variant 10, random rest. */
  String newTxnId() {
    long millis = clock.millis();
    long msb = (millis << 16) | 0x7000L | (RANDOM.nextInt() & 0x0fffL);
    long lsb = (RANDOM.nextLong() & 0x3fffffffffffffffL) | 0x8000000000000000L;
    return new UUID(msb, lsb).toString();
  }
}
