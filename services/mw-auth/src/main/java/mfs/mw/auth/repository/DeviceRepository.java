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
 * {@code auth.device} (ticket 68): one row per device id. How many stay bound to a wallet is the
 * app-type cap ({@code unbindBeyond}, ticket 106). Binding a device id that was
 * bound to another wallet moves it to the new wallet.
 */
@Repository
public class DeviceRepository {

  private static final String COLUMNS = "device_id, wallet_id, public_key, bound_at, unbound_at, binding_epoch";

  private final JdbcTemplate jdbc;

  public DeviceRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<AuthDevice> find(String deviceId) {
    return jdbc.query("SELECT " + COLUMNS + " FROM device WHERE device_id = ?", (rs, n) -> row(rs), deviceId)
        .stream()
        .findFirst();
  }

  /** The wallet's bound device, if this query finds one. Prefer {@link #findBoundDevice} when the device is known. */
  public Optional<AuthDevice> findBound(String walletId) {
    return jdbc.query("SELECT " + COLUMNS + " FROM device WHERE wallet_id = ? AND unbound_at IS NULL",
            (rs, n) -> row(rs), walletId)
        .stream()
        .findFirst();
  }

  /** The bound row of this device on this wallet. */
  public Optional<AuthDevice> findBoundDevice(String walletId, String deviceId) {
    return jdbc.query(
            "SELECT " + COLUMNS + " FROM device WHERE wallet_id = ? AND device_id = ? AND unbound_at IS NULL",
            (rs, n) -> row(rs), walletId, deviceId)
        .stream()
        .findFirst();
  }

  /** Every device still bound to the wallet, newest binding first. */
  public List<AuthDevice> findBoundAll(String walletId) {
    return jdbc.query(
        "SELECT " + COLUMNS + " FROM device WHERE wallet_id = ? AND unbound_at IS NULL ORDER BY bound_at DESC, device_id",
        (rs, n) -> row(rs), walletId);
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

  /**
   * Unbinds bound devices of the wallet other than {@code deviceId} beyond the {@code keepOthers} newest
   * (ticket 106). {@code keepOthers} 0 unbinds every other device.
   */
  public List<String> unbindBeyond(String walletId, String deviceId, int keepOthers, Instant at) {
    return jdbc.query(
        """
        WITH ranked AS (
          SELECT device_id, row_number() OVER (ORDER BY bound_at DESC, device_id) AS n
          FROM device
          WHERE wallet_id = ? AND device_id <> ? AND unbound_at IS NULL
        )
        UPDATE device SET unbound_at = ?
         WHERE device_id IN (SELECT device_id FROM ranked WHERE n > ?)
        RETURNING device_id
        """,
        (rs, n) -> rs.getString(1), walletId, deviceId, Timestamp.from(at), keepOthers);
  }

  /**
   * Binds (or re-binds, with a new key and possibly to another wallet) the device; returns the binding's epoch,
   * the next value of {@code device_binding_epoch_seq} (ticket 110b), new for every binding.
   */
  public long bind(String deviceId, String walletId, String publicKey, Instant at) {
    Long epoch = jdbc.queryForObject(
        """
        INSERT INTO device (device_id, wallet_id, public_key, bound_at, unbound_at, binding_epoch)
        VALUES (?, ?, ?, ?, NULL, nextval('device_binding_epoch_seq'))
        ON CONFLICT (device_id) DO UPDATE
          SET wallet_id = excluded.wallet_id, public_key = excluded.public_key,
              bound_at = excluded.bound_at, unbound_at = NULL, binding_epoch = excluded.binding_epoch
        RETURNING binding_epoch
        """,
        Long.class, deviceId, walletId, publicKey, Timestamp.from(at));
    return epoch == null ? 0 : epoch;
  }

  private static AuthDevice row(ResultSet rs) throws SQLException {
    Timestamp unbound = rs.getTimestamp(5);
    return new AuthDevice(
        rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant(),
        unbound == null ? null : unbound.toInstant(), rs.getLong(6));
  }
}
