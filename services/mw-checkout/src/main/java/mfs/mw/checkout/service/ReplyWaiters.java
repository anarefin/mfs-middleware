package mfs.mw.checkout.service;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import mfs.mw.checkout.dto.StatusMessage;
import org.springframework.stereotype.Component;

/**
 * Requests of this instance waiting for their answer (spec 7.5): {@code txnId -> waiter}. The
 * reply consumer only completes futures, so it never blocks; the HTTP request thread does the
 * waiting, bounded by the budget.
 *
 * <p>A record completes a waiter only if it belongs to that request: the same idempotency key
 * and the same initiator. The orchestrator scopes idempotency by (channel, initiator, key) and
 * puts both on every status record, including the answer to a repeated key, so a record of
 * another customer's transaction cannot complete this one, whatever its attributes say.
 */
@Component
public class ReplyWaiters {

  private record Waiter(
      CompletableFuture<StatusMessage> future, String idempotencyKey, String initiatorId) {

    boolean owns(StatusMessage s) {
      return Objects.equals(idempotencyKey, s.idempotencyKey())
          && s.initiator() != null
          && Objects.equals(initiatorId, s.initiator().id());
    }
  }

  private final ConcurrentMap<String, Waiter> waiting = new ConcurrentHashMap<>();

  /** Registers a waiter; call before publishing, so an early answer is not lost. */
  public CompletableFuture<StatusMessage> register(
      String txnId, String idempotencyKey, String initiatorId) {
    CompletableFuture<StatusMessage> f = new CompletableFuture<>();
    waiting.put(txnId, new Waiter(f, idempotencyKey, initiatorId));
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
    String txnId = status.awaitedTxnId();
    Waiter w = waiting.get(txnId);
    if (w == null || !w.owns(status) || !waiting.remove(txnId, w)) {
      return false;
    }
    return w.future().complete(status);
  }

  /** Number of requests currently waiting (for tests and metrics). */
  public int size() {
    return waiting.size();
  }
}
