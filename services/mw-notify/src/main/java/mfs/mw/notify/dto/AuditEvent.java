package mfs.mw.notify.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * This service's own type for {@code mw.audit.event.v1} (spec 14.3; contract owned by
 * mw-audit-dw). mw-notify emits one per final status of a transaction's notification (ticket 130b,
 * FRS AUD-01, AUD-04): action {@code notification.completed}, hop NOTIFICATION, direction OUT, the
 * channel, status, template and error code in {@code after}, and the rendered text of a
 * transaction notice (params masked when it was rendered, scrubbed again) as {@code payload}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditEvent(
    int schemaVersion,
    String auditId,
    String txnId,
    String correlationId,
    Actor actor,
    String action,
    String resource,
    Map<String, String> after,
    String outcome,
    Instant at,
    String service,
    String hop,
    String direction,
    List<Payload> payload) {

  public AuditEvent {
    after = after == null ? null : Map.copyOf(after);
    payload = payload == null ? null : List.copyOf(payload);
  }

  /** One hop body: the text sent ({@code OUT}), already masked. */
  public record Payload(String direction, String contentType, String body) {}

  /** {@code system}, {@code user} or {@code partner}, and who. */
  public record Actor(String type, String id) {}
}
