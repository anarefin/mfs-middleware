package mfs.mw.orchestrator.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import mfs.mw.orchestrator.client.IdempotencyLock;
import mfs.mw.orchestrator.config.OrchestratorProperties;
import mfs.mw.orchestrator.dto.Party;
import mfs.mw.orchestrator.dto.TxnRequest;
import mfs.mw.orchestrator.service.saga.BalanceFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Business-duplicate check (FRS PLT-013, ticket 99): the same initiator sending the same txnType
 * with the same parties and amount under ANOTHER idempotency key within the window (default 60 s)
 * is a suspected duplicate. {@code SET NX EX} on {@code mw:orchestrator:dup:<sha256(tuple)>} with
 * the value {@code sha256(channel, initiator id, key)}: a repeat under the same key is
 * idempotency's business (7.3) and passes. App and USSD channels get a prompt
 * ({@link Decision#PROMPT}) the user answers by resending with {@code confirmDuplicate}; API
 * clients (checkout, portal, partner) are refused ({@link Decision#REJECT}) and cannot confirm.
 * Operations channels, BALANCE (moves no money) and REVERSAL (its own rules) are never checked. As
 * with the idempotency lock, Valkey/Redis trouble never fails a request: it passes and is counted
 * ({@code mw.txn.duplicate.guard.unavailable}). No MSISDN or key text is stored.
 */
@Service
public class DuplicateGuard {

  private static final Logger log = LoggerFactory.getLogger(DuplicateGuard.class);

  static final String KEY_PREFIX = "mw:orchestrator:dup:";
  private static final HexFormat HEX = HexFormat.of();

  /** What intake does with a request. */
  public enum Decision {
    /** Not a suspected duplicate (or confirmed, or not checked). */
    PASS,
    /** Suspected duplicate on an app/USSD channel: {@code MW-VAL-024}, confirmable. */
    PROMPT,
    /** Suspected duplicate from an API client: {@code MW-VAL-025}. */
    REJECT
  }

  private final IdempotencyLock store;
  private final OrchestratorProperties.DuplicateGuard settings;
  private final Counter unavailable;
  /** The window in force: the configured one, changed only by the test-only API (dev stack). */
  private volatile java.time.Duration window;

  @Autowired
  public DuplicateGuard(IdempotencyLock store, OrchestratorProperties properties, MeterRegistry meters) {
    this(store, properties.duplicateGuard(), meters);
  }

  DuplicateGuard(IdempotencyLock store, OrchestratorProperties.DuplicateGuard settings, MeterRegistry meters) {
    this.store = store;
    this.settings = settings;
    this.window = settings.window();
    this.unavailable =
        Counter.builder("mw.txn.duplicate.guard.unavailable")
            .description("Requests let through without the business-duplicate check because Valkey/Redis was unavailable")
            .register(meters);
  }

  public Decision check(TxnRequest req) {
    java.time.Duration current = window;
    if (!settings.enabled()
        || !current.isPositive()
        || settings.exemptChannels().contains(req.channel())
        || TransactionService.REVERSAL.equals(req.txnType())
        || BalanceFlow.TXN_TYPE.equals(req.txnType())) {
      return Decision.PASS;
    }
    boolean prompt = settings.promptChannels().contains(req.channel());
    if (prompt && req.duplicateConfirmed()) {
      return Decision.PASS;
    }
    String scope = sha256(req.channel(), req.initiator().id(), req.idempotencyKey());
    IdempotencyLock.Result result;
    try {
      result = store.tryAcquire(key(req), scope, current);
    } catch (RuntimeException e) {
      result = new IdempotencyLock.Result.Unavailable(e.getClass().getSimpleName());
    }
    return switch (result) {
      case IdempotencyLock.Result.Acquired a -> Decision.PASS;
      case IdempotencyLock.Result.Off o -> Decision.PASS;
      case IdempotencyLock.Result.Unavailable u -> fallBack(req, u.reason());
      case IdempotencyLock.Result.Held h ->
          h.value() == null || h.value().equals(scope) ? Decision.PASS : prompt ? Decision.PROMPT : Decision.REJECT;
    };
  }

  /** The window in force (0 = off). */
  public java.time.Duration window() {
    return window;
  }

  /**
   * Test-only (ticket 99, {@code POST /test/v1/duplicate-guard}): the dev stack runs with the
   * window off ({@code MW_DUPLICATE_WINDOW=0s}) so the API suite may repeat a payment, and the
   * duplicates folder switches it on for its own scenarios. 0 switches the check off.
   */
  public void changeWindow(java.time.Duration newWindow) {
    this.window = newWindow;
    log.atWarn()
        .addKeyValue("event", "txn.duplicate.window_changed")
        .addKeyValue("windowSeconds", newWindow.toSeconds())
        .log("business-duplicate window changed through the test-only API");
  }

  private Decision fallBack(TxnRequest req, String reason) {
    unavailable.increment();
    log.atWarn()
        .addKeyValue("event", "txn.duplicate.guard_unavailable")
        .addKeyValue("txnId", req.txnId())
        .addKeyValue("reason", reason)
        .log("business-duplicate check unavailable; request let through");
    return Decision.PASS;
  }

  private static String key(TxnRequest req) {
    return KEY_PREFIX
        + sha256(
            req.initiator().id(), req.txnType(), party(req.debitParty()), party(req.creditParty()),
            Long.toString(req.amount().valueMinor()), req.amount().currency());
  }

  private static String party(Party p) {
    return p == null ? "" : p.type() + ":" + p.id();
  }

  private static String sha256(String... parts) {
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      return HEX.formatHex(sha.digest(String.join("\u0000", parts).getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
