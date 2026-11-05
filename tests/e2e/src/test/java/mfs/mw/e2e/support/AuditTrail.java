package mfs.mw.e2e.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * The audit trail (mw-audit-dw, spec 14.3) as an auditor reads it. No service exposes audit rows
 * yet (the admin API comes with WP23) and the service's own role may not read them, so the suite
 * reads {@code audit.event} with the PostgreSQL superuser through {@code docker compose exec}
 * ({@link Stack#psql}): read-only, test-only.
 *
 * <p>The chain check recomputes every hash independently from the documented canonical form:
 * SHA-256 over the JSON array {@code [month, seq, auditId, recordedAt, at, txnId, correlationId,
 * actorType, actorId, action, resource, outcome, service, sourceIp, payloadRef, before, after,
 * prevHashHex]} (instants ISO-8601 UTC, before/after as jsonb text, first prevHash 32 zero bytes),
 * then checks the links and the month's {@code chain_head}.
 */
public final class AuditTrail {

  private static final HexFormat HEX = HexFormat.of();
  private static final String GENESIS = "00".repeat(32);
  private static final String COLUMNS =
      "month, seq, audit_id, recorded_at, at, txn_id, correlation_id, actor_type, actor_id, action,"
          + " resource, outcome, service, source_ip, payload_ref, before::text as before,"
          + " after::text as after, encode(prev_hash, 'hex') as prev_hash, encode(hash, 'hex') as hash";

  private AuditTrail() {}

  /** One {@code audit.event} row (hashes in hex). */
  public record Row(
      String month, long seq, String auditId, String recordedAt, String at, String txnId,
      String correlationId, String actorType, String actorId, String action, String resource,
      String outcome, String service, String sourceIp, String payloadRef, String before,
      String after, String prevHash, String hash) {

    /** {@code after.status} of a {@code txn.state.changed} row. */
    public String afterStatus() {
      return after == null ? null : Http.JSON.readTree(after).path("status").asString(null);
    }
  }

  /** The audit rows of one transaction in chain order. */
  public static List<Row> rowsOfTxn(String txnId) {
    UUID id = UUID.fromString(txnId); // also keeps the SQL literal safe
    return query("txn_id = '" + id + "'");
  }

  /** The audit rows of one org bulk batch (resource {@code bulk-batch/<batchId>}) in chain order. */
  public static List<Row> rowsOfBatch(String batchId) {
    UUID id = UUID.fromString(batchId); // also keeps the SQL literal safe
    return query("resource = 'bulk-batch/" + id + "'");
  }

  /** Action of the orchestrator's one-row-per-state audit (spec 14.3). */
  public static final String STATE_CHANGED = "txn.state.changed";

  /** Action of the adapters' audit of one partner call (spec 12.2, 17). */
  public static final String PARTNER_CALL = "partner.call.completed";

  /**
   * The {@code txn.state.changed} rows of one transaction in chain order: a transaction's trail
   * also holds partner-call and callback rows, which carry no state.
   */
  public static List<Row> stateRowsOfTxn(String txnId) {
    return rowsOfTxn(txnId).stream().filter(r -> STATE_CHANGED.equals(r.action())).toList();
  }

  /**
   * Verifies the whole hash chain of {@code month} ({@code yyyy-MM}); returns {@code null} when it
   * is intact, else why it is broken.
   */
  public static String verifyChain(String month) {
    if (!month.matches("\\d{4}-\\d{2}")) {
      throw new IllegalArgumentException("month must be yyyy-MM: " + month);
    }
    List<Row> rows = query("month = '" + month + "'");
    String prev = GENESIS;
    long expected = 1;
    for (Row r : rows) {
      if (r.seq() != expected) {
        return "seq " + expected + ": row missing";
      }
      if (!r.prevHash().equals(prev)) {
        return "seq " + r.seq() + ": prevHash does not link to the previous row";
      }
      if (!r.hash().equals(hash(r))) {
        return "seq " + r.seq() + ": hash does not match the row";
      }
      prev = r.hash();
      expected++;
    }
    String head =
        Stack.get()
            .psql("select seq || ' ' || encode(hash, 'hex') from audit.chain_head where month = '" + month + "'");
    String want = (expected - 1) + " " + prev;
    return head.equals(want) ? null : "chain head '" + head + "' does not match the last row '" + want + "'";
  }

  private static List<Row> query(String where) {
    String json =
        Stack.get()
            .psql(
                "select coalesce(json_agg(r order by r.month, r.seq), '[]') from (select "
                    + COLUMNS + " from audit.event where " + where + ") r");
    List<Row> rows = new ArrayList<>();
    for (JsonNode n : Http.JSON.readTree(json)) {
      rows.add(
          new Row(
              text(n, "month"), n.get("seq").asLong(), text(n, "audit_id"), instant(n, "recorded_at"),
              instant(n, "at"), text(n, "txn_id"), text(n, "correlation_id"), text(n, "actor_type"),
              text(n, "actor_id"), text(n, "action"), text(n, "resource"), text(n, "outcome"),
              text(n, "service"), text(n, "source_ip"), text(n, "payload_ref"), text(n, "before"),
              text(n, "after"), text(n, "prev_hash"), text(n, "hash")));
    }
    return rows;
  }

  private static String hash(Row r) {
    List<Object> fields =
        Arrays.asList(
            r.month(), r.seq(), r.auditId(), r.recordedAt(), r.at(), r.txnId(), r.correlationId(),
            r.actorType(), r.actorId(), r.action(), r.resource(), r.outcome(), r.service(),
            r.sourceIp(), r.payloadRef(), r.before(), r.after(), r.prevHash());
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(Http.JSON.writeValueAsString(fields).getBytes(StandardCharsets.UTF_8));
      return HEX.formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String text(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v == null || v.isNull() ? null : v.asString();
  }

  /** PostgreSQL's JSON timestamp (e.g. {@code 2026-11-04T04:51:19.12345+00:00}) as Instant.toString. */
  private static String instant(JsonNode n, String field) {
    String v = text(n, field);
    return v == null ? null : OffsetDateTime.parse(v).toInstant().toString();
  }
}
