package mfs.mw.admin.api.util;

import java.util.UUID;
import org.slf4j.MDC;

/**
 * The correlation id of an event this service writes: the request's ({@code correlationId} in
 * the MDC, set by {@code filter/CorrelationIdFilter}) or, without one (a scheduled job), a fresh
 * plain UUID, an RFC 9562 id the masking rules keep whole in logs and audit (18.3).
 */
public final class CorrelationIds {

  private CorrelationIds() {}

  /** {@code correlationId} when present, else a new random UUID. */
  public static String orNew(String correlationId) {
    return correlationId == null || correlationId.isBlank() ? UUID.randomUUID().toString() : correlationId;
  }

  /** The MDC's {@code correlationId}, else a new random UUID. */
  public static String current() {
    return orNew(MDC.get("correlationId"));
  }
}
