package mfs.mw.data.export.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import mfs.mw.data.export.dto.NotifyStatusEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The daily-batch facts (ticket 128). Party columns hold tokens, never a clear MSISDN. A later
 * status of the same transaction updates the row and leaves {@code business_date} as it was on the
 * first write (the day of {@code requestedAt} in Asia/Dhaka).
 */
@Repository
public class DwFactStore {

  private final JdbcTemplate jdbc;

  public DwFactStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Inserts or updates the transaction. {@code businessDate} is applied only on the insert. */
  public void upsertTxn(String txnId, String type, String status, long amountMinor, String currency, String channel,
      String payerToken, String payeeToken, Instant createdAt, Instant completedAt, LocalDate businessDate) {
    jdbc.update(
        """
        insert into txn_fact (txn_id, txn_type, status, amount_minor, currency, channel,
          payer_token, payee_token, created_at, completed_at, business_date)
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        on conflict (txn_id) do update set
          txn_type = excluded.txn_type,
          status = excluded.status,
          amount_minor = excluded.amount_minor,
          currency = excluded.currency,
          channel = excluded.channel,
          payer_token = excluded.payer_token,
          payee_token = excluded.payee_token,
          completed_at = excluded.completed_at
        """,
        txnId, type, status, amountMinor, currency, channel, payerToken, payeeToken,
        at(createdAt), completedAt == null ? null : at(completedAt), businessDate);
  }

  public void upsertNotification(NotifyStatusEvent event, String recipientToken, LocalDate businessDate) {
    jdbc.update(
        """
        insert into notification_fact (notification_id, channel, template, status, recipient_token,
          sent_at, business_date)
        values (?, ?, ?, ?, ?, ?, ?)
        on conflict (notification_id) do update set
          status = excluded.status,
          recipient_token = excluded.recipient_token
        """,
        event.messageId(), event.channel(), event.templateCode() == null ? "" : event.templateCode(),
        event.status(), recipientToken == null ? "" : recipientToken, at(event.at()), businessDate);
  }

  public void upsertRecon(String exceptionId, String exceptionClass, String partner, String txnId, long amountMinor,
      String statementRef, Instant detectedAt, LocalDate businessDate) {
    jdbc.update(
        """
        insert into recon_fact (exception_id, exception_class, partner, txn_id, amount_minor,
          statement_ref, detected_at, business_date)
        values (?, ?, ?, ?, ?, ?, ?, ?)
        on conflict (exception_id) do update set
          exception_class = excluded.exception_class,
          partner = excluded.partner,
          amount_minor = excluded.amount_minor,
          statement_ref = excluded.statement_ref
        """,
        exceptionId, exceptionClass, partner, txnId, amountMinor, statementRef, at(detectedAt), businessDate);
  }

  public List<Map<String, Object>> transactions(LocalDate date) {
    return jdbc.queryForList(
        """
        select txn_id, txn_type, status, amount_minor, currency, channel, payer_token, payee_token,
          created_at, completed_at
        from txn_fact where business_date = ? order by txn_id
        """,
        date);
  }

  public List<Map<String, Object>> notifications(LocalDate date) {
    return jdbc.queryForList(
        """
        select notification_id, channel, template, status, recipient_token, sent_at
        from notification_fact where business_date = ? order by notification_id
        """,
        date);
  }

  public List<Map<String, Object>> recon(LocalDate date) {
    return jdbc.queryForList(
        """
        select exception_id, exception_class, partner, txn_id, amount_minor, statement_ref, detected_at
        from recon_fact where business_date = ? order by exception_id
        """,
        date);
  }

  public List<LocalDate> transactionDaysBefore(LocalDate cutoff) {
    return days("txn_fact", cutoff);
  }

  public List<LocalDate> notificationDaysBefore(LocalDate cutoff) {
    return days("notification_fact", cutoff);
  }

  public List<LocalDate> reconDaysBefore(LocalDate cutoff) {
    return days("recon_fact", cutoff);
  }

  public int deleteTransactions(LocalDate date) {
    return jdbc.update("delete from txn_fact where business_date = ?", date);
  }

  public int deleteNotifications(LocalDate date) {
    return jdbc.update("delete from notification_fact where business_date = ?", date);
  }

  public int deleteRecon(LocalDate date) {
    return jdbc.update("delete from recon_fact where business_date = ?", date);
  }

  private List<LocalDate> days(String table, LocalDate cutoff) {
    return jdbc.query(
        "select distinct business_date from " + table + " where business_date < ? order by business_date",
        (rs, n) -> rs.getObject(1, LocalDate.class), cutoff);
  }

  private static OffsetDateTime at(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }
}
