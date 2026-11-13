package mfs.mw.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/**
 * The test containers (TestInfrastructure): one PostgreSQL and one Kafka per test JVM, never
 * reused, and an empty database of its own for every Spring context. No Spring context here: the
 * database a context would get is created directly.
 */
class TestInfrastructureIT {

  @Test
  void containersAreThrowawayNeverReused() {
    TestInfrastructure.start();
    assertThat(TestInfrastructure.POSTGRES.isShouldBeReused()).isFalse();
    assertThat(TestInfrastructure.KAFKA.isShouldBeReused()).isFalse();
    assertThat(TestInfrastructure.POSTGRES.isRunning()).isTrue();
    assertThat(TestInfrastructure.KAFKA.isRunning()).isTrue();
  }

  @Test
  void everyContextGetsItsOwnEmptyDatabase() throws SQLException {
    String first = TestInfrastructure.createContextDatabase();
    String second = TestInfrastructure.createContextDatabase();
    assertThat(first).isNotEqualTo(second);
    assertThat(currentDatabase(first)).startsWith("ctx_");
    try (Connection c = connect(first);
        Statement s = c.createStatement()) {
      s.execute("create table tc_leftover (id int)");
    }
    try (Connection c = connect(second);
        Statement s = c.createStatement();
        ResultSet rs =
            s.executeQuery(
                "select count(*) from information_schema.tables where table_name = 'tc_leftover'")) {
      rs.next();
      assertThat(rs.getInt(1)).isZero();
    }
  }

  private static Connection connect(String url) throws SQLException {
    return DriverManager.getConnection(
        url, TestInfrastructure.POSTGRES.getUsername(), TestInfrastructure.POSTGRES.getPassword());
  }

  private static String currentDatabase(String url) throws SQLException {
    try (Connection c = connect(url);
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("select current_database()")) {
      rs.next();
      return rs.getString(1);
    }
  }
}
