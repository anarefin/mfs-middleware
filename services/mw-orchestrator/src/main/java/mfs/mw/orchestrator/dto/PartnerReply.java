package mfs.mw.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Map;

/**
 * An adapter's answer on {@code mw.partner.reply.v1} (contract {@code mw.partner.reply.v1}, owned
 * by this service). Unknown fields are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PartnerReply(
    int schemaVersion,
    String family,
    String requestId,
    String txnId,
    String correlationId,
    String partnerId,
    Instant repliedAt,
    Payload payload) {

  @JsonIgnoreProperties(ignoreUnknown = true)
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
