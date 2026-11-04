package mfs.mw.orchestrator.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.orchestrator.dto.EncryptedPin;
import mfs.mw.orchestrator.dto.ErrorInfo;
import mfs.mw.orchestrator.dto.TxnRequest;
import mfs.mw.orchestrator.entity.TransactionRecord;
import mfs.mw.orchestrator.service.saga.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** {@code txn.transaction} and its step history. */
@Repository
public class TransactionRepository {

  private static final String COLUMNS =
      "txn_id, created_at, updated_at, status, previous_status, reply_to, fee_minor, core_ref,"
          + " error_code, error_message, error_message_bn, error_retryable, encrypted_pin::text,"
          + " request::text";

  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  public TransactionRepository(JdbcTemplate jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public void insert(TransactionRecord rec) {
    TxnRequest r = rec.request();
    jdbc.update(
        "insert into transaction (txn_id, created_at, updated_at, requested_at, channel, txn_type,"
            + " status, previous_status, initiator_id, correlation_id, idempotency_key, reply_to,"
            + " amount_minor, currency, encrypted_pin, request)"
            + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)",
        rec.txnId(),
        Timestamp.from(rec.createdAt()),
        Timestamp.from(rec.updatedAt()),
        Timestamp.from(r.requestedAt()),
        r.channel(),
        r.txnType(),
        rec.status().name(),
        null,
        r.initiator().id(),
        r.correlationId(),
        r.idempotencyKey(),
        rec.replyTo(),
        r.amount().valueMinor(),
        r.amount().currency(),
        rec.encryptedPin() == null ? null : json.writeValueAsString(rec.encryptedPin()),
        json.writeValueAsString(r));
  }

  /** Persists the mutable columns of a moved record and bumps the version. */
  public void update(TransactionRecord rec) {
    jdbc.update(
        "update transaction set updated_at = ?, status = ?, previous_status = ?, fee_minor = ?,"
            + " core_ref = ?, error_code = ?, error_message = ?, error_message_bn = ?,"
            + " error_retryable = ?, encrypted_pin = ?::jsonb, version = version + 1"
            + " where txn_id = ?",
        Timestamp.from(rec.updatedAt()),
        rec.status().name(),
        rec.previousStatus() == null ? null : rec.previousStatus().name(),
        rec.feeMinor(),
        rec.coreRef(),
        rec.error() == null ? null : rec.error().code(),
        rec.error() == null ? null : rec.error().message(),
        rec.error() == null ? null : rec.error().messageBn(),
        rec.error() == null ? null : rec.error().retryable(),
        rec.encryptedPin() == null ? null : json.writeValueAsString(rec.encryptedPin()),
        rec.txnId());
  }

  public Optional<TransactionRecord> find(UUID txnId) {
    return jdbc
        .query("select " + COLUMNS + " from transaction where txn_id = ?", this::map, txnId)
        .stream()
        .findFirst();
  }

  /** Loads and row-locks the transaction, so concurrent events and timers serialise per txn. */
  public Optional<TransactionRecord> findForUpdate(UUID txnId) {
    return jdbc
        .query("select " + COLUMNS + " from transaction where txn_id = ? for update", this::map, txnId)
        .stream()
        .findFirst();
  }

  public void recordStep(UUID txnId, Status from, Status to, String trigger, Instant at) {
    jdbc.update(
        "insert into saga_step (txn_id, from_status, to_status, trigger, at) values (?, ?, ?, ?, ?)",
        txnId,
        from.name(),
        to.name(),
        trigger,
        Timestamp.from(at));
  }

  private TransactionRecord map(ResultSet rs, int row) throws SQLException {
    String code = rs.getString("error_code");
    ErrorInfo error =
        code == null
            ? null
            : new ErrorInfo(
                code, rs.getString("error_message"), rs.getString("error_message_bn"),
                rs.getBoolean("error_retryable"));
    String pin = rs.getString("encrypted_pin");
    String prev = rs.getString("previous_status");
    Long fee = rs.getObject("fee_minor", Long.class);
    return new TransactionRecord(
        rs.getObject("txn_id", UUID.class),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("updated_at").toInstant(),
        json.readValue(rs.getString("request"), TxnRequest.class),
        Status.valueOf(rs.getString("status")),
        prev == null ? null : Status.valueOf(prev),
        rs.getString("reply_to"),
        fee,
        rs.getString("core_ref"),
        error,
        pin == null ? null : json.readValue(pin, EncryptedPin.class));
  }
}
