package mfs.mw.adapter.interop.dto;

import java.time.Instant;
import java.util.Map;

/**
 * {@code mw.partner.recon.v1} (own contract, ticket 86): one line of a partner's reconciliation or
 * settlement file read by a batch route, for the future mw-recon service. Key: partnerId. {@code
 * fields} is the partner's data as text: never logged.
 */
public record PartnerReconEvent(
    int schemaVersion,
    String partnerId,
    String family,
    String fileName,
    String fileId,
    int lineNumber,
    boolean last,
    Map<String, String> fields,
    String reportedBy,
    Instant receivedAt) {

  public PartnerReconEvent {
    fields = fields == null ? Map.of() : Map.copyOf(fields);
  }
}
