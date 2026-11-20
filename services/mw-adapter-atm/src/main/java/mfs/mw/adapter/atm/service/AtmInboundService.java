package mfs.mw.adapter.atm.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
 * Inbound ATM calls (tickets 120a, 120b1, FRS UC-17, EXT-16, PLT-025). Each call is recorded once
 * per (operation, atmTxnRef) with the atmId and a fingerprint (HMAC-SHA256 under the switch's
 * WS-Security secret of atmId, MSISDN and amount, so no clear MSISDN is stored). A repeat with the
 * same fingerprint gets the first answer; another fingerprint is {@code MW-IDEM-001} (409).
 *
 * <p>A cash-out is one state machine on its VERIFY row: VERIFIED -> CONFIRMED (ConfirmDispense) or
 * REVERSED (ReversalAdvice), moved once by a compare-and-set. A repeated advice of the reached state
 * gets the first answer; the other advice is {@code MW-IDEM-ATM_STATE} (409), and an advice from
 * another atmId is {@code MW-IDEM-001}. An advice for an unknown cash-out is
 * {@code MW-VAL-ATM_UNKNOWN_TXN}.
 *
 * <p>Fail closed (M9 review): VerifyTokenAndDebit and CashIn move money, and the token check, the
 * hold and the credit are tickets 120b2/120b3. Until then both answer {@code
 * MW-SYS-ATM_CASH_DISABLED} (503) and write nothing, unless {@code mw.atm.inbound.cash-enabled}
 * (tests only). Nothing is published on {@code mw.partner.inbound.v1} yet (contract gap, CLAUDE.md).
 * The token and the MSISDN are never stored in clear or logged (FL-11).
 */
@Service
public class AtmInboundService {

  public static final String FAMILY = "atm";
  public static final String VERIFY = "ATM_VERIFY_DEBIT";
  public static final String CONFIRM = "ATM_CONFIRM_DISPENSE";
  public static final String REVERSAL = "ATM_REVERSAL_ADVICE";
  public static final String CASH_IN = "ATM_CASH_IN";
  public static final String UNKNOWN_TXN = "MW-VAL-ATM_UNKNOWN_TXN";
  public static final String IDEM_CONFLICT = "MW-IDEM-001";
  public static final String STATE_CONFLICT = "MW-IDEM-ATM_STATE";
  public static final String CASH_DISABLED = "MW-SYS-ATM_CASH_DISABLED";
  public static final String VERIFIED = "VERIFIED";
  public static final String CONFIRMED = "CONFIRMED";
  public static final String REVERSED = "REVERSED";
  /** The UCB settlement wallet a cash-in is paid from (FRS UC-17). Publishing it is ticket 120b3's. */
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
    requireCashEnabled(VERIFY);
    return record(VERIFY, atmTxnRef, atmId, fingerprint(atmId, msisdn, amountMinor), VERIFIED);
  }

  /** The cash left the ATM: the held debit commits. */
  @Transactional
  public Ack confirmDispense(String atmTxnRef, String atmId) {
    requireRef(atmTxnRef, atmId);
    return advance(CONFIRM, atmTxnRef, atmId, CONFIRMED);
  }

  /** The dispense failed: the held debit is released. Idempotent (PLT-025). */
  @Transactional
  public Ack reversalAdvice(String atmTxnRef, String atmId, String reasonCode) {
    requireRef(atmTxnRef, atmId);
    if (reasonCode != null && reasonCode.isBlank()) {
      throw invalid();
    }
    return advance(REVERSAL, atmTxnRef, atmId, REVERSED);
  }

  /** Cash-in at the ATM: the wallet of {@code msisdn} is credited from the UCB settlement wallet. */
  @Transactional
  public Ack cashIn(String atmTxnRef, String atmId, String msisdn, long amountMinor) {
    requireRef(atmTxnRef, atmId);
    requireWallet(msisdn, amountMinor);
    requireCashEnabled(CASH_IN);
    return record(CASH_IN, atmTxnRef, atmId, fingerprint(atmId, msisdn, amountMinor), null);
  }

  private Ack record(String operation, String atmTxnRef, String atmId, String fingerprint, String state) {
    Optional<AtmInboundStore.Row> seen = repository.find(operation, atmTxnRef);
    if (seen.isPresent()) {
      return repeat(operation, atmTxnRef, seen.get(), fingerprint);
    }
    AtmInboundStore.Row row = new AtmInboundStore.Row(
        newTxnId(), UUID.randomUUID().toString(), atmId, fingerprint, state);
    if (!repository.insert(operation, atmTxnRef, row, clock.instant())) {
      return repeat(operation, atmTxnRef, repository.find(operation, atmTxnRef).orElseThrow(), fingerprint);
    }
    accepted(operation, row.txnId());
    return new Ack(atmTxnRef, row.txnId());
  }

  private Ack repeat(String operation, String atmTxnRef, AtmInboundStore.Row row, String fingerprint) {
    if (!fingerprint.equals(row.fingerprint())) {
      throw conflict(IDEM_CONFLICT, "atmTxnRef already used for another request", operation, row.txnId());
    }
    return duplicate(operation, atmTxnRef, row.txnId());
  }

  /** Moves the cash-out of {@code atmTxnRef} from VERIFIED to {@code target} once. */
  private Ack advance(String operation, String atmTxnRef, String atmId, String target) {
    AtmInboundStore.Row cashOut = repository.find(VERIFY, atmTxnRef)
        .orElseThrow(() -> new MwException(UNKNOWN_TXN, "Unknown ATM transaction").withStatus(HttpStatus.NOT_FOUND));
    if (!atmId.equals(cashOut.atmId())) {
      throw conflict(IDEM_CONFLICT, "atmTxnRef belongs to another ATM", operation, cashOut.txnId());
    }
    if (repository.transition(VERIFY, atmTxnRef, VERIFIED, target)) {
      repository.insert(operation, atmTxnRef,
          new AtmInboundStore.Row(cashOut.txnId(), UUID.randomUUID().toString(), atmId, null, null),
          clock.instant());
      accepted(operation, cashOut.txnId());
      return new Ack(atmTxnRef, cashOut.txnId());
    }
    String now = repository.find(VERIFY, atmTxnRef).map(AtmInboundStore.Row::state).orElse(null);
    if (target.equals(now)) {
      return duplicate(operation, atmTxnRef, cashOut.txnId());
    }
    throw conflict(STATE_CONFLICT, "ATM cash-out is already " + now, operation, cashOut.txnId());
  }

  private void accepted(String operation, String txnId) {
    log.atInfo()
        .addKeyValue("event", "atm.inbound.accepted")
        .addKeyValue("txnId", txnId)
        .addKeyValue("partnerId", properties.partnerId())
        .addKeyValue("operation", operation)
        .log("ATM call accepted");
  }

  private Ack duplicate(String operation, String atmTxnRef, String txnId) {
    log.atInfo()
        .addKeyValue("event", "atm.inbound.duplicate")
        .addKeyValue("txnId", txnId)
        .addKeyValue("operation", operation)
        .log("repeated ATM call answered with the first result");
    return new Ack(atmTxnRef, txnId);
  }

  private static MwException conflict(String code, String message, String operation, String txnId) {
    log.atWarn()
        .addKeyValue("event", "atm.inbound.conflict")
        .addKeyValue("txnId", txnId)
        .addKeyValue("operation", operation)
        .addKeyValue("errorCode", code)
        .log("ATM call conflicts with the recorded one");
    return new MwException(code, message).withStatus(HttpStatus.CONFLICT);
  }

  private void requireCashEnabled(String operation) {
    if (!Boolean.TRUE.equals(properties.cashEnabled())) {
      log.atWarn()
          .addKeyValue("event", "atm.inbound.cash_disabled")
          .addKeyValue("operation", operation)
          .addKeyValue("errorCode", CASH_DISABLED)
          .log("ATM cash operation refused: not enabled");
      throw new MwException(CASH_DISABLED, "ATM cash service is not enabled")
          .withStatus(HttpStatus.SERVICE_UNAVAILABLE);
    }
  }

  /**
   * HMAC-SHA256 (hex) of atmId, MSISDN and amount under the switch's WS-Security secret: no clear
   * MSISDN at rest. A rotated secret turns a retry that spans the rotation into a conflict.
   */
  private String fingerprint(String atmId, String msisdn, long amountMinor) {
    String secret = properties.wsPassword();
    byte[] key = (secret == null || secret.isEmpty() ? "mw-adapter-atm" : secret).getBytes(StandardCharsets.UTF_8);
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      byte[] input = String.join("|", atmId, msisdn, Long.toString(amountMinor)).getBytes(StandardCharsets.UTF_8);
      return HexFormat.of().formatHex(mac.doFinal(input));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 unavailable", e);
    }
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
