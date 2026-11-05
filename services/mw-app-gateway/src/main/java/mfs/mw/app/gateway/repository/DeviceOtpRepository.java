package mfs.mw.app.gateway.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import mfs.mw.app.gateway.entity.DeviceOtp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code app_device_otp}: the device binding OTP of each wallet (own schema only). Every change is
 * one conditional statement, so parallel requests can neither send more OTPs than allowed nor
 * compare more guesses than an OTP has checks (as mw-checkout's session OTP, ticket 31).
 */
@Repository
public class DeviceOtpRepository {

  private final JdbcTemplate jdbc;

  public DeviceOtpRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<DeviceOtp> find(String walletId) {
    return jdbc
        .query(
            "SELECT wallet_id, device_id, otp_hash, locale, expires_at, attempts, sends"
                + " FROM app_device_otp WHERE wallet_id = ?",
            (rs, n) -> row(rs), walletId)
        .stream()
        .findFirst();
  }

  /**
   * A new OTP for {@code deviceId}: replaces the wallet's previous one and resets its checks, and
   * counts the send in the current {@code window} (a new window starts once it has passed). The
   * sends of the window so far; empty, and nothing changed, when {@code maxSends} were already sent.
   */
  public Optional<Integer> issue(
      String walletId, String deviceId, String otpHash, String locale, Instant expiresAt, int maxSends,
      Duration window, Instant now) {
    Timestamp windowOver = ts(now.minus(window));
    return jdbc
        .query(
            """
            INSERT INTO app_device_otp
              (wallet_id, device_id, otp_hash, locale, expires_at, attempts, sends, window_start, updated_at)
            VALUES (?, ?, ?, ?, ?, 0, 1, ?, ?)
            ON CONFLICT (wallet_id) DO UPDATE
              SET device_id = excluded.device_id, otp_hash = excluded.otp_hash, locale = excluded.locale,
                  expires_at = excluded.expires_at, attempts = 0,
                  sends = CASE WHEN app_device_otp.window_start <= ? THEN 1 ELSE app_device_otp.sends + 1 END,
                  window_start = CASE WHEN app_device_otp.window_start <= ? THEN excluded.window_start
                                      ELSE app_device_otp.window_start END,
                  updated_at = excluded.updated_at
              WHERE app_device_otp.window_start <= ? OR app_device_otp.sends < ?
            RETURNING sends
            """,
            (rs, n) -> rs.getInt(1),
            walletId, deviceId, otpHash, locale, ts(expiresAt), ts(now), ts(now),
            windowOver, windowOver, windowOver, maxSends)
        .stream()
        .findFirst();
  }

  /**
   * Takes one of the current OTP's {@code max} checks before the guess is compared, in one atomic
   * statement: however many guesses arrive in parallel, at most {@code max} are compared. False:
   * no check left, or the OTP is not current for this device (replaced, spent, void or expired).
   */
  public boolean reserveAttempt(String walletId, String deviceId, String otpHash, int max, Instant now) {
    return jdbc.update(
            """
            UPDATE app_device_otp SET attempts = attempts + 1, updated_at = ?
             WHERE wallet_id = ? AND device_id = ? AND otp_hash = ? AND attempts < ? AND expires_at > ?
            """,
            ts(now), walletId, deviceId, otpHash, max, ts(now))
        == 1;
  }

  /** After a wrong guess that used the last check, the OTP is void (a new one must be asked for). */
  public void voidIfExhausted(String walletId, String otpHash, int max, Instant now) {
    jdbc.update(
        "UPDATE app_device_otp SET otp_hash = NULL, updated_at = ?"
            + " WHERE wallet_id = ? AND otp_hash = ? AND attempts >= ?",
        ts(now), walletId, otpHash, max);
  }

  /**
   * Spends the OTP that was just checked: only if it is still current for this device, unexpired,
   * and its check was one of the {@code max} reserved ones. True once per OTP.
   */
  public boolean consume(String walletId, String deviceId, String otpHash, int max, Instant now) {
    return jdbc.update(
            """
            UPDATE app_device_otp SET otp_hash = NULL, updated_at = ?
             WHERE wallet_id = ? AND device_id = ? AND otp_hash = ? AND attempts <= ? AND expires_at > ?
            """,
            ts(now), walletId, deviceId, otpHash, max, ts(now))
        == 1;
  }

  private static Timestamp ts(Instant i) {
    return Timestamp.from(i);
  }

  private static DeviceOtp row(ResultSet rs) throws SQLException {
    return new DeviceOtp(
        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant(),
        rs.getInt(6), rs.getInt(7));
  }
}
