package mfs.mw.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The reused test containers (ticket 54): one PostgreSQL and one Kafka per module, labelled with
 * the module, shared by the integrationTest and conformance JVMs, each JVM on its own database.
 * No Spring context: the JDBC URL checked here is the one every context gets.
 */
class TestInfrastructureIT {

  @Test
  void integrationTestsRunOnTheirOwnDatabase() throws SQLException {
    assertThat(currentDatabase()).isEqualTo("orchestrator_it");
  }

  @Test
  @Tag("conformance")
  void conformanceTestsRunOnTheirOwnDatabase() throws SQLException {
    assertThat(currentDatabase()).isEqualTo("orchestrator_conf");
  }

  @Test
  @Tag("conformance")
  void eachTaskStartsOnAnEmptyDatabase() throws SQLException {
    // Flyway has not run for a plain JDBC client; a leftover table would mean a reused database.
    try (Connection c = connect();
        Statement s = c.createStatement();
        ResultSet rs =
            s.executeQuery(
                "select count(*) from information_schema.tables where table_name = 'tc_leftover'")) {
      rs.next();
      assertThat(rs.getInt(1)).isZero();
      s.execute("create table tc_leftover (id int)");
    }
  }

  @Test
  void containersCarryTheModuleLabelSoModulesNeverShareThem() {
    assertThat(TestInfrastructure.POSTGRES.getLabels()).containsEntry("mw.module", "mw-orchestrator");
    assertThat(TestInfrastructure.KAFKA.getLabels()).containsEntry("mw.module", "mw-orchestrator");
    assertThat(TestInfrastructure.POSTGRES.isShouldBeReused()).isTrue();
    assertThat(TestInfrastructure.KAFKA.isShouldBeReused()).isTrue();
  }

  private static Connection connect() throws SQLException {
    return DriverManager.getConnection(
        TestInfrastructure.POSTGRES.getJdbcUrl(),
        TestInfrastructure.POSTGRES.getUsername(),
        TestInfrastructure.POSTGRES.getPassword());
  }

  private static String currentDatabase() throws SQLException {
    try (Connection c = connect();
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("select current_database()")) {
      rs.next();
      return rs.getString(1);
    }
  }
}
