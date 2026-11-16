package mfs.mw.auth.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import mfs.mw.auth.entity.AuthDevice;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code auth.device} (ticket 68): one row per device id, at most one bound device per wallet
 * (partial unique index {@code device_one_bound_per_wallet_idx}). Binding a device id that was
 * bound to another wallet moves it to the new wallet.
 */
@Repository
public class DeviceRepository {

  private static final String COLUMNS = "device_id, wallet_id, public_key, bound_at, unbound_at";

  private final JdbcTemplate jdbc;

  public DeviceRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<AuthDevice> find(String deviceId) {
    return jdbc.query("SELECT " + COLUMNS + " FROM device WHERE device_id = ?", (rs, n) -> row(rs), deviceId)
        .stream()
        .findFirst();
  }

  /** The wallet's bound device, if it has one (at most one: the partial unique index). */
  public Optional<AuthDevice> findBound(String walletId) {
    return jdbc.query("SELECT " + COLUMNS + " FROM device WHERE wallet_id = ? AND unbound_at IS NULL",
            (rs, n) -> row(rs), walletId)
        .stream()
        .findFirst();
  }

  /** Every device row of the wallet, bound or not, newest binding first (dev test API). */
  public List<AuthDevice> findByWallet(String walletId) {
    return jdbc.query("SELECT " + COLUMNS + " FROM device WHERE wallet_id = ? ORDER BY bound_at DESC, device_id",
        (rs, n) -> row(rs), walletId);
  }

  /** Unbinds every bound device of the wallet; returns their ids (their sessions end). */
  public List<String> unbindAll(String walletId, Instant at) {
    return jdbc.query(
        "UPDATE device SET unbound_at = ? WHERE wallet_id = ? AND unbound_at IS NULL RETURNING device_id",
        (rs, n) -> rs.getString(1), Timestamp.from(at), walletId);
  }

  /** The row of {@code deviceId}, locked for the binding's transaction. */
  public Optional<AuthDevice> lock(String deviceId) {
    return jdbc
        .query("SELECT " + COLUMNS + " FROM device WHERE device_id = ? FOR UPDATE", (rs, n) -> row(rs), deviceId)
        .stream()
        .findFirst();
  }

  /** Unbinds every other bound device of the wallet; returns their ids (their sessions end). */
  public List<String> unbindOthers(String walletId, String deviceId, Instant at) {
    return jdbc.query(
        """
        UPDATE device SET unbound_at = ?
         WHERE wallet_id = ? AND device_id <> ? AND unbound_at IS NULL
        RETURNING device_id
        """,
        (rs, n) -> rs.getString(1),
        Timestamp.from(at), walletId, deviceId);
  }

  /** Binds (or re-binds, with a new key and possibly to another wallet) the device. */
  public void bind(String deviceId, String walletId, String publicKey, Instant at) {
    jdbc.update(
        """
        INSERT INTO device (device_id, wallet_id, public_key, bound_at, unbound_at)
        VALUES (?, ?, ?, ?, NULL)
        ON CONFLICT (device_id) DO UPDATE
          SET wallet_id = excluded.wallet_id, public_key = excluded.public_key,
              bound_at = excluded.bound_at, unbound_at = NULL
        """,
        deviceId, walletId, publicKey, Timestamp.from(at));
  }

  private static AuthDevice row(ResultSet rs) throws SQLException {
    Timestamp unbound = rs.getTimestamp(5);
    return new AuthDevice(
        rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant(),
        unbound == null ? null : unbound.toInstant());
  }
}
