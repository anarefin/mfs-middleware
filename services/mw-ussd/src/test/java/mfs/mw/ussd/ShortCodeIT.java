package mfs.mw.ussd;

import static org.assertj.core.api.Assertions.assertThat;

import mfs.mw.ussd.client.StubCoreAccounts;
import mfs.mw.ussd.dto.UssdEvent;
import mfs.mw.ussd.dto.UssdRequest;
import mfs.mw.ussd.dto.UssdResponse;
import mfs.mw.ussd.service.UssdEngine;
import mfs.mw.ussd.util.MaskingPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Short codes and the remembered language against PostgreSQL (CH-USSD-08, CH-USSD-10). The core
 * account type comes from {@link StubCoreAccounts}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class ShortCodeIT {

  private static final String CUSTOMER = "01719990001";
  private static final String OTHER = "01719990002";

  private final UssdEngine engine;
  private final StubCoreAccounts accounts;
  private final JdbcTemplate jdbc;

  @Autowired
  ShortCodeIT(UssdEngine engine, StubCoreAccounts accounts, JdbcTemplate jdbc) {
    this.engine = engine;
    this.accounts = accounts;
    this.jdbc = jdbc;
  }

  @Test
  void aDisallowedCustomerTypeIsRefusedInBanglaAndEnglish() {
    jdbc.update("DELETE FROM short_code WHERE mno = 'robi' AND code = '*277#'");
    jdbc.update(
        "INSERT INTO short_code (mno, code, root_menu_id, allowed_types) VALUES ('robi', '*277#', 'main', ?::text[])",
        "{AGENT}");
    accounts.setType(CUSTOMER, "CUSTOMER");
    UssdResponse bn = engine.handle(new UssdRequest("robi", "sc-bn", CUSTOMER, UssdEvent.BEGIN, "", "*277#"));
    assertThat(bn.end()).isTrue();
    assertThat(bn.text()).isEqualTo("এই কোডটি আপনার অ্যাকাউন্টের জন্য নয়।");

    accounts.setType(OTHER, "MERCHANT");
    jdbc.update(
        """
        INSERT INTO customer_pref (msisdn_hash, language) VALUES (?, 'en')
        ON CONFLICT (msisdn_hash) DO UPDATE SET language = 'en'
        """,
        MaskingPolicy.msisdnHash(OTHER));
    UssdResponse en = engine.handle(new UssdRequest("robi", "sc-en", OTHER, UssdEvent.BEGIN, "", "*277#"));
    assertThat(en.end()).isTrue();
    assertThat(en.text()).isEqualTo("This code is not available for your account.");
  }

  @Test
  void anUnknownShortCodeEndsInBangla() {
    UssdResponse r = engine.handle(new UssdRequest("robi", "sc-no", "01719990003", UssdEvent.BEGIN, "", "*000#"));
    assertThat(r.end()).isTrue();
    assertThat(r.text()).isEqualTo("এই কোডটি এই নেটওয়ার্কে নেই। ডায়াল করুন *269#");
  }

  @Test
  void languagePersistsAcrossTwoSessionsAndTheTableKeepsOnlyTheHash() {
    String msisdn = "01719990004";
    UssdResponse first = engine.handle(new UssdRequest("robi", "lang-1", msisdn, UssdEvent.BEGIN, "", "*269#"));
    assertThat(first.text()).contains("সেন্ড মানি");
    engine.handle(new UssdRequest("robi", "lang-1", msisdn, UssdEvent.CONTINUE, "9"));
    engine.handle(new UssdRequest("robi", "lang-1", msisdn, UssdEvent.END, null));

    UssdResponse second = engine.handle(new UssdRequest("robi", "lang-2", msisdn, UssdEvent.BEGIN, "", "*269#"));
    assertThat(second.end()).isFalse();
    assertThat(second.text()).contains("Send Money").doesNotContain("সেন্ড");

    String hash = MaskingPolicy.msisdnHash(msisdn);
    assertThat(hash).hasSize(64).doesNotContain(msisdn);
    assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM customer_pref WHERE msisdn_hash = ? AND language = 'en'", Long.class, hash))
        .isEqualTo(1L);
    assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'ussd'"
                + " AND table_name = 'customer_pref' AND column_name = 'msisdn'",
            Long.class))
        .isZero();
    assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM customer_pref WHERE msisdn_hash = ?", Long.class, msisdn))
        .isZero();
  }
}
