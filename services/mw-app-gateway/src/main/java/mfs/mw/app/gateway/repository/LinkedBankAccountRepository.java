package mfs.mw.app.gateway.repository;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import mfs.mw.app.gateway.dto.LinkedBankAccount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code linked_bank_account} (ticket 117b, FRS FL-08). The caller hands in the ciphertext and the
 * lookup hash ({@code util/AccountCipher}); this class never sees the number in the clear and never
 * reads the ciphertext back into a view.
 */
@Repository
public class LinkedBankAccountRepository {

  private final JdbcTemplate jdbc;
  private final Clock clock;

  public LinkedBankAccountRepository(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /** Inserts one row; the same account again for this wallet is a duplicate key. */
  public LinkedBankAccount insert(
      String walletId, String bankCode, String ciphertext, String lookup, String last4, String holderName) {
    UUID id = UUID.randomUUID();
    Instant at = clock.instant();
    jdbc.update(
        """
        insert into linked_bank_account
          (id, wallet_id, bank_code, account_ciphertext, account_lookup, account_last4, holder_name, verified_at, created_at)
        values (?::uuid, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        id.toString(), walletId, bankCode, ciphertext, lookup, last4, holderName, Timestamp.from(at), Timestamp.from(at));
    return new LinkedBankAccount(id.toString(), bankCode, last4, holderName, at);
  }

  public int count(String walletId) {
    Integer n = jdbc.queryForObject("select count(*) from linked_bank_account where wallet_id = ?", Integer.class, walletId);
    return n == null ? 0 : n;
  }

  /** This wallet's accounts, newest first. */
  public List<LinkedBankAccount> list(String walletId) {
    return jdbc.query(
        """
        select id, bank_code, account_last4, holder_name, verified_at
        from linked_bank_account where wallet_id = ? order by created_at desc, id
        """,
        (rs, i) -> new LinkedBankAccount(rs.getObject("id", UUID.class).toString(), rs.getString("bank_code"),
            rs.getString("account_last4"), rs.getString("holder_name"), rs.getTimestamp("verified_at").toInstant()),
        walletId);
  }

  /** The stored row of this wallet (ticket 117c): bank, ciphertext, last 4; empty for another wallet's id. */
  public java.util.Optional<Stored> find(String walletId, UUID id) {
    return jdbc.query(
            "select bank_code, account_ciphertext, account_last4 from linked_bank_account where id = ?::uuid and wallet_id = ?",
            (rs, i) -> new Stored(rs.getString("bank_code"), rs.getString("account_ciphertext"), rs.getString("account_last4")),
            id.toString(), walletId)
        .stream()
        .findFirst();
  }

  /** A stored account as read back for a payment; the number is still ciphertext. */
  public record Stored(String bankCode, String ciphertext, String last4) {
    @Override
    public String toString() {
      return "Stored[bankCode=" + bankCode + ", last4=" + last4 + "]";
    }
  }

  /** Deletes the row only when it belongs to this wallet. */
  public boolean delete(String walletId, UUID id) {
    return jdbc.update("delete from linked_bank_account where id = ?::uuid and wallet_id = ?", id.toString(), walletId) == 1;
  }
}
