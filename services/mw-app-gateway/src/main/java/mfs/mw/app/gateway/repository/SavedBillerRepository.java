package mfs.mw.app.gateway.repository;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import mfs.mw.app.gateway.dto.SavedBiller;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code saved_biller}: the signed-in wallet's billers (ticket 107a). Account numbers are not encrypted. */
@Repository
public class SavedBillerRepository {

  private final JdbcTemplate jdbc;
  private final Clock clock;

  public SavedBillerRepository(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /** Inserts one row for this wallet. A repeat of the same biller and account is a duplicate key. */
  public SavedBiller insert(String walletId, String billerCode, String accountNo, String nickname) {
    UUID id = UUID.randomUUID();
    Instant at = clock.instant();
    jdbc.update(
        """
        insert into saved_biller (id, wallet_id, biller_code, account_no, nickname, created_at)
        values (?::uuid, ?, ?, ?, ?, ?)
        """,
        id.toString(), walletId, billerCode, accountNo, nickname, Timestamp.from(at));
    return new SavedBiller(id.toString(), billerCode, accountNo, nickname, at);
  }

  /** This wallet's rows, newest first. Another wallet's rows are not in the result. */
  public List<SavedBiller> list(String walletId) {
    return jdbc.query(
        """
        select id, biller_code, account_no, nickname, created_at
        from saved_biller where wallet_id = ? order by created_at desc, id
        """,
        (rs, i) -> new SavedBiller(rs.getObject("id", UUID.class).toString(), rs.getString("biller_code"),
            rs.getString("account_no"), rs.getString("nickname"), rs.getTimestamp("created_at").toInstant()),
        walletId);
  }

  /** Deletes the row only when it belongs to this wallet. */
  public boolean delete(String walletId, UUID id) {
    return jdbc.update("delete from saved_biller where id = ?::uuid and wallet_id = ?", id.toString(), walletId) == 1;
  }
}
