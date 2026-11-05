package mfs.mw.ussd;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Flyway runs on start-up against this service's own schema (spec 16.2, 16.4), from
 * {@code src/main/resources/db/migration}: there is no migration container.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class FlywayOnStartIT {

  private final JdbcTemplate jdbc;

  @Autowired
  FlywayOnStartIT(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Test
  void startUpCreatesTheServiceSchemaAndAppliesMigrations() {
    assertThat(
            jdbc.queryForList(
                "select schema_name from information_schema.schemata where schema_name = 'ussd'",
                String.class))
        .containsExactly("ussd");

    assertThat(
            jdbc.queryForList(
                "select version from ussd.flyway_schema_history where success order by installed_rank",
                String.class))
        .contains("1");
  }

  @Test
  void applicationConnectionsUseTheServiceSchema() {
    assertThat(jdbc.queryForObject("select current_schema()", String.class))
        .isEqualTo("ussd");
  }
}
