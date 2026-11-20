package mfs.mw.orchestrator.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import mfs.mw.orchestrator.dto.ErrorInfo;
import mfs.mw.orchestrator.dto.TxnDetail;
import mfs.mw.orchestrator.dto.TxnRequest;
import mfs.mw.orchestrator.dto.TxnSearchCriteria;
import mfs.mw.orchestrator.entity.TransactionRecord;
import mfs.mw.orchestrator.service.saga.PendingLeg;
import mfs.mw.orchestrator.service.saga.Status;
import mfs.mw.orchestrator.util.SearchHash;
import mfs.mw.orchestrator.util.SearchKeys;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** {@code txn.transaction} and its step history. */
@Repository
public class TransactionRepository {

  private static final String COLUMNS =
      "txn_id, created_at, updated_at, status, previous_status, reply_to, fee_minor, core_ref,"
          + " error_code, error_message, error_message_bn, error_retryable,"
          + " request::text, hold_id, partner_ref, reversal_txn_id, pending_leg";

  /** {@link #COLUMNS} of the search's {@code transaction t}. */
  private static final String T_COLUMNS =
      Arrays.stream(COLUMNS.split(", ")).map(c -> "t." + c).collect(Collectors.joining(", "));

  private final JdbcTemplate jdbc;
  private final JsonMapper json;
  private final SearchHash searchHash;

  public TransactionRepository(JdbcTemplate jdbc, JsonMapper json, SearchHash searchHash) {
    this.jdbc = jdbc;
    this.json = json;
    this.searchHash = searchHash;
  }

  /**
   * Inserts a new transaction with the keyed hashes of its typed initiator id and of its payee
   * (ticket 35 search, A2/A3), and its wallet parties for the wallet history (ADR D25), in the
   * caller's transaction.
   */
  public void insert(TransactionRecord rec) {
    TxnRequest r = rec.request();
    jdbc.update(
        "insert into transaction (txn_id, created_at, updated_at, requested_at, channel, txn_type,"
            + " status, previous_status, initiator_id, initiator_type, initiator_hash, payee_hash,"
            + " correlation_id, idempotency_key, reply_to, amount_minor, currency, request)"
            + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
        rec.txnId(),
        Timestamp.from(rec.createdAt()),
        Timestamp.from(rec.updatedAt()),
        Timestamp.from(r.requestedAt()),
        r.channel(),
        r.txnType(),
        rec.status().name(),
        null,
        r.initiator().id(),
        r.initiator().type(),
        SearchKeys.initiator(r).map(searchHash::of).orElse(null),
        SearchKeys.payee(r).map(searchHash::of).orElse(null),
        r.correlationId(),
        r.idempotencyKey(),
        rec.replyTo(),
        r.amount().valueMinor(),
        r.amount().currency(),
        json.writeValueAsString(r));
    insertWalletParties(rec.txnId(), rec.createdAt(), r);
  }

  /**
   * The {@code wallet_txn} rows of one transaction ({@link SearchKeys#walletParties}); a row that
   * is already there is left alone. Also used by the V13 backfill ({@code WalletPartyMigration}).
   */
  public void insertWalletParties(UUID txnId, Instant createdAt, TxnRequest r) {
    for (SearchKeys.WalletParty p : SearchKeys.walletParties(r)) {
      jdbc.update(
          "insert into wallet_txn (wallet_hash, txn_id, direction, created_at) values (?, ?, ?, ?)"
              + " on conflict do nothing",
          searchHash.of(p.key()), txnId, p.direction(), Timestamp.from(createdAt));
    }
  }

  /** Stores the partner routing chose. The idempotency hash is not recomputed (ticket 98). */
  public void updateRequest(UUID txnId, TxnRequest request) {
    jdbc.update(
        "update transaction set request = ?::jsonb, version = version + 1 where txn_id = ?",
        json.writeValueAsString(request),
        txnId);
  }

  /** Persists the mutable columns of a moved record and bumps the version. */
  public void update(TransactionRecord rec) {
    jdbc.update(
        "update transaction set updated_at = ?, status = ?, previous_status = ?, fee_minor = ?,"
            + " core_ref = ?, error_code = ?, error_message = ?, error_message_bn = ?,"
            + " error_retryable = ?, hold_id = ?, partner_ref = ?,"
            + " reversal_txn_id = ?, pending_leg = ?, version = version + 1"
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
        rec.holdId(),
        rec.partnerRef(),
        rec.reversalTxnId(),
        rec.pendingLeg() == null ? null : rec.pendingLeg().name(),
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

  /**
   * Loads and row-locks the transaction that the REVERSAL request {@code reversalTxnId}
   * compensates: the core answers ReverseTransaction under the reversal's own txnId.
   */
  public Optional<TransactionRecord> findForUpdateByReversal(UUID reversalTxnId) {
    return jdbc
        .query(
            "select " + COLUMNS + " from transaction where reversal_txn_id = ? for update",
            this::map,
            reversalTxnId)
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

  /** How many steps of the transaction's history {@code trigger} caused. */
  public int countSteps(UUID txnId, String trigger) {
    Integer n =
        jdbc.queryForObject(
            "select count(*) from saga_step where txn_id = ? and trigger = ?", Integer.class, txnId, trigger);
    return n == null ? 0 : n;
  }

  /** When the transaction last entered {@code status} (from the step history), if it did. */
  public Optional<Instant> enteredAt(UUID txnId, Status status) {
    return jdbc
        .query(
            "select at from saga_step where txn_id = ? and to_status = ? order by id desc limit 1",
            (rs, i) -> rs.getTimestamp("at").toInstant(),
            txnId,
            status.name())
        .stream()
        .findFirst();
  }

  /**
   * The earliest {@code updated_at} among transactions in {@code status}: when the one waiting
   * longest entered it (a state change sets {@code updated_at}); empty when none is in it.
   */
  public Optional<Instant> oldestUpdateInStatus(Status status) {
    java.sql.Timestamp t =
        jdbc.queryForObject("select min(updated_at) from transaction where status = ?", java.sql.Timestamp.class, status.name());
    return Optional.ofNullable(t).map(java.sql.Timestamp::toInstant);
  }

  /** How many transactions are in {@code status} now (backlog gauges, spec 21). */
  public long countByStatus(Status status) {
    Long n = jdbc.queryForObject("select count(*) from transaction where status = ?", Long.class, status.name());
    return n == null ? 0 : n;
  }

  /**
   * Internal search (ticket 35), newest first. MSISDN and org id are matched through the typed
   * {@code initiator_hash} and the initiator type, never through {@code initiator_id} (A2); with
   * an org id, also the transactions paying one of {@code orgPayees} through {@code payee_hash}
   * (A3). {@code parentTxnId} through the stored request's attributes (bulk children, ticket 34).
   * With a wallet id, the transactions of that wallet's {@code wallet_txn} rows (ADR D25): the
   * ones it paid in any status, the ones paying it only in one of {@code walletInStatuses}.
   */
  public List<TransactionRecord> search(
      TxnSearchCriteria c, List<SearchKeys.Key> orgPayees, List<String> walletInStatuses) {
    StringBuilder sql = new StringBuilder("select " + T_COLUMNS + " from transaction t");
    List<Object> args = new ArrayList<>();
    if (c.walletId() != null) {
      sql.append(" join wallet_txn w on w.txn_id = t.txn_id and w.wallet_hash = ?");
      args.add(searchHash.of(SearchKeys.WALLET, c.walletId()));
      if (c.direction() != null) {
        sql.append(" and w.direction = ?");
        args.add(c.direction());
      }
      sql.append(" and (w.direction = '").append(SearchKeys.OUT).append("'");
      if (!walletInStatuses.isEmpty()) {
        sql.append(" or t.status in (").append(placeholders(walletInStatuses.size())).append(")");
        args.addAll(walletInStatuses);
      }
      sql.append(")");
    }
    sql.append(" where true");
    if (c.txnId() != null) {
      sql.append(" and t.txn_id = ?");
      args.add(c.txnId());
    }
    if (c.msisdn() != null) {
      String marks = placeholders(SearchKeys.MSISDN_TYPES.size());
      sql.append(" and t.initiator_type in (").append(marks).append(") and t.initiator_hash in (").append(marks).append(")");
      args.addAll(SearchKeys.MSISDN_TYPES);
      SearchKeys.MSISDN_TYPES.forEach(type -> args.add(searchHash.of(type, c.msisdn())));
    }
    if (c.orgId() != null) {
      sql.append(" and ((t.initiator_type = ? and t.initiator_hash = ?)");
      args.add(SearchKeys.ORG);
      args.add(searchHash.of(SearchKeys.ORG, c.orgId()));
      if (!orgPayees.isEmpty()) {
        sql.append(" or t.payee_hash in (").append(placeholders(orgPayees.size())).append(")");
        orgPayees.forEach(p -> args.add(searchHash.of(p)));
      }
      sql.append(")");
    }
    if (c.partnerRef() != null) {
      sql.append(" and t.partner_ref = ?");
      args.add(c.partnerRef());
    }
    if (c.status() != null) {
      sql.append(" and t.status = ?");
      args.add(c.status());
    }
    if (c.txnType() != null) {
      sql.append(" and t.txn_type = ?");
      args.add(c.txnType());
    }
    if (c.parentTxnId() != null) {
      sql.append(" and t.request -> 'attributes' ->> 'parentTxnId' = ?");
      args.add(c.parentTxnId());
    }
    if (c.from() != null) {
      sql.append(" and t.created_at >= ?");
      args.add(Timestamp.from(c.from()));
    }
    if (c.to() != null) {
      sql.append(" and t.created_at < ?");
      args.add(Timestamp.from(c.to()));
    }
    sql.append(" order by t.created_at desc, t.txn_id desc limit ? offset ?");
    args.add(c.limit());
    args.add(c.offset());
    return jdbc.query(sql.toString(), this::map, args.toArray());
  }

  private static String placeholders(int n) {
    return String.join(", ", java.util.Collections.nCopies(n, "?"));
  }

  /**
   * The saga timeline (ticket 35, B13): every state change and every core command of {@code
   * txnId} with its result, in the order they happened ({@code timeline_seq}).
   */
  public List<TxnDetail.TimelineEntry> timeline(UUID txnId) {
    return jdbc.query(
        "select seq, 'STATE' as kind, from_status, to_status, trigger, at, null as command_type,"
            + " null as command_id, null::int as sends, null as result, null as outcome, null as reason_code,"
            + " null::timestamptz as answered_at from saga_step where txn_id = ?"
            + " union all"
            + " select seq, 'CORE', null, null, null, sent_at, command_type, command_id, sends, result, outcome,"
            + " reason_code, answered_at from core_call where txn_id = ?"
            + " order by seq",
        (rs, i) -> {
          Timestamp answered = rs.getTimestamp("answered_at");
          return new TxnDetail.TimelineEntry(
              rs.getString("kind"), rs.getString("from_status"), rs.getString("to_status"), rs.getString("trigger"),
              rs.getString("command_type"), rs.getString("command_id"), rs.getObject("sends", Integer.class),
              rs.getString("result"), rs.getString("outcome"), rs.getString("reason_code"),
              rs.getTimestamp("at").toInstant(), answered == null ? null : answered.toInstant());
        },
        txnId,
        txnId);
  }

  private static PendingLeg pendingLeg(String value) {
    return value == null ? null : PendingLeg.valueOf(value);
  }

  private TransactionRecord map(ResultSet rs, int row) throws SQLException {
    String code = rs.getString("error_code");
    ErrorInfo error =
        code == null
            ? null
            : new ErrorInfo(
                code, rs.getString("error_message"), rs.getString("error_message_bn"),
                rs.getBoolean("error_retryable"));
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
        rs.getString("hold_id"),
        rs.getString("partner_ref"),
        rs.getObject("reversal_txn_id", UUID.class),
        pendingLeg(rs.getString("pending_leg")));
  }
}
