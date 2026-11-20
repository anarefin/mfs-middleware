package mfs.mw.orchestrator.service;

import mfs.mw.orchestrator.client.CoreWalletClient;
import mfs.mw.orchestrator.dto.CoreCommand;
import mfs.mw.orchestrator.dto.CoreReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs a saga step's core call off the thread that applied the step (ADR D31, ticket 95a; root rule:
 * no blocking call on a Kafka listener thread). {@link SagaEngine} records the call on the saga
 * timeline and arms the step's deadline in its transaction; the call is handed to the {@link
 * CoreCallExecutor} (a virtual thread) only once that transaction has committed, so the core never
 * sees a step the database does not know. The answer re-enters the saga in a transaction of its own
 * ({@link CoreReplyService}, PLT-021). A crash between the commit and the call loses nothing: the
 * persisted deadline fires and decides as for a lost answer.
 */
@Service
public class CoreCallDispatcher {

  private static final Logger log = LoggerFactory.getLogger(CoreCallDispatcher.class);

  private final CoreWalletClient client;
  private final CoreCallExecutor executor;
  // CoreReplyService -> SagaEngine -> this: resolved at call time, not at construction
  private final ObjectProvider<CoreReplyService> replies;

  public CoreCallDispatcher(CoreWalletClient client, CoreCallExecutor executor, ObjectProvider<CoreReplyService> replies) {
    this.client = client;
    this.executor = executor;
    this.replies = replies;
  }

  /** Calls the core for {@code command} after the current transaction commits (at once outside one). */
  public void dispatch(CoreCommand command) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          submit(command);
        }
      });
    } else {
      submit(command);
    }
  }

  private void submit(CoreCommand command) {
    try {
      executor.submit(() -> call(command));
    } catch (RuntimeException e) {
      // shutting down: the persisted deadline decides as for a lost answer
      log.atWarn()
          .addKeyValue("event", "core.call.not_started")
          .addKeyValue("txnId", command.txnId())
          .addKeyValue("commandType", command.commandType())
          .log("core call not started; the step's deadline settles it");
    }
  }

  private void call(CoreCommand command) {
    if (command.correlationId() != null) {
      MDC.put("correlationId", command.correlationId());
    }
    MDC.put("txnId", command.txnId());
    try {
      CoreReply reply = client.call(command);
      replies.getObject().handle(reply);
    } catch (RuntimeException e) {
      log.atError()
          .addKeyValue("event", "core.reply.failed")
          .addKeyValue("txnId", command.txnId())
          .addKeyValue("commandType", command.commandType())
          .addKeyValue("errorClass", e.getClass().getSimpleName())
          .log("core answer could not be applied; the step's deadline settles it");
    } finally {
      MDC.remove("correlationId");
      MDC.remove("txnId");
    }
  }
}
