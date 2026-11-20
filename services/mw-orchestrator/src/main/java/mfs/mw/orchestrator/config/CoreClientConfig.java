package mfs.mw.orchestrator.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import mfs.mw.orchestrator.service.CoreCallExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The executor of the saga's core calls (ADR D31, ticket 95a): one virtual thread per call, named
 * {@code core-call-<n>}, so a call blocked on the core never holds a Kafka listener, the timer sweep
 * or a request thread. The core client's bulkhead {@code core} bounds how many run at once. Not an
 * {@code Executor} bean, so Boot's own task executor stays as it is.
 */
@Configuration(proxyBeanMethods = false)
public class CoreClientConfig {

  /** The thread name prefix of a core call (tests check calls never run on a listener thread). */
  public static final String THREAD_PREFIX = "core-call-";

  /** Virtual threads; closed (waiting for running calls) when the context stops. */
  static final class VirtualThreadCalls implements CoreCallExecutor, AutoCloseable {
    private final ExecutorService threads =
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(THREAD_PREFIX, 0).factory());

    @Override
    public void submit(Runnable call) {
      threads.execute(call);
    }

    @Override
    public void close() {
      threads.close();
    }
  }

  @Bean
  CoreCallExecutor coreCallExecutor() {
    return new VirtualThreadCalls();
  }
}
