package mfs.mw.auth.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.auth.entity.PinReset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code auth.pin_reset} (own schema only; ticket 70). One open reset per wallet (partial unique
 * index); every status move is a conditional update on the expected status, so a late core answer or
 * a second request never moves a reset twice.
 */
@Repository
public class PinResetRepository {

  private static final String COLUMNS = "id, wallet_id, kind, status, device_id, expires_at, change_request_id, reason";

  private final JdbcTemplate jdbc;

  public PinResetRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<PinReset> find(UUID id) {
    return jdbc.query("select " + COLUMNS + " from pin_reset where id = ?", (rs, n) -> row(rs), id).stream().findFirst();
  }

  public Optional<PinReset> findByChangeRequest(UUID changeRequestId) {
    return jdbc.query("select " + COLUMNS + " from pin_reset where change_request_id = ?", (rs, n) -> row(rs),
        changeRequestId).stream().findFirst();
  }

  public Optional<PinReset> findByCommandId(String commandId) {
    return jdbc.query("select " + COLUMNS + " from pin_reset where command_id = ?", (rs, n) -> row(rs), commandId)
        .stream().findFirst();
  }

  /** The wallet's open reset, if any (expired or not). */
  public Optional<PinReset> open(String walletId) {
    return jdbc.query(
            "select " + COLUMNS + " from pin_reset where wallet_id = ?"
                + " and status in ('PENDING_KYC', 'PENDING_OTP', 'APPROVED', 'VERIFIED')",
            (rs, n) -> row(rs), walletId)
        .stream().findFirst();
  }

  /** Closes the wallet's open resets past their end (EXPIRED), or of {@code kind} whatever their end. */
  public int expire(String walletId, String kind, Instant now) {
    return jdbc.update(
        "update pin_reset set status = 'EXPIRED', updated_at = ? where wallet_id = ?"
            + " and status in ('PENDING_KYC', 'PENDING_OTP', 'APPROVED', 'VERIFIED') and (expires_at <= ? or kind = ?)",
        Timestamp.from(now), walletId, Timestamp.from(now), kind);
  }

  public void insert(PinReset r, String requestedBy, String commandId, Instant now) {
    jdbc.update(
        "insert into pin_reset (id, wallet_id, kind, status, requested_by, created_at, updated_at, device_id, expires_at,"
            + " change_request_id, command_id) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        r.id(), r.walletId(), r.kind(), r.status(), requestedBy, Timestamp.from(now), Timestamp.from(now), r.deviceId(),
        Timestamp.from(r.expiresAt()), r.changeRequestId(), commandId);
  }

  /** Moves the reset from {@code from} to {@code to} if it is still {@code from} and not expired. */
  public boolean move(UUID id, String from, String to, String reason, Instant now) {
    return jdbc.update(
            "update pin_reset set status = ?, reason = coalesce(?, reason), updated_at = ?"
                + " where id = ? and status = ? and expires_at > ?",
            to, reason, Timestamp.from(now), id, from, Timestamp.from(now))
        == 1;
  }

  /** A care reset taken up by {@code deviceId} after its OTP: VERIFIED for that device until {@code expiresAt}. */
  public boolean takeUp(UUID id, String deviceId, Instant expiresAt, Instant now) {
    return jdbc.update(
            "update pin_reset set status = 'VERIFIED', device_id = ?, expires_at = ?, updated_at = ?"
                + " where id = ? and status = 'APPROVED' and expires_at > ?",
            deviceId, Timestamp.from(expiresAt), Timestamp.from(now), id, Timestamp.from(now))
        == 1;
  }

  private static PinReset row(ResultSet rs) throws SQLException {
    return new PinReset(
        rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
        rs.getTimestamp(6).toInstant(), rs.getObject(7, UUID.class), rs.getString(8));
  }
}
