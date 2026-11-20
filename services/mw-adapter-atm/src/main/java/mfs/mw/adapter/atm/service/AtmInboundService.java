package mfs.mw.adapter.atm.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.adapter.atm.config.AtmInboundProperties;
import mfs.mw.adapter.atm.exception.ErrorCodes;
import mfs.mw.adapter.atm.exception.MwException;
import mfs.mw.adapter.atm.repository.AtmInboundStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inbound ATM calls (ticket 120a, FRS UC-17, EXT-16, PLT-025): each call is recorded once per
 * (operation, atmTxnRef). A repeated call (the switch retries on a timeout) gets the first answer
 * again and writes nothing. A cash-out's txnId is minted by VERIFY; CONFIRM and REVERSAL name the
 * same atmTxnRef and carry that txnId, so an advice for an unknown cash-out is refused
 * ({@code MW-VAL-ATM_UNKNOWN_TXN}).
 *
 * <p>The answer is {@code ACCEPTED}. The call is not published on {@code mw.partner.inbound.v1}:
 * that contract (owner mw-orchestrator) only allows kinds CALLBACK, REMITTANCE and NPSB_CREDIT and
 * has no debit party, so verify, confirm, reversal and cash-in do not fit. Extending it and
 * publishing is ticket 120b's, with the token check and the hold. The token and the MSISDN are
 * never stored or logged (FL-11).
 */
@Service
public class AtmInboundService {

  public static final String FAMILY = "atm";
  public static final String VERIFY = "ATM_VERIFY_DEBIT";
  public static final String CONFIRM = "ATM_CONFIRM_DISPENSE";
  public static final String REVERSAL = "ATM_REVERSAL_ADVICE";
  public static final String CASH_IN = "ATM_CASH_IN";
  public static final String UNKNOWN_TXN = "MW-VAL-ATM_UNKNOWN_TXN";
  /** The UCB settlement wallet a cash-in is paid from (FRS UC-17). Publishing it is ticket 120b's. */
  public static final String SETTLEMENT = "ucb-atm-settlement";

  private static final Logger log = LoggerFactory.getLogger(AtmInboundService.class);
  private static final SecureRandom RANDOM = new SecureRandom();

  /** The answer to the switch. */
  public record Ack(String atmTxnRef, String txnId) {}

  private final AtmInboundStore repository;
  private final AtmInboundProperties properties;
  private final Clock clock;

  public AtmInboundService(AtmInboundStore repository, AtmInboundProperties properties, Clock clock) {
    this.repository = repository;
    this.properties = properties;
    this.clock = clock;
  }

  /** Cash-out at the ATM: the wallet of {@code msisdn} is to be debited {@code amountMinor}. */
  @Transactional
  public Ack verifyAndDebit(String atmTxnRef, String atmId, String msisdn, long amountMinor) {
    requireRef(atmTxnRef, atmId);
    requireWallet(msisdn, amountMinor);
    return record(VERIFY, atmTxnRef, newTxnId());
  }

  /** The cash left the ATM: the held debit commits. */
  @Transactional
  public Ack confirmDispense(String atmTxnRef, String atmId) {
    requireRef(atmTxnRef, atmId);
    return record(CONFIRM, atmTxnRef, cashOutTxnId(atmTxnRef));
  }

  /** The dispense failed: the held debit is released. Idempotent (PLT-025). */
  @Transactional
  public Ack reversalAdvice(String atmTxnRef, String atmId, String reasonCode) {
    requireRef(atmTxnRef, atmId);
    if (reasonCode != null && reasonCode.isBlank()) {
      throw invalid();
    }
    return record(REVERSAL, atmTxnRef, cashOutTxnId(atmTxnRef));
  }

  /** Cash-in at the ATM: the wallet of {@code msisdn} is credited from the UCB settlement wallet. */
  @Transactional
  public Ack cashIn(String atmTxnRef, String atmId, String msisdn, long amountMinor) {
    requireRef(atmTxnRef, atmId);
    requireWallet(msisdn, amountMinor);
    return record(CASH_IN, atmTxnRef, newTxnId());
  }

  private Ack record(String operation, String atmTxnRef, String txnId) {
    Optional<AtmInboundStore.Row> seen = repository.find(operation, atmTxnRef);
    if (seen.isPresent()) {
      return duplicate(operation, atmTxnRef, seen.get());
    }
    String inboundId = UUID.randomUUID().toString();
    if (!repository.insert(operation, atmTxnRef, txnId, inboundId, clock.instant())) {
      return duplicate(operation, atmTxnRef, repository.find(operation, atmTxnRef).orElseThrow());
    }
    log.atInfo()
        .addKeyValue("event", "atm.inbound.accepted")
        .addKeyValue("txnId", txnId)
        .addKeyValue("partnerId", properties.partnerId())
        .addKeyValue("operation", operation)
        .log("ATM call accepted");
    return new Ack(atmTxnRef, txnId);
  }

  private Ack duplicate(String operation, String atmTxnRef, AtmInboundStore.Row row) {
    log.atInfo()
        .addKeyValue("event", "atm.inbound.duplicate")
        .addKeyValue("txnId", row.txnId())
        .addKeyValue("operation", operation)
        .log("repeated ATM call answered with the first result");
    return new Ack(atmTxnRef, row.txnId());
  }

  private String cashOutTxnId(String atmTxnRef) {
    return repository.find(VERIFY, atmTxnRef)
        .map(AtmInboundStore.Row::txnId)
        .orElseThrow(() -> new MwException(UNKNOWN_TXN, "Unknown ATM transaction").withStatus(HttpStatus.NOT_FOUND));
  }

  private static void requireRef(String atmTxnRef, String atmId) {
    if (atmTxnRef == null || atmTxnRef.isBlank() || atmId == null || atmId.isBlank()) {
      throw invalid();
    }
  }

  private static void requireWallet(String msisdn, long amountMinor) {
    if (msisdn == null || msisdn.isBlank() || amountMinor <= 0) {
      throw invalid();
    }
  }

  private static MwException invalid() {
    return new MwException(ErrorCodes.VAL_INVALID, "Request validation failed").withStatus(HttpStatus.BAD_REQUEST);
  }

  /** A UUIDv7 (RFC 9562): 48-bit Unix milliseconds, version 7, variant 10, random rest. */
  String newTxnId() {
    long millis = clock.millis();
    long msb = (millis << 16) | 0x7000L | (RANDOM.nextInt() & 0x0fffL);
    long lsb = (RANDOM.nextLong() & 0x3fffffffffffffffL) | 0x8000000000000000L;
    return new UUID(msb, lsb).toString();
  }
}
