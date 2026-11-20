package mfs.mw.audit.dw.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The search projection {@code audit.txn_index} (ticket 130), written as the service's own role
 * ({@code audit_writer}: SELECT, INSERT, UPDATE on this table only). One row per transaction; the
 * status follows the newest {@code updatedAt}, so a late, older status event never wins; channel,
 * type, amount, partner and requestedAt keep the first value seen; MSISDN tokens are set once.
 */
@Repository
public class TxnIndexRepository {

  /** One transaction's projected facts; {@code msisdnTokens} never holds an MSISDN. */
  public record Row(
      UUID txnId,
      String channel,
      String txnType,
      String status,
      Long amountMinor,
      String currency,
      String partnerId,
      List<String> msisdnTokens,
      Instant requestedAt,
      Instant updatedAt) {}

  private final JdbcTemplate jdbc;

  public TxnIndexRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** The tokens already stored for {@code txnId}, empty when the row or its tokens are missing. */
  public Optional<List<String>> tokens(UUID txnId) {
    List<String[]> rows =
        jdbc.query(
            "select msisdn_tokens from txn_index where txn_id = ?",
            (rs, i) -> (String[]) rs.getArray(1).getArray(),
            txnId);
    return rows.isEmpty() || rows.get(0).length == 0 ? Optional.empty() : Optional.of(List.of(rows.get(0)));
  }

  public void upsert(Row r) {
    jdbc.update(
        """
        insert into txn_index (txn_id, channel, txn_type, status, amount_minor, currency, partner_id,
                               msisdn_tokens, requested_at, updated_at)
        values (?, ?, ?, ?, ?, ?, ?, ?::text[], ?, ?)
        on conflict (txn_id) do update set
          channel       = coalesce(txn_index.channel, excluded.channel),
          txn_type      = coalesce(txn_index.txn_type, excluded.txn_type),
          amount_minor  = coalesce(txn_index.amount_minor, excluded.amount_minor),
          currency      = coalesce(txn_index.currency, excluded.currency),
          partner_id    = coalesce(txn_index.partner_id, excluded.partner_id),
          requested_at  = coalesce(txn_index.requested_at, excluded.requested_at),
          msisdn_tokens = case when cardinality(txn_index.msisdn_tokens) = 0
                               then excluded.msisdn_tokens else txn_index.msisdn_tokens end,
          status        = case when excluded.updated_at >= txn_index.updated_at
                               then excluded.status else txn_index.status end,
          updated_at    = greatest(txn_index.updated_at, excluded.updated_at)
        """,
        r.txnId(), r.channel(), r.txnType(), r.status(), r.amountMinor(), r.currency(), r.partnerId(),
        "{" + String.join(",", r.msisdnTokens()) + "}",
        r.requestedAt() == null ? null : Timestamp.from(r.requestedAt()),
        Timestamp.from(r.updatedAt()));
  }
}
