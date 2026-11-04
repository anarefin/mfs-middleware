package mfs.mw.app.gateway.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import mfs.mw.app.gateway.entity.AppDevice;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code app_device}: devices bound to wallets (own schema only). */
@Repository
public class AppDeviceRepository {

  private static final String COLUMNS = "wallet_id, device_id, subject, public_key, status, bound_at";

  private final JdbcTemplate jdbc;

  public AppDeviceRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<AppDevice> find(String walletId, String deviceId) {
    return jdbc
        .query(
            "SELECT " + COLUMNS + " FROM app_device WHERE wallet_id = ? AND device_id = ?",
            (rs, n) -> row(rs), walletId, deviceId)
        .stream()
        .findFirst();
  }

  /** Binds (or re-binds with a new key) the device as ACTIVE. */
  public void bind(String walletId, String deviceId, String subject, String publicKey, Instant at) {
    jdbc.update(
        """
        INSERT INTO app_device (wallet_id, device_id, subject, public_key, status, bound_at, updated_at)
        VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)
        ON CONFLICT (wallet_id, device_id) DO UPDATE
          SET subject = excluded.subject, public_key = excluded.public_key, status = 'ACTIVE',
              bound_at = excluded.bound_at, updated_at = excluded.updated_at
        """,
        walletId, deviceId, subject, publicKey, Timestamp.from(at), Timestamp.from(at));
  }

  /** Marks every other ACTIVE device of the wallet REPLACED. */
  public void replaceOthers(String walletId, String deviceId, Instant at) {
    jdbc.update(
        """
        UPDATE app_device SET status = 'REPLACED', updated_at = ?
         WHERE wallet_id = ? AND device_id <> ? AND status = 'ACTIVE'
        """,
        Timestamp.from(at), walletId, deviceId);
  }

  /** Every device of the wallet but this one, active or replaced. */
  public List<AppDevice> others(String walletId, String deviceId) {
    return jdbc.query(
        "SELECT " + COLUMNS + " FROM app_device WHERE wallet_id = ? AND device_id <> ?",
        (rs, n) -> row(rs), walletId, deviceId);
  }

  private static AppDevice row(ResultSet rs) throws SQLException {
    return new AppDevice(
        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
        "ACTIVE".equals(rs.getString(5)), rs.getTimestamp(6).toInstant());
  }
}
