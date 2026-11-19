package mfs.mw.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import mfs.mw.orchestrator.TestData;
import mfs.mw.orchestrator.client.IdempotencyLock;
import mfs.mw.orchestrator.config.OrchestratorProperties;
import mfs.mw.orchestrator.dto.Amount;
import mfs.mw.orchestrator.dto.TxnRequest;
import mfs.mw.orchestrator.service.DuplicateGuard.Decision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The business-duplicate window (FRS PLT-013, ticket 99). */
class DuplicateGuardTest {

  /** SET NX EX in memory with a hand-moved clock; can be switched off like an unreachable server. */
  static final class FakeLock implements IdempotencyLock {
    final Map<String, String> values = new HashMap<>();
    final Map<String, Instant> expiries = new HashMap<>();
    Instant now = TestData.NOW;
    boolean down;

    @Override
    public Result tryAcquire(String key, String value, Duration ttl) {
      if (down) {
        return new Result.Unavailable("RedisConnectionFailureException");
      }
      Instant expiry = expiries.get(key);
      if (expiry != null && !now.isBefore(expiry)) {
        values.remove(key);
        expiries.remove(key);
      }
      String held = values.putIfAbsent(key, value);
      if (held != null) {
        return new Result.Held(held);
      }
      expiries.put(key, now.plus(ttl));
      return new Result.Acquired();
    }
  }

  private FakeLock lock;
  private SimpleMeterRegistry meters;
  private DuplicateGuard guard;

  @BeforeEach
  void setUp() {
    lock = new FakeLock();
    meters = new SimpleMeterRegistry();
    guard = new DuplicateGuard(lock, settings(true), meters);
  }

  private static OrchestratorProperties.DuplicateGuard settings(boolean enabled) {
    return new OrchestratorProperties.DuplicateGuard(
        enabled, Duration.ofSeconds(60), Set.of("CUSTOMER_APP", "AGENT_APP", "USSD"), Set.of("SYSTEM"));
  }

  private static TxnRequest p2p(String key, long amountMinor) {
    return TestData.p2p(TestData.txnId(), key, amountMinor);
  }

  private static TxnRequest onChannel(TxnRequest r, String channel) {
    return new TxnRequest(
        r.schemaVersion(), r.txnId(), r.idempotencyKey(), r.correlationId(), channel, r.txnType(),
        r.initiator(), r.debitParty(), r.creditParty(), r.amount(), r.partner(), r.originalTxnId(),
        r.assertion(), r.locale(), r.metadata(), r.attributes(), r.requestedAt(), r.confirmDuplicate());
  }

  private static TxnRequest confirmed(TxnRequest r) {
    return new TxnRequest(
        r.schemaVersion(), r.txnId(), r.idempotencyKey(), r.correlationId(), r.channel(), r.txnType(),
        r.initiator(), r.debitParty(), r.creditParty(), r.amount(), r.partner(), r.originalTxnId(),
        r.assertion(), r.locale(), r.metadata(), r.attributes(), r.requestedAt(), Boolean.TRUE);
  }

  @Test
  void theSameTupleUnderAnotherKeyWithinTheWindowIsSuspectedOnAnAppChannel() {
    assertThat(guard.check(p2p("key-a", 5_000))).isEqualTo(Decision.PASS);
    assertThat(guard.check(p2p("key-b", 5_000))).isEqualTo(Decision.PROMPT);
  }

  @Test
  void anApiClientDuplicateIsRejectedNotPrompted() {
    assertThat(guard.check(onChannel(p2p("key-a", 5_000), "WEB_CHECKOUT"))).isEqualTo(Decision.PASS);
    assertThat(guard.check(onChannel(p2p("key-b", 5_000), "WEB_CHECKOUT"))).isEqualTo(Decision.REJECT);
  }

  @Test
  void anApiClientCannotConfirmADuplicate() {
    guard.check(onChannel(p2p("key-a", 5_000), "ORG_PORTAL"));
    assertThat(guard.check(confirmed(onChannel(p2p("key-b", 5_000), "ORG_PORTAL")))).isEqualTo(Decision.REJECT);
  }

  @Test
  void theSameTupleAfterTheWindowPasses() {
    guard.check(p2p("key-a", 5_000));
    lock.now = lock.now.plusSeconds(60);
    assertThat(guard.check(p2p("key-b", 5_000))).isEqualTo(Decision.PASS);
  }

  @Test
  void anotherAmountOrCounterpartyPasses() {
    guard.check(p2p("key-a", 5_000));
    assertThat(guard.check(p2p("key-b", 5_001))).isEqualTo(Decision.PASS);
    TxnRequest other = p2p("key-c", 5_000);
    TxnRequest toSomeoneElse =
        new TxnRequest(
            other.schemaVersion(), other.txnId(), other.idempotencyKey(), other.correlationId(), other.channel(),
            other.txnType(), other.initiator(), other.debitParty(),
            new mfs.mw.orchestrator.dto.Party("WALLET", "01711000003", null, "CUSTOMER"), Amount.bdt(5_000),
            null, null, other.assertion(), other.locale(), other.metadata(), null, other.requestedAt(), null);
    assertThat(guard.check(toSomeoneElse)).isEqualTo(Decision.PASS);
  }

  @Test
  void aConfirmedDuplicateFromAnAppChannelPasses() {
    guard.check(p2p("key-a", 5_000));
    assertThat(guard.check(confirmed(p2p("key-b", 5_000)))).isEqualTo(Decision.PASS);
  }

  /** A same-key repeat is idempotency's job (7.3): it must reach the claim, not the prompt. */
  @Test
  void theSameIdempotencyKeyIsNotABusinessDuplicate() {
    guard.check(p2p("key-a", 5_000));
    assertThat(guard.check(p2p("key-a", 5_000))).isEqualTo(Decision.PASS);
  }

  @Test
  void theGuardKeyHoldsNoPersonalData() {
    guard.check(p2p("key-a", 5_000));
    assertThat(lock.values.keySet())
        .singleElement()
        .satisfies(k -> assertThat(k).startsWith("mw:orchestrator:dup:").doesNotContain("01711000001", "01711000002"));
    assertThat(lock.values.values()).singleElement().satisfies(v -> assertThat(v).doesNotContain("key-a"));
    assertThat(lock.expiries.values()).containsExactly(TestData.NOW.plusSeconds(60));
  }

  @Test
  void exemptChannelsBalanceAndReversalAreNeverChecked() {
    guard.check(onChannel(p2p("key-a", 5_000), "SYSTEM"));
    assertThat(guard.check(onChannel(p2p("key-b", 5_000), "SYSTEM"))).isEqualTo(Decision.PASS);
    assertThat(lock.values).isEmpty();
  }

  @Test
  void whenTheStoreIsUnreachableTheRequestPassesAndIsCounted() {
    lock.down = true;
    guard.check(p2p("key-a", 5_000));
    assertThat(guard.check(p2p("key-b", 5_000))).isEqualTo(Decision.PASS);
    assertThat(meters.get("mw.txn.duplicate.guard.unavailable").counter().count()).isEqualTo(2.0);
  }

  /** The dev stack runs with the window at 0 and the duplicates folder sets it (test-only API). */
  @Test
  void aZeroWindowSwitchesTheCheckOffAndAChangedWindowAppliesAtOnce() {
    guard.changeWindow(Duration.ZERO);
    guard.check(p2p("key-a", 5_000));
    assertThat(guard.check(p2p("key-b", 5_000))).isEqualTo(Decision.PASS);
    assertThat(lock.values).isEmpty();

    guard.changeWindow(Duration.ofSeconds(30));
    guard.check(p2p("key-c", 5_000));
    assertThat(guard.check(p2p("key-d", 5_000))).isEqualTo(Decision.PROMPT);
    assertThat(lock.expiries.values()).containsExactly(TestData.NOW.plusSeconds(30));
  }

  @Test
  void switchedOffEverythingPasses() {
    DuplicateGuard off = new DuplicateGuard(lock, settings(false), meters);
    off.check(p2p("key-a", 5_000));
    assertThat(off.check(p2p("key-b", 5_000))).isEqualTo(Decision.PASS);
  }
}
