package mfs.mw.orchestrator.service.saga;

/** The event is not allowed in this state (spec 7.2). */
public class InvalidTransitionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient Status from;
  private final transient SagaEvent event;

  public InvalidTransitionException(Status from, SagaEvent event) {
    super("no transition from " + from + " on " + event.getClass().getSimpleName());
    this.from = from;
    this.event = event;
  }

  public Status from() {
    return from;
  }

  public SagaEvent event() {
    return event;
  }
}
