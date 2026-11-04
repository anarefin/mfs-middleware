package mfs.mw.app.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import mfs.mw.app.gateway.dto.Party;
import mfs.mw.app.gateway.dto.StatusMessage;
import org.junit.jupiter.api.Test;

/**
 * Which status records complete a waiting request (spec 7.3, 7.5), and that a record can only
 * complete the request it belongs to: same idempotency key and same initiator.
 */
class ReplyWaitersTest {

  static final String KEY = "key-1";
  static final String PAYER = "01711000001";

  final ReplyWaiters waiters = new ReplyWaiters();

  static StatusMessage status(
      String txnId, String status, String key, String initiator, Map<String, String> attributes) {
    return new StatusMessage(
        1, txnId, key, "c", "CUSTOMER_APP", "P2P", status, null,
        initiator == null ? null : new Party("MSISDN", initiator, null, null), null, null, null,
        null, null, null, null, attributes, null, null);
  }

  static StatusMessage status(String txnId, String status, Map<String, String> attributes) {
    return status(txnId, status, KEY, PAYER, attributes);
  }

  @Test
  void intermediateStatusesDoNotCompleteTerminalOnesDo() {
    CompletableFuture<StatusMessage> f = waiters.register("T1", KEY, PAYER);
    assertThat(waiters.offer(status("T1", "VALIDATING", null))).isFalse();
    assertThat(f).isNotDone();
    assertThat(waiters.offer(status("T1", "COMPLETED", null))).isTrue();
    assertThat(f).isCompleted();
    assertThat(waiters.size()).isZero();
  }

  @Test
  void aDuplicateAnswerCompletesTheRequestItNamesWhateverItsStatus() {
    CompletableFuture<StatusMessage> f = waiters.register("T2", KEY, PAYER);
    StatusMessage dup =
        status("T-STORED", "POSTING", Map.of("requestTxnId", "T2", "duplicate", "true"));
    assertThat(waiters.offer(dup)).isTrue();
    assertThat(f.join().txnId()).isEqualTo("T-STORED");
  }

  @Test
  void noWaiterNoEffect() {
    assertThat(waiters.offer(status("T3", "FAILED", null))).isFalse();
    waiters.register("T4", KEY, PAYER);
    waiters.remove("T4");
    assertThat(waiters.offer(status("T4", "COMPLETED", null))).isFalse();
  }

  /**
   * Another customer's transaction whose attributes claim to be a duplicate answer for the
   * victim's txnId: other key and initiator, so the victim's waiter stays open.
   */
  @Test
  void spoofedDuplicateAttributesCannotCompleteSomeoneElsesWaiter() {
    CompletableFuture<StatusMessage> victim = waiters.register("T-VICTIM", KEY, PAYER);
    StatusMessage spoof =
        status(
            "T-ATTACKER", "COMPLETED", "attacker-key", "01711000099",
            Map.of("requestTxnId", "T-VICTIM", "duplicate", "true"));
    assertThat(waiters.offer(spoof)).isFalse();
    assertThat(victim).isNotDone();
    assertThat(waiters.size()).isEqualTo(1);
  }

  @Test
  void aRecordWithTheRightTxnIdButAnotherKeyOrInitiatorIsIgnored() {
    CompletableFuture<StatusMessage> f = waiters.register("T5", KEY, PAYER);
    assertThat(waiters.offer(status("T5", "COMPLETED", "other-key", PAYER, null))).isFalse();
    assertThat(waiters.offer(status("T5", "COMPLETED", KEY, "01711000099", null))).isFalse();
    assertThat(waiters.offer(status("T5", "COMPLETED", KEY, null, null))).isFalse();
    assertThat(f).isNotDone();
    assertThat(waiters.offer(status("T5", "COMPLETED", null))).isTrue();
  }
}
