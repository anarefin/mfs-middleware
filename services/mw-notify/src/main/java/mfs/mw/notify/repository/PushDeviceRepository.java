package mfs.mw.notify.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code notify.push_device} (ticket 110): the device token of each wallet's bound device. Wallet ids are
 * the core's (the MSISDN for app wallets, the party id of {@code mw.txn.status.v1}).
 */
@Repository
public class PushDeviceRepository {

  private final JdbcTemplate jdbc;

  public PushDeviceRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Stores the token of {@code (walletId, deviceId)} unless a newer registration is stored. Call inside a
   * transaction with {@link #removeOthers}. True when stored.
   */
  public boolean upsert(String walletId, String deviceId, String token, String platform, Instant at) {
    return jdbc.update(
            "insert into push_device (wallet_id, device_id, token, platform, updated_at) values (?, ?, ?, ?, ?)"
                + " on conflict (wallet_id, device_id) do update set token = excluded.token,"
                + " platform = excluded.platform, updated_at = excluded.updated_at"
                + " where push_device.updated_at <= excluded.updated_at",
            walletId, deviceId, token, platform, Timestamp.from(at))
        == 1;
  }

  /**
   * Removes what a registration of {@code (walletId, deviceId, token)} replaces: the wallet's other devices
   * (one bound device per wallet), the device's rows under another wallet, and the token under any other
   * key. Returns the rows removed.
   */
  public int removeOthers(String walletId, String deviceId, String token) {
    return jdbc.update(
        "delete from push_device where (wallet_id = ? and device_id <> ?) or (device_id = ? and wallet_id <> ?)"
            + " or (token = ? and not (wallet_id = ? and device_id = ?))",
        walletId, deviceId, deviceId, walletId, token, walletId, deviceId);
  }

  /**
   * A re-bind of {@code walletId} to {@code deviceId} at {@code at}: removes the wallet's other devices' tokens
   * and the device's tokens under any other wallet (a device that moved), and records the binding (the
   * revocation tombstone {@link #revokedBy} reads; an older binding never replaces a newer one). Rows removed.
   */
  public int rebind(String walletId, String deviceId, Instant at) {
    int removed = jdbc.update(
        "delete from push_device where (wallet_id = ? and device_id <> ?) or (device_id = ? and wallet_id <> ?)",
        walletId, deviceId, deviceId, walletId);
    jdbc.update(
        "insert into push_device_binding (wallet_id, device_id, bound_at) values (?, ?, ?)"
            + " on conflict (wallet_id) do update set device_id = excluded.device_id, bound_at = excluded.bound_at"
            + " where push_device_binding.bound_at <= excluded.bound_at",
        walletId, deviceId, Timestamp.from(at));
    return removed;
  }

  /**
   * Whether a registration of {@code (walletId, deviceId)} stamped {@code registeredAt} was revoked by a
   * binding at or after it: the wallet bound to another device, or the device bound to another wallet.
   */
  public boolean revokedBy(String walletId, String deviceId, Instant registeredAt) {
    Integer n = jdbc.queryForObject(
        "select count(*) from push_device_binding where bound_at >= ?"
            + " and ((wallet_id = ? and device_id <> ?) or (device_id = ? and wallet_id <> ?))",
        Integer.class, Timestamp.from(registeredAt), walletId, deviceId, deviceId, walletId);
    return n != null && n > 0;
  }

  /** The wallet's newest token, if its app registered one. */
  public Optional<String> tokenOf(String walletId) {
    return jdbc.query(
            "select token from push_device where wallet_id = ? order by updated_at desc limit 1",
            (rs, i) -> rs.getString(1), walletId)
        .stream()
        .findFirst();
  }

  /** The platform of a registered token; empty when the token is not (or no longer) registered. */
  public Optional<String> platformOf(String token) {
    return jdbc.query("select platform from push_device where token = ?", (rs, i) -> rs.getString(1), token)
        .stream()
        .findFirst();
  }

  /** Drops a token the push server no longer knows; true when it was registered. */
  public boolean deleteToken(String token) {
    return jdbc.update("delete from push_device where token = ?", token) > 0;
  }
}
