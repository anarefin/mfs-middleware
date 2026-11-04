package mfs.mw.orchestrator.service.saga;

import java.util.Optional;

/**
 * The reusable saga steps of spec 7.1 ({@code validate}, {@code hold}, {@code callPartner},
 * {@code commit}, {@code release}, {@code reverse}, plus posting and the core status query). A
 * {@link Flow} is the set of steps its transaction type uses; {@code SagaEngine} performs a step
 * only for a flow that has it.
 */
public enum Step {
  VALIDATE,
  POST,
  HOLD,
  CALL_PARTNER,
  COMMIT,
  RELEASE,
  REVERSE,
  /** QueryTransaction to the core (after a HOLDING timeout). */
  QUERY,
  /** Status inquiry of an UNKNOWN outcome (spec 7.4): partner STATUS_QUERY or core query. */
  INQUIRE;

  /** The step that performs {@code effect}; empty for effects that send nothing. */
  public static Optional<Step> of(Effect effect) {
    return Optional.ofNullable(
        switch (effect) {
          case SEND_VALIDATE -> VALIDATE;
          case SEND_POST -> POST;
          case SEND_HOLD -> HOLD;
          case SEND_PARTNER_REQUEST -> CALL_PARTNER;
          case SEND_COMMIT -> COMMIT;
          case SEND_RELEASE -> RELEASE;
          case SEND_REVERSE -> REVERSE;
          case SEND_QUERY -> QUERY;
          case SEND_INQUIRY -> INQUIRE;
          case NONE, SCHEDULE_INQUIRY -> null;
        });
  }
}
