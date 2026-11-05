package mfs.mw.core.mock.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import mfs.mw.core.mock.TestInfrastructure;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The ledger is seeded on start (spec 6, "Core mock"): 1,000 customers, 50 agents, 20 merchants and
 * 2 orgs, the same wallets and balances every time, so tests and demos can rely on them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class WalletSeederIT {

  private final JdbcTemplate jdbc;
  private final WalletSeeder seeder;
  private final PasswordEncoder encoder;

  @Autowired
  WalletSeederIT(JdbcTemplate jdbc, WalletSeeder seeder, PasswordEncoder encoder) {
    this.jdbc = jdbc;
    this.seeder = seeder;
    this.encoder = encoder;
  }

  private Map<String, Long> countsByType() {
    Map<String, Long> out = new java.util.TreeMap<>();
    jdbc.query(
        "select type, count(*) c from wallet group by type",
        rs -> {
          out.put(rs.getString("type"), rs.getLong("c"));
        });
    return out;
  }

  @Test
  void seedLoadsOnStartWithTheSpecifiedPopulation() {
    seeder.reset();

    assertThat(countsByType())
        .containsEntry("CUSTOMER", 1000L)
        .containsEntry("AGENT", 50L)
        .containsEntry("MERCHANT", 20L)
        .containsEntry("ORG", 2L)
        .containsEntry("SYSTEM", 2L);
  }

  @Test
  void seedIsReproducible() {
    seeder.reset();
    List<String> first = snapshot();
    jdbc.update("update wallet set balance_minor = 1 where id = ?", "01711000001");
    seeder.reset();

    assertThat(snapshot()).isEqualTo(first);
    assertThat(first).contains("01711000001|CUSTOMER|ACTIVE|1000000");
  }

  @Test
  void seededWalletsShareTheFakeSeedPinAsBcryptHash() {
    seeder.reset();
    String hash =
        jdbc.queryForObject("select pin_hash from wallet where id = '01711000001'", String.class);

    assertThat(hash).startsWith("$2");
    assertThat(encoder.matches("1234", hash)).isTrue();
  }

  @Test
  void seedingTwiceDoesNotDuplicate() {
    seeder.seed();
    seeder.seed();

    assertThat(countsByType()).containsEntry("CUSTOMER", 1000L);
  }

  private List<String> snapshot() {
    return jdbc.queryForList(
        "select id || '|' || type || '|' || status || '|' || balance_minor from wallet order by id",
        String.class);
  }
}
