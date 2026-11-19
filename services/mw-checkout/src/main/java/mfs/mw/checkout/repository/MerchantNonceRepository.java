package mfs.mw.checkout.repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Merchant request nonces (ticket 99, FRS PLT-008), table {@code checkout.merchant_nonce} (V8): rows
 * older than the window are deleted first, then the nonce is inserted {@code on conflict do
 * nothing}; inserted = first use. PostgreSQL rather than Valkey/Redis: this service uses no
 * Valkey/Redis, and a nonce is no personal data.
 */
@Repository
public class MerchantNonceRepository {

  private final JdbcTemplate jdbc;

  public MerchantNonceRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** True the first time {@code merchantId} sends {@code nonce} within {@code window}. */
  public boolean firstUse(String merchantId, String nonce, Instant now, Duration window) {
    jdbc.update("delete from merchant_nonce where seen_at < ?", Timestamp.from(now.minus(window)));
    return jdbc.update(
            "insert into merchant_nonce (merchant_id, nonce, seen_at) values (?, ?, ?) on conflict do nothing",
            merchantId, nonce, Timestamp.from(now))
        == 1;
  }
}
