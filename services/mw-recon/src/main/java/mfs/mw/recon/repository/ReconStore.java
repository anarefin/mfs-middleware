package mfs.mw.recon.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import mfs.mw.recon.dto.ExternalLine;
import mfs.mw.recon.dto.Finding;
import mfs.mw.recon.dto.ReconClass;
import mfs.mw.recon.dto.StatementLine;
import mfs.mw.recon.dto.TxnProjection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The recon schema (ticket 126). Callers hold the transaction. */
@Repository
public class ReconStore {

  /** A registered file. */
  public record FileHead(UUID id, String source, String filename, String partnerId, String status, LocalDate businessDate) {}

  private final JdbcTemplate jdbc;

  public ReconStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Inserts the file, or returns the existing row when filename and sha256 are already registered. */
  public FileHead register(UUID id, String source, String filename, String sha256, long size, Instant received,
      String partnerId) {
    int inserted = jdbc.update("""
        insert into source_file (id, source, filename, sha256, size_bytes, received_at, status, partner_id)
        values (?, ?, ?, ?, ?, ?, 'RECEIVED', ?)
        on conflict on constraint source_file_name_sha do nothing
        """, id, source, filename, sha256, size, Timestamp.from(received), partnerId);
    if (inserted == 1) {
      return new FileHead(id, source, filename, partnerId, "RECEIVED", null);
    }
    return jdbc.queryForObject("""
        select id, source, filename, partner_id, status, business_date
        from source_file where filename = ? and sha256 = ?
        """, (rs, n) -> head(rs), filename, sha256);
  }

  public FileHead require(UUID id) {
    return jdbc.query("""
        select id, source, filename, partner_id, status, business_date from source_file where id = ?
        """, (rs, n) -> head(rs), id).stream().findFirst().orElse(null);
  }

  public void addStatementLines(UUID fileId, List<StatementLine> lines) {
    for (StatementLine line : lines) {
      jdbc.update("""
          insert into statement_line (id, file_id, line_no, value_date, amount_minor, dc_mark, reference, supplementary)
          values (?, ?, ?, ?, ?, ?, ?, ?)
          on conflict on constraint statement_line_file_line do nothing
          """, UUID.randomUUID(), fileId, line.lineNo(), line.valueDate(), line.amountMinor(), line.dcMark(),
          line.reference(), blankToNull(line.supplementary()));
    }
  }

  public void addPartnerLine(UUID fileId, String partnerId, String family, int lineNumber, ExternalLine line, String currency) {
    jdbc.update("""
        insert into partner_line
          (id, file_id, partner_id, family, line_number, partner_ref, reference, amount_minor, currency, partner_status, value_date)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        on conflict on constraint partner_line_file_line do nothing
        """, UUID.randomUUID(), fileId, partnerId, family, lineNumber, blankToNull(line.partnerRef()),
        blankToNull(line.reference()), line.amountMinor(), currency, blankToNull(line.status()), line.valueDate());
  }

  public List<ExternalLine> partnerLines(UUID fileId) {
    return jdbc.query("""
        select partner_ref, reference, amount_minor, partner_status, value_date
        from partner_line where file_id = ? order by line_number
        """, (rs, n) -> new ExternalLine(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4),
            rs.getObject(5, LocalDate.class)), fileId);
  }

  public List<ExternalLine> statementLines(UUID fileId) {
    return jdbc.query("""
        select reference, amount_minor, value_date from statement_line where file_id = ? order by line_no
        """, (rs, n) -> new ExternalLine(null, rs.getString(1), rs.getLong(2), null, rs.getObject(3, LocalDate.class)),
        fileId);
  }

  public void upsertTxn(UUID txnId, String partnerId, String partnerRef, String reference, long amount, String currency,
      String state, String txnType, Instant updatedAt, Instant completedAt) {
    jdbc.update("""
        insert into txn_view
          (txn_id, partner_id, partner_ref, reference, amount_minor, currency, state, txn_type, updated_at, completed_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        on conflict (txn_id) do update set
          partner_id = excluded.partner_id,
          partner_ref = excluded.partner_ref,
          reference = excluded.reference,
          amount_minor = excluded.amount_minor,
          currency = excluded.currency,
          state = excluded.state,
          txn_type = excluded.txn_type,
          updated_at = excluded.updated_at,
          completed_at = excluded.completed_at
        where txn_view.updated_at <= excluded.updated_at
        """, txnId, blankToNull(partnerId), blankToNull(partnerRef), blankToNull(reference), amount, currency, state,
        blankToNull(txnType), Timestamp.from(updatedAt), completedAt == null ? null : Timestamp.from(completedAt));
  }

  public void insertPosting(UUID eventId, UUID txnId, String eventType, String coreRef, Instant postedAt) {
    jdbc.update("""
        insert into ledger_view (event_id, txn_id, event_type, core_ref, posted_at)
        values (?, ?, ?, ?, ?)
        on conflict (event_id) do nothing
        """, eventId, txnId, eventType, coreRef, Timestamp.from(postedAt));
  }

  public List<TxnProjection> txnsForPartner(String partnerId, Instant from, Instant to, ZoneId zone) {
    return jdbc.query("""
        select txn_id, partner_id, partner_ref, reference, amount_minor, state, completed_at, updated_at
        from txn_view
        where partner_id = ? and coalesce(completed_at, updated_at) >= ? and coalesce(completed_at, updated_at) < ?
        """, (rs, n) -> txn(rs, zone), partnerId, Timestamp.from(from), Timestamp.from(to));
  }

  /** Completed-or-not rows in the window, for a statement that is not tied to one partner. */
  public List<TxnProjection> txnsInWindow(Instant from, Instant to, ZoneId zone) {
    return jdbc.query("""
        select txn_id, partner_id, partner_ref, reference, amount_minor, state, completed_at, updated_at
        from txn_view
        where coalesce(completed_at, updated_at) >= ? and coalesce(completed_at, updated_at) < ?
        """, (rs, n) -> txn(rs, zone), Timestamp.from(from), Timestamp.from(to));
  }

  public List<TxnProjection> txnsById(List<UUID> ids, ZoneId zone) {
    if (ids.isEmpty()) {
      return List.of();
    }
    String marks = String.join(",", ids.stream().map(id -> "?").toList());
    List<Object> args = new ArrayList<>(ids);
    return jdbc.query("""
        select txn_id, partner_id, partner_ref, reference, amount_minor, state, completed_at, updated_at
        from txn_view where txn_id in (%s)
        """.formatted(marks), (rs, n) -> txn(rs, zone), args.toArray());
  }

  public List<UUID> posted(List<UUID> txnIds) {
    if (txnIds.isEmpty()) {
      return List.of();
    }
    String marks = String.join(",", txnIds.stream().map(id -> "?").toList());
    return jdbc.query("""
        select distinct txn_id from ledger_view where txn_id in (%s)
        """.formatted(marks), (rs, n) -> rs.getObject(1, UUID.class), txnIds.toArray());
  }

  public void clearResults(UUID fileId) {
    jdbc.update("delete from recon_exception where file_id = ?", fileId);
    jdbc.update("delete from match_result where run_id in (select id from recon_run where file_id = ?)", fileId);
  }

  public void insertRun(UUID runId, UUID fileId, String partnerId, String source, Instant started, Instant finished, LocalDate businessDate) {
    jdbc.update("""
        insert into recon_run (id, file_id, partner_id, source, started_at, finished_at) values (?, ?, ?, ?, ?, ?)
        """, runId, fileId, partnerId, source, Timestamp.from(started), Timestamp.from(finished));
    jdbc.update("update source_file set status = 'MATCHED', business_date = ? where id = ?", businessDate, fileId);
  }

  public void insertFinding(UUID id, UUID runId, UUID fileId, Finding finding, Instant at) {
    UUID txnId = uuidOrNull(finding.txnId());
    jdbc.update("""
        insert into match_result (id, run_id, class, detail, txn_id, file_amount_minor, upay_amount_minor, ledger_amount_minor)
        values (?, ?, ?, ?, ?, ?, ?, ?)
        """, UUID.randomUUID(), runId, finding.classification().name(), finding.detail(), txnId,
        finding.fileAmountMinor(), finding.upayAmountMinor(), finding.ledgerAmountMinor());
    if (finding.classification() != ReconClass.MATCHED) {
      jdbc.update("""
          insert into recon_exception
            (id, run_id, file_id, class, detail, owner, txn_id, partner_ref, reference,
             file_amount_minor, upay_amount_minor, ledger_amount_minor, state, created_at)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?)
          """, id, runId, fileId, finding.classification().name(), finding.detail(), finding.owner(), txnId,
          finding.partnerRef(), finding.reference(), finding.fileAmountMinor(), finding.upayAmountMinor(),
          finding.ledgerAmountMinor(), Timestamp.from(at));
    }
  }

  public int runs(UUID fileId) {
    Integer n = jdbc.queryForObject("select count(*) from recon_run where file_id = ?", Integer.class, fileId);
    return n == null ? 0 : n;
  }

  public int exceptions(UUID fileId) {
    Integer n = jdbc.queryForObject("select count(*) from recon_exception where file_id = ?", Integer.class, fileId);
    return n == null ? 0 : n;
  }

  public int partnerLineCount(UUID fileId) {
    Integer n = jdbc.queryForObject("select count(*) from partner_line where file_id = ?", Integer.class, fileId);
    return n == null ? 0 : n;
  }


  private static FileHead head(ResultSet rs) throws SQLException {
    return new FileHead(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
        rs.getString(5), rs.getObject(6, LocalDate.class));
  }

  private static TxnProjection txn(ResultSet rs, ZoneId zone) throws SQLException {
    Instant completed = rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant();
    Instant updated = rs.getTimestamp(8).toInstant();
    Instant when = completed != null ? completed : updated;
    return new TxnProjection(rs.getObject(1, UUID.class).toString(), rs.getString(2), rs.getString(3), rs.getString(4),
        rs.getLong(5), rs.getString(6), when.atZone(zone).toLocalDate());
  }

  private static UUID uuidOrNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
