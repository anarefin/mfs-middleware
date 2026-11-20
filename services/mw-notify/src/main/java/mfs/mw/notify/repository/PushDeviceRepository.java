package mfs.mw.notify.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code notify.push_device} (ticket 110): the device token of each wallet's bound device. Wallet ids are
 * the core's (the MSISDN for app wallets, the party id of {@code mw.txn.status.v1}). Bindings and
 * registrations are ordered by mw-auth's binding epoch (ticket 110b), never by a service's clock.
 */
@Repository
public class PushDeviceRepository {

  private final JdbcTemplate jdbc;

  public PushDeviceRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Stores the token of {@code (walletId, deviceId)} from binding {@code epoch} unless the stored row is of a
   * newer binding, or of the same binding and registered later ({@code at}, the gateway's clock, only orders
   * registrations of one binding). Call inside a transaction with {@link #removeOthers}. True when stored.
   */
  public boolean upsert(String walletId, String deviceId, String token, String platform, long epoch, Instant at) {
    return jdbc.update(
            "insert into push_device (wallet_id, device_id, token, platform, binding_epoch, updated_at)"
                + " values (?, ?, ?, ?, ?, ?)"
                + " on conflict (wallet_id, device_id) do update set token = excluded.token,"
                + " platform = excluded.platform, binding_epoch = excluded.binding_epoch,"
                + " updated_at = excluded.updated_at"
                + " where coalesce(push_device.binding_epoch, 0) < excluded.binding_epoch"
                + " or (push_device.binding_epoch = excluded.binding_epoch"
                + " and push_device.updated_at <= excluded.updated_at)",
            walletId, deviceId, token, platform, epoch, Timestamp.from(at))
        == 1;
  }

  /**
   * Removes what a registration of {@code (walletId, deviceId, token)} from binding {@code epoch} replaces:
   * the wallet's other devices (one bound device per wallet) and the device's rows under another wallet, of
   * older bindings, and the token under any other key. Returns the rows removed.
   */
  public int removeOthers(String walletId, String deviceId, String token, long epoch) {
    return jdbc.update(
        "delete from push_device where (((wallet_id = ? and device_id <> ?) or (device_id = ? and wallet_id <> ?))"
            + " and coalesce(binding_epoch, 0) < ?)"
            + " or (token = ? and not (wallet_id = ? and device_id = ?))",
        walletId, deviceId, deviceId, walletId, epoch, token, walletId, deviceId);
  }

  /**
   * A re-bind of {@code walletId} to {@code deviceId} as binding {@code epoch} (mw-auth's): removes the
   * wallet's other devices' tokens and the device's tokens under any other wallet (a device that moved) that
   * come from older bindings, and records the binding (the revocation tombstone {@link #supersedes} reads; an
   * older epoch never replaces a newer one). A notice without an epoch ({@code null}) removes those rows
   * whatever their binding and records nothing. Rows removed.
   */
  public int rebind(String walletId, String deviceId, Long epoch, Instant at) {
    if (epoch == null) {
      return jdbc.update(
          "delete from push_device where (wallet_id = ? and device_id <> ?) or (device_id = ? and wallet_id <> ?)",
          walletId, deviceId, deviceId, walletId);
    }
    int removed = jdbc.update(
        "delete from push_device where ((wallet_id = ? and device_id <> ?) or (device_id = ? and wallet_id <> ?))"
            + " and coalesce(binding_epoch, 0) < ?",
        walletId, deviceId, deviceId, walletId, epoch);
    jdbc.update(
        "insert into push_device_binding (wallet_id, device_id, binding_epoch, bound_at) values (?, ?, ?, ?)"
            + " on conflict (wallet_id, device_id) do update set binding_epoch = excluded.binding_epoch,"
            + " bound_at = excluded.bound_at"
            + " where coalesce(push_device_binding.binding_epoch, 0) < excluded.binding_epoch",
        walletId, deviceId, epoch, Timestamp.from(at));
    return removed;
  }

  /**
   * Whether a registration of {@code (walletId, deviceId)} from binding {@code epoch} is superseded: a newer
   * binding of the wallet or of the device is known (a DEVICE_BOUND notice), or a token of another device of
   * the wallet, or of the device under another wallet, from a newer binding is stored (its notice not in yet).
   */
  public boolean supersedes(String walletId, String deviceId, long epoch) {
    Boolean b = jdbc.queryForObject(
        "select exists (select 1 from push_device_binding where binding_epoch > ? and (wallet_id = ? or device_id = ?))"
            + " or exists (select 1 from push_device where binding_epoch > ?"
            + " and ((wallet_id = ? and device_id <> ?) or (device_id = ? and wallet_id <> ?)))",
        Boolean.class, epoch, walletId, deviceId, epoch, walletId, deviceId, deviceId, walletId);
    return Boolean.TRUE.equals(b);
  }

  /** The wallet's token of its newest binding, if its app registered one. */
  public Optional<String> tokenOf(String walletId) {
    return jdbc.query(
            "select token from push_device where wallet_id = ?"
                + " order by coalesce(binding_epoch, 0) desc, updated_at desc limit 1",
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
