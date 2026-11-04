package mfs.mw.app.gateway.service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import mfs.mw.app.gateway.dto.StatusMessage;
import org.springframework.stereotype.Component;

/**
 * Requests of this instance waiting for their answer (spec 7.5): {@code txnId -> future}. The
 * reply consumer only completes futures, so it never blocks; the HTTP request thread does the
 * waiting, bounded by the budget.
 */
@Component
public class ReplyWaiters {

  private final ConcurrentMap<String, CompletableFuture<StatusMessage>> waiting =
      new ConcurrentHashMap<>();

  /** Registers a waiter; call before publishing, so an early answer is not lost. */
  public CompletableFuture<StatusMessage> register(String txnId) {
    CompletableFuture<StatusMessage> f = new CompletableFuture<>();
    waiting.put(txnId, f);
    return f;
  }

  public void remove(String txnId) {
    waiting.remove(txnId);
  }

  /**
   * Hands a status record to its waiter, if any: a terminal status, or the answer to a repeated
   * idempotency key (whatever its status, since no further record will name this txnId).
   *
   * @return whether a waiter was completed
   */
  public boolean offer(StatusMessage status) {
    if (!status.terminal() && !status.duplicate()) {
      return false;
    }
    CompletableFuture<StatusMessage> f = waiting.remove(status.awaitedTxnId());
    return f != null && f.complete(status);
  }

  /** Number of requests currently waiting (for tests and metrics). */
  public int size() {
    return waiting.size();
  }
}
