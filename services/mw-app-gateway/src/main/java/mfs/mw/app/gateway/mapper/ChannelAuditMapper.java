package mfs.mw.app.gateway.mapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import mfs.mw.app.gateway.dto.AuditEvent;
import mfs.mw.app.gateway.util.MaskingPolicy;

/**
 * A transaction request the channel served to its CHANNEL_IN audit hop (ticket 130b, FRS AUD-01,
 * AUD-04): action {@code channel.request.completed}, resource {@code txn/<txnId>}, actor the
 * initiator ({@code user}, its id masked), {@code after} the channel, txnType, the status answered
 * and its error code, outcome FAILURE for a REJECTED or FAILED answer. Payloads: the request IN and
 * the answer OUT, rendered by {@link MaskingPolicy#describe} (sensitive fields masked, free text
 * scrubbed; the assertion blanked), cut to {@link #MAX_BODY}.
 */
public final class ChannelAuditMapper {

  public static final String ACTION = "channel.request.completed";
  public static final String HOP = "CHANNEL_IN";
  public static final int MAX_BODY = 4096;

  private static final Set<String> FAILED = Set.of("REJECTED", "FAILED");

  private ChannelAuditMapper() {}

  /** The facts of one served request; {@code request} and {@code answer} are rendered masked. */
  public record Served(
      String service, String channel, String txnId, String correlationId, String actorId, String txnType,
      String status, String errorCode, Object request, Object answer) {}

  public static AuditEvent channelIn(Served s, Instant at, long durationMs) {
    Map<String, String> after = new HashMap<>();
    put(after, "channel", s.channel());
    put(after, "txnType", s.txnType());
    put(after, "status", s.status());
    put(after, "errorCode", s.errorCode());
    List<AuditEvent.Payload> payload = new ArrayList<>();
    if (s.request() != null) {
      payload.add(new AuditEvent.Payload("IN", "text/plain", excerpt(MaskingPolicy.describe(s.request()))));
    }
    if (s.answer() != null) {
      payload.add(new AuditEvent.Payload("OUT", "text/plain", excerpt(MaskingPolicy.describe(s.answer()))));
    }
    String actor = s.actorId() == null || s.actorId().isBlank() ? "unknown" : MaskingPolicy.scrub(s.actorId());
    return new AuditEvent(
        1,
        UUID.randomUUID().toString(),
        s.txnId(),
        s.correlationId() == null || s.correlationId().isBlank() ? s.txnId() : s.correlationId(),
        new AuditEvent.Actor("user", actor),
        ACTION,
        "txn/" + s.txnId(),
        after,
        s.status() != null && FAILED.contains(s.status()) ? "FAILURE" : "SUCCESS",
        at,
        s.service(),
        HOP,
        "IN",
        Math.max(0, durationMs),
        payload.isEmpty() ? null : payload);
  }

  private static String excerpt(String text) {
    return text.length() <= MAX_BODY ? text : text.substring(0, MAX_BODY);
  }

  private static void put(Map<String, String> map, String key, String value) {
    if (value != null) {
      map.put(key, value);
    }
  }
}
