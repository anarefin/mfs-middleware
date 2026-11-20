package mfs.mw.orchestrator.dto;

import tools.jackson.databind.JsonNode;

/**
 * The core's answer to one REST call of a saga step (ADR D31, ticket 95a; contract {@code
 * api/mw-core-mock/core-wallet.v1.openapi.yaml}), as {@code client/CoreWalletClient} received it.
 * {@code mapper/CoreReplyMapper} reads it into a saga event.
 *
 * @param commandType the step's call ({@code ValidateTransaction}, {@code HoldFunds}, ...)
 * @param commandId the step id the saga timeline records it under ({@code <txnId>:<step>})
 * @param txnId the txnId the call was made under (the reversal's for a reversal)
 * @param kind whether the core answered, refused, or the outcome is unknown
 * @param status the HTTP status, 0 when no response arrived (timeout, connection, breaker, bulkhead)
 * @param code the error envelope's {@code code} of a refusal ({@code MW-CORE-<reason>}, {@code
 *     MW-AUTH-046}), else null
 * @param body the response body (the answer, or the error envelope), null when there is none
 */
public record CoreReply(
    String commandType, String commandId, String txnId, Kind kind, int status, String code, JsonNode body) {

  /** How definite the answer is. */
  public enum Kind {
    /** 2xx: the core did it (or reports what it knows, for a read). */
    ANSWERED,
    /** A definite refusal: 422 {@code MW-CORE-*}, 401 {@code MW-AUTH-046}, another 4xx about the request. */
    REFUSED,
    /**
     * No definite answer: a timeout, a 5xx, a broken connection, the breaker or bulkhead refusing the
     * call, a refused service token, a 409. The step's deadline settles it (spec 7.2, 7.4).
     */
    AMBIGUOUS
  }

  /** An unknown outcome of {@code command}'s call. */
  public static CoreReply ambiguous(CoreCommand command, int status) {
    return new CoreReply(command.commandType(), command.commandId(), command.txnId(), Kind.AMBIGUOUS, status, null, null);
  }
}
