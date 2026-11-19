package mfs.mw.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The one error body for every API (spec 5.3): {@code { code, message, messageBn?, txnId?,
 * correlationId, retryable, details[], timestamp? }}. Optional fields are omitted when absent.
 * {@code timestamp} (FRS 3.7, ticket 92) is when the error was built, UTC ISO-8601 to the
 * millisecond: every envelope this service builds carries it (the seven-argument constructor stamps
 * it); an envelope read from another service keeps whatever that one sent.
 */
public record ErrorEnvelope(
    String code,
    String message,
    @JsonInclude(JsonInclude.Include.NON_NULL) String messageBn,
    @JsonInclude(JsonInclude.Include.NON_NULL) String txnId,
    String correlationId,
    boolean retryable,
    List<ErrorDetail> details,
    @JsonInclude(JsonInclude.Include.NON_NULL) String timestamp) {

  public ErrorEnvelope {
    details = details == null ? List.of() : List.copyOf(details);
  }

  /** A new envelope, stamped now. */
  public ErrorEnvelope(
      String code,
      String message,
      String messageBn,
      String txnId,
      String correlationId,
      boolean retryable,
      List<ErrorDetail> details) {
    this(code, message, messageBn, txnId, correlationId, retryable, details, now());
  }

  /** The current UTC time as an envelope timestamp ({@code 2026-11-19T08:15:30.123Z}). */
  public static String now() {
    return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
  }
}
