package mfs.mw.admin.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The audit trail as mw-audit-dw's {@code audit-query.v1} returns it (own type; unknown fields
 * ignored). Values are stored masked (spec 18.3), so they are shown as they come; operator
 * usernames ({@code actor.id} of a {@code user}) are staff data and not masked.
 */
public final class AuditTrail {

  private AuditTrail() {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Entry(
      String auditId,
      String month,
      Long seq,
      Instant recordedAt,
      Instant at,
      String txnId,
      String correlationId,
      Actor actor,
      String action,
      String resource,
      String outcome,
      String service,
      String sourceIp,
      String payloadRef,
      Map<String, Object> before,
      Map<String, Object> after) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Actor(String type, String id) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Page(List<Entry> items, int limit, int offset) {

    public Page {
      items = items == null ? List.of() : List.copyOf(items);
    }
  }
}
