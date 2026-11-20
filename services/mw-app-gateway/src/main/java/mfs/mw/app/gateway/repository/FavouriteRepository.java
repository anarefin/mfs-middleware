package mfs.mw.app.gateway.repository;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import mfs.mw.app.gateway.dto.Favourite;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code favourite}: the signed-in wallet's counterparties (ticket 107a). The MSISDN is not encrypted. */
@Repository
public class FavouriteRepository {

  private final JdbcTemplate jdbc;
  private final Clock clock;

  public FavouriteRepository(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /** Inserts one row. A repeat of the same counterparty for this wallet is a duplicate key. */
  public Favourite insert(String walletId, String msisdn, String label) {
    UUID id = UUID.randomUUID();
    Instant at = clock.instant();
    jdbc.update(
        """
        insert into favourite (id, wallet_id, counterparty_msisdn, label, created_at)
        values (?::uuid, ?, ?, ?, ?)
        """,
        id.toString(), walletId, msisdn, label, Timestamp.from(at));
    return new Favourite(id.toString(), msisdn, label, at);
  }

  /** This wallet's rows, newest first. */
  public List<Favourite> list(String walletId) {
    return jdbc.query(
        """
        select id, counterparty_msisdn, label, created_at
        from favourite where wallet_id = ? order by created_at desc, id
        """,
        (rs, i) -> new Favourite(rs.getObject("id", UUID.class).toString(), rs.getString("counterparty_msisdn"),
            rs.getString("label"), rs.getTimestamp("created_at").toInstant()),
        walletId);
  }

  /** Deletes the row only when it belongs to this wallet. */
  public boolean delete(String walletId, UUID id) {
    return jdbc.update("delete from favourite where id = ?::uuid and wallet_id = ?", id.toString(), walletId) == 1;
  }
}
