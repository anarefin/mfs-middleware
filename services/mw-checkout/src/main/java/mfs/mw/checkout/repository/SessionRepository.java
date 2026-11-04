package mfs.mw.checkout.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.checkout.dto.SessionStatus;
import mfs.mw.checkout.entity.CheckoutSession;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code checkout.session} (spec 9.3, 16.2). Every state change is one conditional statement, so
 * two requests racing on one session (a double click, two tabs) cannot both win: the caller
 * checks the row count.
 */
@Repository
public class SessionRepository {

  private static final String COLUMNS =
      "session_id, pay_token, merchant_id, merchant_wallet, order_id, amount_minor, currency, description,"
          + " locale, success_url, fail_url, cancel_url, ipn_url, status, payer_msisdn, otp_hash, otp_expires_at,"
          + " otp_attempts, otp_sends, txn_id, error_code, created_at, expires_at, updated_at";

  private final JdbcClient jdbc;

  public SessionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** Stores a new session; false when the merchant already has a session for this order. */
  public boolean insert(CheckoutSession s) {
    return jdbc.sql(
                "insert into session (session_id, pay_token, merchant_id, merchant_wallet, order_id, amount_minor,"
                    + " currency, description, locale, success_url, fail_url, cancel_url, ipn_url, status,"
                    + " created_at, expires_at, updated_at)"
                    + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                    + " on conflict (merchant_id, order_id) do nothing")
            .params(
                s.sessionId(), s.payToken(), s.merchantId(), s.merchantWallet(), s.orderId(), s.amountMinor(),
                s.currency(), s.description(), s.locale(), s.successUrl(), s.failUrl(), s.cancelUrl(), s.ipnUrl(),
                s.status().name(), ts(s.createdAt()), ts(s.expiresAt()), ts(s.updatedAt()))
            .update()
        == 1;
  }

  public Optional<CheckoutSession> findById(UUID sessionId) {
    return one("session_id = ?", sessionId);
  }

  public Optional<CheckoutSession> findByToken(String payToken) {
    return one("pay_token = ?", payToken);
  }

  public Optional<CheckoutSession> findByMerchantOrder(String merchantId, String orderId) {
    return jdbc.sql("select " + COLUMNS + " from session where merchant_id = ? and order_id = ?")
        .params(merchantId, orderId)
        .query(SessionRepository::map)
        .optional();
  }

  public Optional<CheckoutSession> findByTxnId(UUID txnId) {
    return one("txn_id = ?", txnId);
  }

  /**
   * A new OTP for {@code payer}: replaces the previous one, resets the attempts, counts the send.
   * Only while the session is OPEN, unexpired and below {@code maxSends}.
   */
  public boolean otpIssued(
      UUID sessionId, String payer, String otpHash, Instant otpExpiresAt, int maxSends, Instant now) {
    return jdbc.sql(
                "update session set payer_msisdn = ?, otp_hash = ?, otp_expires_at = ?, otp_attempts = 0,"
                    + " otp_sends = otp_sends + 1, updated_at = ?"
                    + " where session_id = ? and status = 'OPEN' and expires_at > ? and otp_sends < ?")
            .params(payer, otpHash, ts(otpExpiresAt), ts(now), sessionId, ts(now), maxSends)
            .update()
        == 1;
  }

  /**
   * Takes one of the current OTP's {@code max} checks before the guess is compared, in one atomic
   * statement: parallel guesses cannot all be compared before the counter moves, so at most
   * {@code max} guesses are ever evaluated per OTP. False: no check left (or the OTP was replaced,
   * spent or voided, or the session is no longer OPEN).
   */
  public boolean reserveOtpAttempt(UUID sessionId, String otpHash, int max, Instant now) {
    return jdbc.sql(
                "update session set otp_attempts = otp_attempts + 1, updated_at = ?"
                    + " where session_id = ? and status = 'OPEN' and otp_hash = ? and otp_attempts < ?")
            .params(ts(now), sessionId, otpHash, max)
            .update()
        == 1;
  }

  /** After a wrong guess that used the last check, the OTP is voided (a new one must be requested). */
  public void voidOtpIfExhausted(UUID sessionId, String otpHash, int max, Instant now) {
    jdbc.sql(
            "update session set otp_hash = null, updated_at = ?"
                + " where session_id = ? and otp_hash = ? and otp_attempts >= ?")
        .params(ts(now), sessionId, otpHash, max)
        .update();
  }

  /**
   * OPEN -> PAYING with the payment's txnId, only if still OPEN, unexpired, the OTP checked is
   * still the current one and its check was within the {@code max} reserved ones. The OTP is spent.
   */
  public boolean claimForPayment(UUID sessionId, String otpHash, UUID txnId, int max, Instant now) {
    return jdbc.sql(
                "update session set status = 'PAYING', txn_id = ?, otp_hash = null, updated_at = ?"
                    + " where session_id = ? and status = 'OPEN' and expires_at > ? and otp_hash = ?"
                    + " and otp_attempts <= ?")
            .params(txnId, ts(now), sessionId, ts(now), otpHash, max)
            .update()
        == 1;
  }

  /** PAYING -> OPEN again when the payment request could not be sent at all. */
  public void releaseClaim(UUID sessionId, UUID txnId, Instant now) {
    jdbc.sql(
            "update session set status = 'OPEN', txn_id = null, updated_at = ?"
                + " where session_id = ? and status = 'PAYING' and txn_id = ?")
        .params(ts(now), sessionId, txnId)
        .update();
  }

  /** PAYING -> PAID or FAILED; false when the session is not (any more) waiting for this txn. */
  public boolean finish(UUID txnId, SessionStatus status, String errorCode, Instant now) {
    return jdbc.sql(
                "update session set status = ?, error_code = ?, updated_at = ?"
                    + " where txn_id = ? and status = 'PAYING'")
            .params(status.name(), errorCode, ts(now), txnId)
            .update()
        == 1;
  }

  /** OPEN -> CANCELLED while unexpired. */
  public boolean cancel(UUID sessionId, Instant now) {
    return jdbc.sql(
                "update session set status = 'CANCELLED', otp_hash = null, updated_at = ?"
                    + " where session_id = ? and status = 'OPEN' and expires_at > ?")
            .params(ts(now), sessionId, ts(now))
            .update()
        == 1;
  }

  private Optional<CheckoutSession> one(String where, Object param) {
    return jdbc.sql("select " + COLUMNS + " from session where " + where)
        .param(param)
        .query(SessionRepository::map)
        .optional();
  }

  private static CheckoutSession map(ResultSet rs, int row) throws SQLException {
    return new CheckoutSession(
        rs.getObject("session_id", UUID.class),
        rs.getString("pay_token"),
        rs.getString("merchant_id"),
        rs.getString("merchant_wallet"),
        rs.getString("order_id"),
        rs.getLong("amount_minor"),
        rs.getString("currency"),
        rs.getString("description"),
        rs.getString("locale"),
        rs.getString("success_url"),
        rs.getString("fail_url"),
        rs.getString("cancel_url"),
        rs.getString("ipn_url"),
        SessionStatus.valueOf(rs.getString("status")),
        rs.getString("payer_msisdn"),
        rs.getString("otp_hash"),
        instant(rs.getTimestamp("otp_expires_at")),
        rs.getInt("otp_attempts"),
        rs.getInt("otp_sends"),
        rs.getObject("txn_id", UUID.class),
        rs.getString("error_code"),
        instant(rs.getTimestamp("created_at")),
        instant(rs.getTimestamp("expires_at")),
        instant(rs.getTimestamp("updated_at")));
  }

  private static Timestamp ts(Instant i) {
    return i == null ? null : Timestamp.from(i);
  }

  private static Instant instant(Timestamp t) {
    return t == null ? null : t.toInstant();
  }
}
