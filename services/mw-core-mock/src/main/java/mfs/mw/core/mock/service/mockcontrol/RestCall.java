package mfs.mw.core.mock.service.mockcontrol;

import java.util.Optional;

/**
 * The {@code /core/v1} call in progress on this thread (ticket 103): {@code filter/MockControlFilter}
 * opens it, checks the switch once ({@link #admitted}) and carries out the decision the engine made
 * for the call ({@link #decision}: the REST answer held, replaced by a 5xx, cut short or dropped).
 * Kafka commands run outside one, so the engine checks the switch itself and the effects shape only
 * the result event.
 */
public final class RestCall {

  private static final ThreadLocal<RestCall> CURRENT = new ThreadLocal<>();

  private volatile Decision decision;

  private RestCall() {}

  /** Opens a call on this thread (the filter; always paired with {@link #end}). */
  public static RestCall begin() {
    RestCall call = new RestCall();
    CURRENT.set(call);
    return call;
  }

  public static void end() {
    CURRENT.remove();
  }

  public static Optional<RestCall> current() {
    return Optional.ofNullable(CURRENT.get());
  }

  /** True inside a REST call: its switch was checked by the filter. */
  static boolean admitted() {
    return CURRENT.get() != null;
  }

  /** The decision of the call's operation (the last one when it ran more than one), else null. */
  public Decision decision() {
    return decision;
  }

  void decided(Decision d) {
    this.decision = d;
  }
}
