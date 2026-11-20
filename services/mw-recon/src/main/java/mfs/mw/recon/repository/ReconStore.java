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

  /** One finding of a run, for the reports (ticket 127): its class and amount in poisha. */
  public record FindingRow(String reconClass, long amountMinor) {}

  /** One work-queue exception (ticket 127). */
  public record ExceptionRow(UUID id, String reconClass, String detail, String owner, String partnerId, UUID txnId,
      String partnerRef, String reference, Long fileAmountMinor, Long upayAmountMinor, Long ledgerAmountMinor,
      String state, Instant createdAt, String explanation, String explainedBy, Instant explainedAt, String action,
      UUID changeRequestId, String actionedBy) {}

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
             file_amount_minor, upay_amount_minor, ledger_amount_minor, state, created_at, partner_id)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, (select partner_id from recon_run where id = ?))
          """, id, runId, fileId, finding.classification().name(), finding.detail(), finding.owner(), txnId,
          finding.partnerRef(), finding.reference(), finding.fileAmountMinor(), finding.upayAmountMinor(),
          finding.ledgerAmountMinor(), Timestamp.from(at), runId);
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

  /** The findings of the runs of one source for a partner whose file's business date is in [from, to]. */
  public List<FindingRow> findingsFor(String partnerId, LocalDate from, LocalDate to, String source) {
    return jdbc.query("""
        select m.class, coalesce(m.file_amount_minor, m.upay_amount_minor, m.ledger_amount_minor, 0)
        from match_result m
        join recon_run r on r.id = m.run_id
        join source_file f on f.id = r.file_id
        where r.partner_id = ? and r.source = ? and f.business_date between ? and ?
        """, (rs, n) -> new FindingRow(rs.getString(1), rs.getLong(2)), partnerId, source, from, to);
  }

  /** Partners with a run on that business date, for the scheduled daily report. */
  public List<String> partnersOn(LocalDate day) {
    return jdbc.queryForList("""
        select distinct r.partner_id from recon_run r join source_file f on f.id = r.file_id
        where f.business_date = ? order by 1
        """, String.class, day);
  }

  public void saveReport(String partnerId, String kind, LocalDate from, LocalDate to, String format, byte[] content,
      Instant at) {
    jdbc.update("""
        insert into report (id, partner_id, kind, from_date, to_date, format, content, created_at)
        values (?, ?, ?, ?, ?, ?, ?, ?)
        on conflict on constraint report_partner_day do update
          set content = excluded.content, created_at = excluded.created_at
        """, UUID.randomUUID(), partnerId, kind, from, to, format, content, Timestamp.from(at));
  }

  public byte[] storedReport(String partnerId, String kind, LocalDate from, LocalDate to, String format) {
    return jdbc.query("""
        select content from report where partner_id = ? and kind = ? and from_date = ? and to_date = ? and format = ?
        """, (rs, n) -> rs.getBytes(1), partnerId, kind, from, to, format).stream().findFirst().orElse(null);
  }

  /** The work queue, oldest first; every filter is optional. {@code olderThan} keeps rows created before it. */
  public List<ExceptionRow> exceptionQueue(String reconClass, String partnerId, String state, Instant olderThan,
      int limit) {
    StringBuilder sql = new StringBuilder(EXCEPTION_COLUMNS).append(" where 1 = 1");
    List<Object> args = new ArrayList<>();
    if (reconClass != null) {
      sql.append(" and class = ?");
      args.add(reconClass);
    }
    if (partnerId != null) {
      sql.append(" and partner_id = ?");
      args.add(partnerId);
    }
    if (state != null) {
      sql.append(" and state = ?");
      args.add(state);
    }
    if (olderThan != null) {
      sql.append(" and created_at < ?");
      args.add(Timestamp.from(olderThan));
    }
    sql.append(" order by created_at, id limit ?");
    args.add(limit);
    return jdbc.query(sql.toString(), (rs, n) -> exception(rs), args.toArray());
  }

  public ExceptionRow exception(UUID id) {
    return jdbc.query(EXCEPTION_COLUMNS + " where id = ?", (rs, n) -> exception(rs), id).stream().findFirst()
        .orElse(null);
  }

  /**
   * Records the explanation while the exception is OPEN or EXPLAINED (OPEN becomes EXPLAINED; FRS 10.4 #7). An
   * ACTIONED or RESOLVED exception is never rewritten: 0 rows.
   */
  public int explain(UUID id, String explanation, String operatorId, Instant at) {
    return jdbc.update("""
        update recon_exception set explanation = ?, explained_by = ?, explained_at = ?, state = 'EXPLAINED'
        where id = ? and state in ('OPEN', 'EXPLAINED')
        """, explanation, operatorId, Timestamp.from(at), id);
  }

  /**
   * Records an approved action (ticket 127b) while the exception is OPEN or EXPLAINED and has a transaction: it
   * becomes ACTIONED and waits for {@code expectedStatus} of its transaction. 0 rows otherwise.
   */
  public int markActioned(UUID id, String action, UUID changeRequestId, String actor, String expectedStatus,
      Instant at) {
    return jdbc.update("""
        update recon_exception set state = 'ACTIONED', action = ?, change_request_id = ?, actioned_by = ?,
          actioned_at = ?, expected_status = ?
        where id = ? and state in ('OPEN', 'EXPLAINED') and txn_id is not null
        """, action, changeRequestId, actor, Timestamp.from(at), expectedStatus, id);
  }

  /** The ACTIONED exceptions of a transaction waiting for that status. */
  public List<UUID> actionedAwaiting(UUID txnId, String status) {
    return jdbc.queryForList("""
        select id from recon_exception where state = 'ACTIONED' and txn_id = ? and expected_status = ? order by id
        """, UUID.class, txnId, status);
  }

  /**
   * The ACTIONED exceptions of a transaction whose action failed: the transaction reached a final status other than
   * the one the action leads to (REC-05, FRS 10.4 #7).
   */
  public List<UUID> actionedFailed(UUID txnId, String status) {
    return jdbc.queryForList("""
        select id from recon_exception where state = 'ACTIONED' and txn_id = ? and expected_status <> ? order by id
        """, UUID.class, txnId, status);
  }

  /**
   * ACTIONED -> OPEN after a failed action, so it can be explained or actioned again; the last action and its change
   * request stay on the row as history. 0 rows when it is not ACTIONED.
   */
  public int reopen(UUID id) {
    return jdbc.update("""
        update recon_exception set state = 'OPEN', expected_status = null where id = ? and state = 'ACTIONED'
        """, id);
  }

  /** The projected {@code updatedAt} of a transaction, or null. */
  public Instant txnUpdatedAt(UUID txnId) {
    return jdbc.query("select updated_at from txn_view where txn_id = ?", (rs, n) -> rs.getTimestamp(1).toInstant(),
        txnId).stream().findFirst().orElse(null);
  }

  /** ACTIONED -> RESOLVED; 0 rows when it is not ACTIONED. */
  public int resolve(UUID id, Instant at) {
    return jdbc.update("""
        update recon_exception set state = 'RESOLVED', resolved_at = ? where id = ? and state = 'ACTIONED'
        """, Timestamp.from(at), id);
  }

  /** The projected status of a transaction, or null. */
  public String txnState(UUID txnId) {
    return jdbc.query("select state from txn_view where txn_id = ?", (rs, n) -> rs.getString(1), txnId).stream()
        .findFirst().orElse(null);
  }

  /** A run-less exception (the trust position, REC-07). */
  public void insertStandaloneException(UUID id, String reconClass, String detail, String owner, String partnerId,
      Long fileAmountMinor, Long upayAmountMinor, Instant at) {
    jdbc.update("""
        insert into recon_exception
          (id, class, detail, owner, partner_id, file_amount_minor, upay_amount_minor, state, created_at)
        values (?, ?, ?, ?, ?, ?, ?, 'OPEN', ?)
        """, id, reconClass, detail, owner, partnerId, fileAmountMinor, upayAmountMinor, Timestamp.from(at));
  }

  public void setClosingBalance(UUID fileId, long closingMinor) {
    jdbc.update("update source_file set closing_balance_minor = ? where id = ?", closingMinor, fileId);
  }

  /** The closing balance of the latest statement of that source for the business date, or null. */
  public Long trustClosing(LocalDate day, String source) {
    return jdbc.query("""
        select closing_balance_minor from source_file
        where source = ? and business_date = ? and closing_balance_minor is not null
        order by received_at desc limit 1
        """, (rs, n) -> rs.getLong(1), source, day).stream().findFirst().orElse(null);
  }

  /** The latest business date of {@code source} with a closing balance, or null (ticket 127c). */
  public LocalDate latestTrustClosingDate(String source) {
    return jdbc.queryForObject(
        "select max(business_date) from source_file where source = ? and closing_balance_minor is not null",
        (rs, n) -> rs.getObject(1, LocalDate.class), source);
  }

  public void saveTrustPosition(LocalDate day, long emoney, long trust, long difference, UUID exceptionId, Instant at) {
    jdbc.update("""
        insert into trust_position
          (business_date, emoney_total_minor, trust_closing_minor, difference_minor, exception_id, computed_at)
        values (?, ?, ?, ?, ?, ?)
        on conflict (business_date) do update set emoney_total_minor = excluded.emoney_total_minor,
          trust_closing_minor = excluded.trust_closing_minor, difference_minor = excluded.difference_minor,
          exception_id = excluded.exception_id, computed_at = excluded.computed_at
        """, day, emoney, trust, difference, exceptionId, Timestamp.from(at));
  }

  private static final String EXCEPTION_COLUMNS = """
      select id, class, detail, owner, partner_id, txn_id, partner_ref, reference, file_amount_minor,
        upay_amount_minor, ledger_amount_minor, state, created_at, explanation, explained_by, explained_at,
        action, change_request_id, actioned_by
      from recon_exception""";

  private static ExceptionRow exception(ResultSet rs) throws SQLException {
    return new ExceptionRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
        rs.getString(5), rs.getObject(6, UUID.class), rs.getString(7), rs.getString(8), rs.getObject(9, Long.class),
        rs.getObject(10, Long.class), rs.getObject(11, Long.class), rs.getString(12), rs.getTimestamp(13).toInstant(),
        rs.getString(14), rs.getString(15), rs.getTimestamp(16) == null ? null : rs.getTimestamp(16).toInstant(),
        rs.getString(17), rs.getObject(18, UUID.class), rs.getString(19));
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
