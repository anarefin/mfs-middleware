package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The one error body for every API (spec 5.3): {@code { code, message, messageBn?, txnId?,
 * correlationId, retryable, details[] }}. Optional fields are omitted when absent.
 */
public record ErrorEnvelope(
    String code,
    String message,
    @JsonInclude(JsonInclude.Include.NON_NULL) String messageBn,
    @JsonInclude(JsonInclude.Include.NON_NULL) String txnId,
    String correlationId,
    boolean retryable,
    List<ErrorDetail> details) {

  public ErrorEnvelope {
    details = details == null ? List.of() : List.copyOf(details);
  }
}
