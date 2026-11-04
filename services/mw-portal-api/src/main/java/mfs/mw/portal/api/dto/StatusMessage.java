package mfs.mw.portal.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Map;

/**
 * A state change read from {@code mw.txn.status.v1} (contract {@code
 * events/mw-orchestrator/mw.txn.status.v1}, owned by mw-orchestrator): only the fields that settle
 * a bulk row. Unknown fields are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StatusMessage(
    String txnId,
    String channel,
    String txnType,
    String status,
    Error error,
    String coreRef,
    Map<String, String> attributes,
    Instant updatedAt) {

  /** The error subset of the status contract. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Error(String code, String message, Boolean retryable) {}
}
