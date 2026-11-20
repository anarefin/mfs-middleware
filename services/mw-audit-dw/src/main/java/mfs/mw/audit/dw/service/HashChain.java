package mfs.mw.audit.dw.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mfs.mw.audit.dw.entity.AuditRow;
import mfs.mw.audit.dw.entity.ChainHead;
import mfs.mw.audit.dw.entity.PayloadRow;
import tools.jackson.databind.json.JsonMapper;

/**
 * The hash chain of spec 14.3, one per monthly partition: {@code hash = SHA-256(canonical row)},
 * where the canonical row is the JSON array {@code [month, seq, auditId, recordedAt, at, txnId,
 * correlationId, actorType, actorId, action, resource, outcome, service, sourceIp, payloadRef,
 * before, after, prevHashHex]} (UTF-8, no spaces, null for absent values, instants as ISO-8601
 * UTC as PostgreSQL stores them, i.e. at most microseconds; before/after as jsonb text). The
 * first row of a month links to {@link #GENESIS} (32 zero bytes).
 *
 * <p>Ticket 130: a row with hop facts or payloads appends one more element, {@code {"hop",
 * "direction", "durationMs", "payloads": [{"ordinal", "direction", "contentType", "payloadRef",
 * "size", "sha256"}]}} (sha256 in hex), so its {@code audit.payload} rows are inside the chain, and
 * {@link #verify} also recomputes each body's SHA-256. A row without them hashes exactly as before,
 * so the chains written earlier stay valid.
 */
public final class HashChain {

  /** prevHash of the first row of every monthly chain. */
  public static final byte[] GENESIS = new byte[32];

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HexFormat HEX = HexFormat.of();

  private HashChain() {}

  /** Returns {@code row} with {@code prevHash} set and its hash computed. */
  public static AuditRow seal(AuditRow row, byte[] prevHash) {
    return row.withHashes(prevHash.clone(), hash(row, prevHash));
  }

  static byte[] hash(AuditRow r, byte[] prevHash) {
    List<Object> fields =
        new ArrayList<>(
            Arrays.asList(
                r.month(), r.seq(), text(r.auditId()), text(r.recordedAt()), text(r.at()),
                text(r.txnId()), r.correlationId(), r.actorType(), r.actorId(), r.action(),
                r.resource(), r.outcome(), r.service(), r.sourceIp(), r.payloadRef(), r.before(),
                r.after(), HEX.formatHex(prevHash)));
    if (r.hasHopFacts()) {
      Map<String, Object> hop = new LinkedHashMap<>();
      hop.put("hop", r.hop());
      hop.put("direction", r.direction());
      hop.put("durationMs", r.durationMs());
      List<Object> payloads = new ArrayList<>();
      for (PayloadRow p : r.payloads()) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("ordinal", p.ordinal());
        d.put("direction", p.direction());
        d.put("contentType", p.contentType());
        d.put("payloadRef", p.payloadRef());
        d.put("size", p.size());
        d.put("sha256", p.sha256() == null ? null : HEX.formatHex(p.sha256()));
        payloads.add(d);
      }
      hop.put("payloads", payloads);
      fields.add(hop);
    }
    return digest(JSON.writeValueAsString(fields).getBytes(StandardCharsets.UTF_8));
  }

  /** SHA-256 of {@code body}'s UTF-8 bytes; null for no body. */
  public static byte[] sha256(String body) {
    return body == null ? null : digest(body.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] digest(byte[] bytes) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
    }
  }

  private static String text(Object v) {
    return v == null ? null : v.toString();
  }

  /**
   * Checks one month's rows (ordered by seq) against its head. Fails at the first row whose hash
   * does not match its content, whose prevHash is not the previous row's hash, or whose seq is
   * missing (a deleted row; deleted last rows too, because the head still counts them).
   */
  public static ChainVerification verify(List<AuditRow> rows, ChainHead head) {
    String month = head != null ? head.month() : rows.isEmpty() ? null : rows.get(0).month();
    byte[] prev = GENESIS;
    long expected = 1;
    for (AuditRow r : rows) {
      if (r.seq() != expected) {
        return ChainVerification.broken(month, rows.size(), expected, "row missing");
      }
      if (!Arrays.equals(r.prevHash(), prev)) {
        return ChainVerification.broken(
            month, rows.size(), expected, "prevHash does not link to the previous row");
      }
      for (PayloadRow p : r.payloads()) {
        if (!Arrays.equals(p.sha256(), sha256(p.body()))) {
          return ChainVerification.broken(month, rows.size(), expected, "payload does not match its digest");
        }
      }
      if (!Arrays.equals(r.hash(), hash(r, r.prevHash()))) {
        return ChainVerification.broken(month, rows.size(), expected, "hash does not match the row");
      }
      prev = r.hash();
      expected++;
    }
    long last = expected - 1;
    if (head == null) {
      return rows.isEmpty()
          ? ChainVerification.ok(month, 0)
          : ChainVerification.broken(month, rows.size(), 1, "chain head missing");
    }
    if (head.seq() > last) {
      return ChainVerification.broken(month, rows.size(), last + 1, "row missing");
    }
    if (head.seq() < last || !Arrays.equals(head.hash(), prev)) {
      return ChainVerification.broken(
          month, rows.size(), head.seq(), "chain head does not match the last row");
    }
    return ChainVerification.ok(month, rows.size());
  }
}
