package mfs.mw.core.mock.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import mfs.mw.core.mock.entity.LedgerEntry;
import mfs.mw.core.mock.entity.PostedEntry;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Double-entry rows and core references. */
@Repository
public class LedgerRepository {

  private final JdbcTemplate jdbc;

  public LedgerRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void append(
      String commandId, String txnId, String walletId, String direction, String kind, long amountMinor, Instant at) {
    jdbc.update(
        "insert into entry (command_id, txn_id, wallet_id, direction, kind, amount_minor, created_at) "
            + "values (?, ?, ?, ?, ?, ?, ?)",
        commandId,
        txnId,
        walletId,
        direction,
        kind,
        amountMinor,
        Timestamp.from(at));
  }

  /**
   * Posts one transaction's legs (ticket 94): each leg moves its wallet's balance and writes its entry
   * with the balance right after it, in one statement. Legs go in wallet id order (people's MSISDNs
   * before the system wallets, the order every multi-wallet change locks in, so concurrent postings
   * cannot deadlock), a wallet's credits before its debits (no balance dips below zero on the way).
   * The legs must balance (debits = credits).
   */
  public void post(String commandId, String txnId, String txnType, List<LedgerEntry> legs, Instant at) {
    long net = legs.stream().mapToLong(l -> l.debit() ? -l.amountMinor() : l.amountMinor()).sum();
    if (net != 0) {
      throw new IllegalStateException("unbalanced posting of " + txnId + ": " + net);
    }
    legs.stream()
        .filter(l -> l.amountMinor() > 0)
        .sorted(Comparator.comparing(LedgerEntry::walletId).thenComparing(LedgerEntry::debit))
        .forEach(l -> jdbc.update(
            "with w as (update wallet set balance_minor = balance_minor + ? where id = ? returning balance_minor) "
                + "insert into entry (command_id, txn_id, txn_type, wallet_id, direction, kind, amount_minor, "
                + "balance_after_minor, created_at) select ?, ?, ?, ?, ?, ?, ?, w.balance_minor, ? from w",
            l.debit() ? -l.amountMinor() : l.amountMinor(), l.walletId(),
            commandId, txnId, txnType, l.walletId(), l.direction(), l.kind(), l.amountMinor(), Timestamp.from(at)));
  }

  public long nextCoreSequence() {
    Long n = jdbc.queryForObject("select nextval('core_ref_seq')", Long.class);
    return n == null ? 0 : n;
  }

  /** Every entry posted under {@code txnId}. */
  public List<LedgerEntry> entriesOf(String txnId) {
    return jdbc.query(
        "select wallet_id, direction, kind, amount_minor from entry where txn_id = ? order by id",
        (rs, i) -> new LedgerEntry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)),
        txnId);
  }

  private static final RowMapper<PostedEntry> POSTED =
      (rs, i) -> new PostedEntry(rs.getString("txn_id"), rs.getString("wallet_id"), rs.getString("direction"),
          rs.getString("kind"), rs.getLong("amount_minor"), rs.getObject("balance_after_minor", Long.class),
          rs.getTimestamp("created_at").toInstant());
  private static final String POSTED_COLUMNS =
      "select txn_id, wallet_id, direction, kind, amount_minor, balance_after_minor, created_at from entry ";

  /** Every entry of {@code txnId} with its time, in posting order (WAL-13, ticket 93). */
  public List<PostedEntry> postedOf(String txnId) {
    return jdbc.query(
        POSTED_COLUMNS + "where txn_id = ? order by id",
        POSTED, txnId);
  }

  /**
   * At most {@code limit} entries of a wallet in [from, to), oldest first, skipping the first {@code
   * offset} (WAL-12, tickets 93, 94).
   */
  public List<PostedEntry> postedOfWallet(String walletId, Instant from, Instant to, int limit, long offset) {
    return jdbc.query(
        POSTED_COLUMNS + "where wallet_id = ? and created_at >= ? and created_at < ? order by created_at, id limit ? offset ?",
        POSTED, walletId, Timestamp.from(from), Timestamp.from(to), limit, offset);
  }

  public boolean isReversed(String originalTxnId) {
    Integer n = jdbc.queryForObject("select count(*) from reversal where original_txn_id = ?", Integer.class, originalTxnId);
    return n != null && n > 0;
  }

  /** The total of the partial reversals (refunds) of {@code originalTxnId} so far (ticket 116). */
  public long refundedMinor(String originalTxnId) {
    Long n = jdbc.queryForObject(
        "select coalesce(sum(amount_minor), 0) from partial_reversal where original_txn_id = ?", Long.class, originalTxnId);
    return n == null ? 0 : n;
  }

  public void recordPartialReversal(String originalTxnId, String reversalTxnId, long amountMinor) {
    jdbc.update("insert into partial_reversal (reversal_txn_id, original_txn_id, amount_minor) values (?, ?, ?)",
        reversalTxnId, originalTxnId, amountMinor);
  }

  public void markReversed(String originalTxnId, String reversalTxnId) {
    jdbc.update("insert into reversal (original_txn_id, reversal_txn_id) values (?, ?)", originalTxnId, reversalTxnId);
  }
}
