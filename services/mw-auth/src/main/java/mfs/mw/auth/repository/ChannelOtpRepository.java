package mfs.mw.auth.repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code channel_otp} and {@code channel_otp_quota} (V8, ticket 72; security review): a channel's OTP
 * per wallet, purpose and binding (the checkout session), apart from {@code device_otp}. Every change
 * is one conditional statement, as in {@link DeviceOtpRepository}: parallel requests can neither send
 * more OTPs than the wallet's quota allows nor compare more guesses than an OTP has checks.
 */
@Repository
public class ChannelOtpRepository {

  /** A binding's current OTP: its hash (null once spent or void), expiry and checks used. */
  public record Row(String otpHash, String locale, Instant expiresAt, int attempts) {}

  private final JdbcTemplate jdbc;

  public ChannelOtpRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<Row> find(String walletId, String purpose, String bindingId) {
    return jdbc
        .query(
            "SELECT otp_hash, locale, expires_at, attempts FROM channel_otp"
                + " WHERE wallet_id = ? AND purpose = ? AND binding_id = ?",
            (rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getInt(4)),
            walletId, purpose, bindingId)
        .stream()
        .findFirst();
  }

  /**
   * A new OTP for the binding: counts one send in the wallet's quota of this purpose (a new window
   * starts once {@code window} has passed) and, only when the quota allows it, replaces this
   * binding's OTP (checks reset); no other binding or purpose is touched. The sends of the window
   * so far; empty, and nothing changed, when {@code maxSends} were already sent. A wallet's expired
   * OTPs of this purpose are deleted first.
   */
  public Optional<Integer> issue(
      String walletId, String purpose, String bindingId, String otpHash, String locale, Instant expiresAt,
      int maxSends, Duration window, Instant now) {
    jdbc.update(
        "DELETE FROM channel_otp WHERE wallet_id = ? AND purpose = ? AND binding_id <> ? AND expires_at <= ?",
        walletId, purpose, bindingId, ts(now));
    Timestamp windowOver = ts(now.minus(window));
    return jdbc
        .query(
            """
            WITH q AS (
              INSERT INTO channel_otp_quota (wallet_id, purpose, sends, window_start)
              VALUES (?, ?, 1, ?)
              ON CONFLICT (wallet_id, purpose) DO UPDATE
                SET sends = CASE WHEN channel_otp_quota.window_start <= ? THEN 1 ELSE channel_otp_quota.sends + 1 END,
                    window_start = CASE WHEN channel_otp_quota.window_start <= ? THEN excluded.window_start
                                        ELSE channel_otp_quota.window_start END
                WHERE channel_otp_quota.window_start <= ? OR channel_otp_quota.sends < ?
              RETURNING sends
            )
            INSERT INTO channel_otp (wallet_id, purpose, binding_id, otp_hash, locale, expires_at, attempts, send_no, updated_at)
            SELECT ?, ?, ?, ?, ?, ?, 0, q.sends, ? FROM q
            ON CONFLICT (wallet_id, purpose, binding_id) DO UPDATE
              SET otp_hash = excluded.otp_hash, locale = excluded.locale, expires_at = excluded.expires_at,
                  attempts = 0, send_no = excluded.send_no, updated_at = excluded.updated_at
            RETURNING send_no
            """,
            (rs, n) -> rs.getInt(1),
            walletId, purpose, ts(now), windowOver, windowOver, windowOver, maxSends,
            walletId, purpose, bindingId, otpHash, locale, ts(expiresAt), ts(now))
        .stream()
        .findFirst();
  }

  /** Takes one of the binding's OTP checks before the guess is compared (atomic; false: none left or not current). */
  public boolean reserveAttempt(String walletId, String purpose, String bindingId, String otpHash, int max, Instant now) {
    return jdbc.update(
            """
            UPDATE channel_otp SET attempts = attempts + 1, updated_at = ?
             WHERE wallet_id = ? AND purpose = ? AND binding_id = ? AND otp_hash = ? AND attempts < ? AND expires_at > ?
            """,
            ts(now), walletId, purpose, bindingId, otpHash, max, ts(now))
        == 1;
  }

  /** After a wrong guess that used the last check, the OTP is void. */
  public void voidIfExhausted(String walletId, String purpose, String bindingId, String otpHash, int max, Instant now) {
    jdbc.update(
        "UPDATE channel_otp SET otp_hash = NULL, updated_at = ?"
            + " WHERE wallet_id = ? AND purpose = ? AND binding_id = ? AND otp_hash = ? AND attempts >= ?",
        ts(now), walletId, purpose, bindingId, otpHash, max);
  }

  /** Spends the OTP just checked, once: only if still current, unexpired and its check was reserved. */
  public boolean consume(String walletId, String purpose, String bindingId, String otpHash, int max, Instant now) {
    return jdbc.update(
            """
            UPDATE channel_otp SET otp_hash = NULL, updated_at = ?
             WHERE wallet_id = ? AND purpose = ? AND binding_id = ? AND otp_hash = ? AND attempts <= ? AND expires_at > ?
            """,
            ts(now), walletId, purpose, bindingId, otpHash, max, ts(now))
        == 1;
  }

  private static Timestamp ts(Instant i) {
    return Timestamp.from(i);
  }
}
