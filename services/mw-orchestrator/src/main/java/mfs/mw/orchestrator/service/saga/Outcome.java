package mfs.mw.orchestrator.service.saga;

import java.util.Optional;

/**
 * A partner's answer as {@code mw.partner.reply.v1} / {@code mw.partner.inbound.v1} spell it
 * ({@code payload.outcome}). The DTOs keep the raw string; services read it through {@link
 * #parse}, so an unexpected value is "no outcome" rather than a deserialisation failure.
 */
public enum Outcome {
  SUCCESS,
  FAILED,
  UNKNOWN,
  /** Answers a STATUS_QUERY only: the partner has no record of the request (spec 7.4). */
  NOT_FOUND,
  PENDING;

  public static Optional<Outcome> parse(String value) {
    if (value == null) {
      return Optional.empty();
    }
    for (Outcome o : values()) {
      if (o.name().equals(value)) {
        return Optional.of(o);
      }
    }
    return Optional.empty();
  }

  /**
   * Whether this outcome, arriving after the transaction left PARTNER_PENDING / UNKNOWN,
   * contradicts where it stands ({@code txn.outcome.conflict}): a success for a transaction that
   * was refunded, a refusal for one that was paid, and any definite answer for one an operator is
   * deciding (MANUAL_REVIEW), which the operator must see.
   */
  public boolean contradicts(Status status) {
    return switch (this) {
      case SUCCESS -> status == Status.RELEASING || status == Status.FAILED || status == Status.MANUAL_REVIEW;
      case FAILED, NOT_FOUND ->
          switch (status) {
            case COMMITTING, COMPLETED, REVERSING, REVERSED, MANUAL_REVIEW -> true;
            default -> false;
          };
      case UNKNOWN, PENDING -> false;
    };
  }
}
