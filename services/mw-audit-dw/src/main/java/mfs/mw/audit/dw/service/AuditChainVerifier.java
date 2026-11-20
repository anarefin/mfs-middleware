package mfs.mw.audit.dw.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.audit.dw.dto.AuditAnchor;
import mfs.mw.audit.dw.entity.AnchorRow;
import mfs.mw.audit.dw.entity.AuditRow;
import mfs.mw.audit.dw.entity.ChainHead;
import mfs.mw.audit.dw.entity.PayloadRow;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Recomputes one month's hash chain from {@code audit.event} and checks it against
 * {@code audit.chain_head} (spec 14.3). The service connects as {@code audit_writer}, which may
 * only INSERT into {@code audit.event} and cannot read it, so a verifier is given a reading
 * role's {@link JdbcTemplate}: the read-only {@code audit_reader} for the scheduled check
 * ({@code config/AuditReaderConfig}, ticket 50), or the owner or an auditor's account.
 */
public class AuditChainVerifier {

  private static final HexFormat HEX = HexFormat.of();

  private final JdbcTemplate jdbc;

  public AuditChainVerifier(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Every month that has a chain ({@code yyyy-MM}, oldest first). */
  public List<String> months() {
    return jdbc.queryForList("select month from chain_head order by month", String.class);
  }

  /** @param month {@code yyyy-MM} */
  public ChainVerification verify(String month) {
    List<ChainHead> head =
        jdbc.query(
            "select month, seq, hash from chain_head where month = ?",
            (rs, i) -> new ChainHead(rs.getString("month"), rs.getLong("seq"), rs.getBytes("hash")),
            month);
    return HashChain.verify(rows(month), head.isEmpty() ? null : head.get(0));
  }

  /** Every row of one month's chain, in chain order, with its payload rows (ticket 130). */
  public List<AuditRow> rows(String month) {
    Map<UUID, List<PayloadRow>> payloads = new HashMap<>();
    jdbc.query(
        """
        select audit_id, ordinal, direction, content_type, body, payload_ref, size, sha256
          from payload where month = ? order by audit_id, ordinal
        """,
        rs -> {
          payloads
              .computeIfAbsent(rs.getObject("audit_id", UUID.class), k -> new ArrayList<>())
              .add(
                  new PayloadRow(
                      rs.getInt("ordinal"),
                      rs.getString("direction"),
                      rs.getString("content_type"),
                      rs.getString("body"),
                      rs.getString("payload_ref"),
                      (Integer) rs.getObject("size"),
                      rs.getBytes("sha256")));
        },
        month);
    return rawRows(month).stream()
        .map(r -> r.withHop(r.hop(), r.direction(), r.durationMs(), payloads.getOrDefault(r.auditId(), List.of())))
        .toList();
  }

  private List<AuditRow> rawRows(String month) {
    return jdbc.query(
            """
            select month, seq, audit_id, recorded_at, at, txn_id, correlation_id, actor_type,
                   actor_id, action, resource, outcome, service, source_ip, payload_ref,
                   before::text before, after::text after, prev_hash, hash, hop, direction, duration_ms
              from event where month = ? order by seq
            """,
            (rs, i) ->
                new AuditRow(
                    rs.getString("month"),
                    rs.getLong("seq"),
                    rs.getObject("audit_id", UUID.class),
                    rs.getObject("recorded_at", OffsetDateTime.class).toInstant(),
                    rs.getObject("at", OffsetDateTime.class).toInstant(),
                    rs.getObject("txn_id", UUID.class),
                    rs.getString("correlation_id"),
                    rs.getString("actor_type"),
                    rs.getString("actor_id"),
                    rs.getString("action"),
                    rs.getString("resource"),
                    rs.getString("outcome"),
                    rs.getString("service"),
                    rs.getString("source_ip"),
                    rs.getString("payload_ref"),
                    rs.getString("before"),
                    rs.getString("after"),
                    rs.getBytes("prev_hash"),
                    rs.getBytes("hash"),
                    rs.getString("hop"),
                    rs.getString("direction"),
                    (Long) rs.getObject("duration_ms"),
                    List.of()),
            month);
  }

  /**
   * The anchor heads of {@code [from, to)} (ticket 74): for every chain that received rows in that
   * window, its last row of the window ({@code rowCount} = that row's seq), by partition.
   */
  public List<AuditAnchor.Head> heads(Instant from, Instant to) {
    return jdbc.query(
        """
        select e.month, e.seq, e.hash
          from event e
          join (select month, max(seq) seq from event
                 where recorded_at >= ? and recorded_at < ? group by month) d
            on e.month = d.month and e.seq = d.seq
         order by e.month
        """,
        (rs, i) -> new AuditAnchor.Head(rs.getString(1), HEX.formatHex(rs.getBytes(3)), rs.getLong(2)),
        utc(from),
        utc(to));
  }

  /** Every stored anchor, oldest first. */
  public List<AnchorRow> anchors() {
    return jdbc.query(
        "select anchor_date, jws, kid, anchor_hash from anchor order by anchor_date",
        (rs, i) ->
            new AnchorRow(
                rs.getObject("anchor_date", LocalDate.class),
                rs.getString("jws"),
                rs.getString("kid"),
                rs.getBytes("anchor_hash")));
  }

  private static OffsetDateTime utc(Instant t) {
    return t.atOffset(ZoneOffset.UTC);
  }
}
