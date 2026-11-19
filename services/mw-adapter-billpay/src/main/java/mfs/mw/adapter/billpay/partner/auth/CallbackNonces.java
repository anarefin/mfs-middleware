package mfs.mw.adapter.billpay.partner.auth;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Callback replay nonces (ticket 99, FRS PLT-008/PLT-014): a signed callback's nonce is let through
 * once per partner while its timestamp can still be accepted. Table {@code callback_nonce} (own
 * schema, V6): rows older than the window are deleted first, then the nonce is inserted {@code on
 * conflict do nothing}; inserted = first use. In PostgreSQL rather than Valkey/Redis: the adapters
 * use no Valkey/Redis, and a nonce row holds no personal data.
 */
@Component
public class CallbackNonces {

  private final JdbcTemplate jdbc;
  private final Clock clock;

  public CallbackNonces(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  /** True the first time {@code partnerId} sends {@code nonce} within {@code window}. */
  public boolean firstUse(String partnerId, String nonce, Duration window) {
    Instant now = clock.instant();
    jdbc.update("delete from callback_nonce where seen_at < ?", Timestamp.from(now.minus(window)));
    return jdbc.update(
            "insert into callback_nonce (partner_id, nonce, seen_at) values (?, ?, ?) on conflict do nothing",
            partnerId, nonce, Timestamp.from(now))
        == 1;
  }
}
