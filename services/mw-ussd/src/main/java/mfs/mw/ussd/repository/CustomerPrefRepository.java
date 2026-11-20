package mfs.mw.ussd.repository;

import java.util.List;
import mfs.mw.ussd.service.CustomerPrefs;
import mfs.mw.ussd.util.MaskingPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code ussd.customer_pref}: language by MSISDN hash, never the number (CH-USSD-10). Default Bangla. */
@Repository
public class CustomerPrefRepository implements CustomerPrefs {

  private final JdbcTemplate jdbc;

  public CustomerPrefRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public String language(String msisdn) {
    List<String> rows =
        jdbc.query(
            "SELECT language FROM customer_pref WHERE msisdn_hash = ?",
            (rs, i) -> rs.getString(1),
            MaskingPolicy.msisdnHash(msisdn));
    return rows.isEmpty() ? "bn" : rows.getFirst();
  }

  @Override
  public void remember(String msisdn, String language) {
    jdbc.update(
        """
        INSERT INTO customer_pref (msisdn_hash, language) VALUES (?, ?)
        ON CONFLICT (msisdn_hash) DO UPDATE SET language = EXCLUDED.language
        """,
        MaskingPolicy.msisdnHash(msisdn),
        language);
  }

  /** Drops a remembered language so the next dial starts in Bangla. The number is not stored. */
  public void forget(String msisdn) {
    jdbc.update("DELETE FROM customer_pref WHERE msisdn_hash = ?", MaskingPolicy.msisdnHash(msisdn));
  }
}
