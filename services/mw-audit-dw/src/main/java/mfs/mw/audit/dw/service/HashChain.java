package mfs.mw.audit.dw.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import mfs.mw.audit.dw.entity.AuditRow;
import mfs.mw.audit.dw.entity.ChainHead;
import tools.jackson.databind.json.JsonMapper;

/**
 * The hash chain of spec 14.3, one per monthly partition: {@code hash = SHA-256(canonical row)},
 * where the canonical row is the JSON array {@code [month, seq, auditId, recordedAt, at, txnId,
 * correlationId, actorType, actorId, action, resource, outcome, service, sourceIp, payloadRef,
 * before, after, prevHashHex]} (UTF-8, no spaces, null for absent values, instants as ISO-8601
 * UTC as PostgreSQL stores them, i.e. at most microseconds; before/after as jsonb text). The
 * first row of a month links to {@link #GENESIS} (32 zero bytes).
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
        Arrays.asList(
            r.month(), r.seq(), text(r.auditId()), text(r.recordedAt()), text(r.at()),
            text(r.txnId()), r.correlationId(), r.actorType(), r.actorId(), r.action(),
            r.resource(), r.outcome(), r.service(), r.sourceIp(), r.payloadRef(), r.before(),
            r.after(), HEX.formatHex(prevHash));
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(JSON.writeValueAsString(fields).getBytes(StandardCharsets.UTF_8));
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
