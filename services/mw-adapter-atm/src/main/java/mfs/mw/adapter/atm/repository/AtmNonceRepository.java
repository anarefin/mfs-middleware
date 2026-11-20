package mfs.mw.adapter.atm.repository;

import java.time.Duration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Table {@code atm_ws_nonce} (V10): the once-only WS-Security nonces of the inbound ATM API
 * (ticket 120b2, FRS PLT-008). Each row expires at its own {@code now() + window}; a call first
 * deletes the rows whose expiry has passed, then inserts its nonce {@code on conflict do nothing}
 * (inserted = first use). Both use the database clock, so two instances whose clocks differ never
 * purge each other's live nonces. Kept apart from the partner framework's {@code callback_nonce},
 * whose purge runs on the callbacks' own window.
 */
@Repository
public class AtmNonceRepository {

  private final JdbcTemplate jdbc;

  public AtmNonceRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** True the first time {@code partnerId} sends {@code nonce} among the rows live now. */
  public boolean firstUse(String partnerId, String nonce, Duration window) {
    jdbc.update("delete from atm_ws_nonce where expires_at < now()");
    return jdbc.update(
            "insert into atm_ws_nonce (partner_id, nonce, expires_at) "
                + "values (?, ?, now() + make_interval(secs => ?)) on conflict do nothing",
            partnerId, nonce, window.toMillis() / 1000.0)
        == 1;
  }
}
