package mfs.mw.ussd;

import mfs.mw.ussd.util.MaskingPolicy;
import org.springframework.jdbc.core.JdbcTemplate;

/** Drops a remembered language so a walk that expects Bangla at dial-in starts there. */
final class LanguageReset {

  private LanguageReset() {}

  static void banglaAgain(JdbcTemplate jdbc, String... msisdns) {
    for (String msisdn : msisdns) {
      jdbc.update("DELETE FROM customer_pref WHERE msisdn_hash = ?", MaskingPolicy.msisdnHash(msisdn));
    }
  }
}
