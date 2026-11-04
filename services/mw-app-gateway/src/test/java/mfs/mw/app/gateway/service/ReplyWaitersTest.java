package mfs.mw.app.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import mfs.mw.app.gateway.dto.StatusMessage;
import org.junit.jupiter.api.Test;

/** Which status records complete a waiting request (spec 7.3, 7.5). */
class ReplyWaitersTest {

  final ReplyWaiters waiters = new ReplyWaiters();

  static StatusMessage status(String txnId, String status, Map<String, String> attributes) {
    return new StatusMessage(
        1, txnId, "k", "c", "CUSTOMER_APP", "P2P", status, null, null, null, null, null, null,
        null, null, null, attributes, null, null);
  }

  @Test
  void intermediateStatusesDoNotCompleteTerminalOnesDo() {
    CompletableFuture<StatusMessage> f = waiters.register("T1");
    assertThat(waiters.offer(status("T1", "VALIDATING", null))).isFalse();
    assertThat(f).isNotDone();
    assertThat(waiters.offer(status("T1", "COMPLETED", null))).isTrue();
    assertThat(f).isCompleted();
    assertThat(waiters.size()).isZero();
  }

  @Test
  void aDuplicateAnswerCompletesTheRequestItNamesWhateverItsStatus() {
    CompletableFuture<StatusMessage> f = waiters.register("T2");
    StatusMessage dup = status("T-STORED", "POSTING", Map.of("requestTxnId", "T2", "duplicate", "true"));
    assertThat(waiters.offer(dup)).isTrue();
    assertThat(f.join().txnId()).isEqualTo("T-STORED");
  }

  @Test
  void noWaiterNoEffect() {
    assertThat(waiters.offer(status("T3", "FAILED", null))).isFalse();
    waiters.register("T4");
    waiters.remove("T4");
    assertThat(waiters.offer(status("T4", "COMPLETED", null))).isFalse();
  }
}
