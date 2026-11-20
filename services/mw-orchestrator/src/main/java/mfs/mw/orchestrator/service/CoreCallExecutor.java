package mfs.mw.orchestrator.service;

/**
 * Where a saga step's core call runs (ADR D31, ticket 95a): never on the thread that applied the step
 * (a Kafka listener, the timer sweep, an API request). The bean ({@code config/CoreClientConfig}) runs
 * each call on its own virtual thread; a test may run them in line.
 */
@FunctionalInterface
public interface CoreCallExecutor {

  void submit(Runnable call);
}
