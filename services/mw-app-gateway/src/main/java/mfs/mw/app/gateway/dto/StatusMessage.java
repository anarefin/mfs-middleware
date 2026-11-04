package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * A state change read from {@code mw.txn.status.v1} (contract {@code
 * events/mw-orchestrator/mw.txn.status.v1}, owned by mw-orchestrator). Only the fields this
 * service shows the app; unknown fields are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StatusMessage(
    int schemaVersion,
    String txnId,
    String idempotencyKey,
    String correlationId,
    String channel,
    String txnType,
    String status,
    String previousStatus,
    Party initiator,
    Party debitParty,
    Party creditParty,
    Amount amount,
    Amount fee,
    String coreRef,
    ErrorInfo error,
    String locale,
    Map<String, String> attributes,
    Instant requestedAt,
    Instant updatedAt) {

  /** Attribute the orchestrator sets on the answer to a repeated request (spec 7.3). */
  public static final String REQUEST_TXN_ID = "requestTxnId";

  public static final String DUPLICATE = "duplicate";

  /** True for the answer to a repeated idempotency key: it describes the stored transaction. */
  public boolean duplicate() {
    return attributes != null && "true".equals(attributes.get(DUPLICATE));
  }

  /**
   * The txnId the sender of the answered request waits for: {@code attributes.requestTxnId} on a
   * duplicate answer, else the record's own txnId.
   */
  public String awaitedTxnId() {
    if (duplicate() && attributes.get(REQUEST_TXN_ID) != null) {
      return attributes.get(REQUEST_TXN_ID);
    }
    return txnId;
  }

  /** States after which nothing changes any more for the app (spec 7.2). */
  public boolean terminal() {
    return switch (status == null ? "" : status) {
      case "COMPLETED", "FAILED", "REJECTED", "REVERSED" -> true;
      default -> false;
    };
  }
}
