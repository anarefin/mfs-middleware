package mfs.mw.adapter.billpay.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * This service's own type for {@code mw.partner.reply.v1} (contract owned by mw-orchestrator,
 * shared payload). {@code PartnerReplySchemaTest} keeps it aligned with the contract.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PartnerReply(
    int schemaVersion,
    String family,
    String requestId,
    String txnId,
    String correlationId,
    String partnerId,
    Instant repliedAt,
    Payload payload) {

  /** FAILED carries a canonical {@code errorCode}; SUCCESS the partner's own {@code partnerRef}. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Payload(
      String operation,
      String outcome,
      String partnerRef,
      String partnerCode,
      String errorCode,
      String message,
      Boolean retryable,
      Map<String, String> attributes) {}
}
