package mfs.mw.admin.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;

/**
 * This service's own type for the REVERSAL it sends on {@code mw.txn.request.v1} (contract owned by
 * mw-orchestrator; ticket 37): channel {@code SYSTEM}, the original's txnId and full amount. The
 * orchestrator reverses the original's own parties, so initiator, debit and credit party name only
 * the operations organisation ({@code ORG}/{@code bits-ops}): no customer data leaves this service.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReversalRequest(
    int schemaVersion,
    String txnId,
    String idempotencyKey,
    String correlationId,
    String channel,
    String txnType,
    Party initiator,
    Party debitParty,
    Party creditParty,
    AdminTxn.Amount amount,
    String originalTxnId,
    Instant requestedAt,
    Map<String, String> attributes) {

  public ReversalRequest {
    attributes = attributes == null ? null : Map.copyOf(attributes);
  }

  public record Party(String type, String id) {}
}
