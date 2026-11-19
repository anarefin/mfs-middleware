package mfs.mw.core.mock.dto;

/**
 * What a mock-control scenario makes an operation do (FRS MCT-01, ADR D33). Three dimensions, each
 * resolved on its own for a call: the outcome ({@code SUCCESS}, {@code BUSINESS_ERROR}, {@code TIMEOUT},
 * {@code HTTP_5XX}, {@code MALFORMED_RESPONSE}, {@code CONNECTION_RESET}, {@code PARTIAL_SUCCESS}; when no
 * scenario sets it, the magic value of the call decides), an extra delay ({@code DELAY}) and the callback
 * ({@code DUPLICATE_CALLBACK}, {@code LATE_CALLBACK}, {@code OUT_OF_ORDER_CALLBACK}, {@code NO_CALLBACK};
 * FRS MCK-06). Copied from mw-simulators (ticket 103, module isolation). For the core party the
 * "callback" is the result event the core publishes on {@code core.*.event.v1} (the asynchronous answer
 * of a command): DUPLICATE = published twice, LATE = after {@code delayMs}, NO_CALLBACK = never (applied,
 * the reply lost); OUT_OF_ORDER and PARTIAL_SUCCESS have no core meaning and answer normally.
 */
public enum MockBehaviour {
  /** Answer success, whatever the magic value says. */
  SUCCESS(Dimension.OUTCOME),
  /** The party's business refusal; {@code code} replaces its default refusal code. */
  BUSINESS_ERROR(Dimension.OUTCOME),
  /** Applied, the reply held {@code delayMs} (default the party's timeout hold). */
  TIMEOUT(Dimension.OUTCOME),
  /** Extra {@code delayMs} before the reply; the outcome is decided as without it. */
  DELAY(Dimension.DELAY),
  /** Applied, then answered {@code httpStatus} (default 500). */
  HTTP_5XX(Dimension.OUTCOME),
  /** Applied, the reply body cut short (not parseable). */
  MALFORMED_RESPONSE(Dimension.OUTCOME),
  /** Applied, then the connection is closed without an answer. */
  CONNECTION_RESET(Dimension.OUTCOME),
  /** The callback is sent twice. */
  DUPLICATE_CALLBACK(Dimension.CALLBACK),
  /** The callback is sent after {@code delayMs}. */
  LATE_CALLBACK(Dimension.CALLBACK),
  /** The outcome callback is followed by a stale PENDING one. */
  OUT_OF_ORDER_CALLBACK(Dimension.CALLBACK),
  /** No callback (a partner that never calls back; replay still works). */
  NO_CALLBACK(Dimension.CALLBACK),
  /** The party's partial reply (status {@code PARTIAL}). */
  PARTIAL_SUCCESS(Dimension.OUTCOME);

  /** The part of a call's behaviour a scenario decides. */
  public enum Dimension {
    OUTCOME,
    DELAY,
    CALLBACK
  }

  private final Dimension dimension;

  MockBehaviour(Dimension dimension) {
    this.dimension = dimension;
  }

  public Dimension dimension() {
    return dimension;
  }
}
