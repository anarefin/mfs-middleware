package mfs.mw.adapter.telco;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.GroupListing;
import org.apache.kafka.clients.admin.ListGroupsOptions;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Real infrastructure for tests that start the application context (spec 22.1): PostgreSQL 18 and
 * a single-node KRaft Kafka, the same images as the platform stack. This module owns its fixtures
 * (no shared test kit). Import it with {@code @Import(TestInfrastructure.class)}. The containers
 * are shared by every test context in the run and stopped by Testcontainers at JVM exit.
 *
 * <p>Reuse (ticket 54): with {@code TESTCONTAINERS_REUSE_ENABLE=true} (set by {@code make verify}
 * and {@code make verify-changed}) the containers outlive the JVM, so the {@code integrationTest}
 * and {@code conformance} JVMs share one PostgreSQL and one Kafka. They are labelled {@code
 * mw.module} (this module) and {@code mw.root} (the checkout), so modules and worktrees never share
 * them. Each test JVM still starts on clean infrastructure: it works in its own database (system
 * property {@code mw.tc.database}, e.g. {@code <svc>_it} / {@code <svc>_conf}, set by the Gradle
 * test task), dropped and created afresh at start, and it deletes every topic and consumer group a
 * previous JVM left behind. Without reuse enabled the containers are throwaway, as before.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestInfrastructure {

  /** This module, the label that keeps its reused containers apart from other modules'. */
  static final String MODULE = System.getProperty("mw.module", "mw-adapter-telco");

  /**
   * The containers live as long as the JVM, not as long as one Spring context: Spring Boot's
   * Testcontainers support stops a container bean when its context closes (whatever the bean's
   * destroyMethod says), and every context in the run shares these two. So {@code stop()} is a
   * no-op here; Testcontainers' Ryuk removes them when the JVM exits.
   */
  static final class JvmScopedPostgres extends PostgreSQLContainer {
    private volatile String database;

    JvmScopedPostgres(String image) {
      super(image);
    }

    /** Drops and creates {@code name}, then points every later JDBC URL at it. */
    void useFreshDatabase(String name) {
      if (!name.matches("[a-z][a-z0-9_]{0,62}")) {
        throw new IllegalArgumentException("not a plain database name: " + name);
      }
      try (Connection c = DriverManager.getConnection(super.getJdbcUrl(), getUsername(), getPassword());
          Statement s = c.createStatement()) {
        s.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
        s.execute("CREATE DATABASE " + name);
      } catch (SQLException e) {
        throw new IllegalStateException("cannot create test database " + name, e);
      }
      database = name;
    }

    @Override
    public String getDatabaseName() {
      return database == null ? super.getDatabaseName() : database;
    }

    @Override
    public String getJdbcUrl() {
      String url = super.getJdbcUrl();
      return database == null
          ? url
          : url.replaceFirst("/" + super.getDatabaseName() + "(?=\\?|$)", "/" + database);
    }

    @Override
    public void stop() {
      // shared by all contexts; removed at JVM exit
    }
  }

  static final class JvmScopedKafka extends KafkaContainer {
    JvmScopedKafka(String image) {
      super(image);
    }

    /** Deletes every topic and consumer group, so this JVM starts on an empty broker. */
    void deleteTopicsAndGroups() {
      try (Admin admin =
          Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, getBootstrapServers()))) {
        var groups =
            admin.listGroups(ListGroupsOptions.forConsumerGroups()).all().get().stream()
                .map(GroupListing::groupId)
                .toList();
        if (!groups.isEmpty()) {
          admin.deleteConsumerGroups(groups).all().get();
        }
        Set<String> topics = admin.listTopics().names().get();
        if (!topics.isEmpty()) {
          admin.deleteTopics(topics).all().get();
        }
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (!admin.listTopics().names().get().isEmpty()) {
          if (Instant.now().isAfter(deadline)) {
            throw new IllegalStateException("topics of an earlier test run are still being deleted");
          }
          Thread.sleep(100);
        }
      } catch (ExecutionException e) {
        throw new IllegalStateException("cannot clean the reused Kafka broker", e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }

    @Override
    public void stop() {
      // shared by all contexts; removed at JVM exit
    }
  }

  static final JvmScopedPostgres POSTGRES =
      reusable(new JvmScopedPostgres("postgres:18.6-alpine3.24"));
  static final JvmScopedKafka KAFKA = reusable(new JvmScopedKafka("apache/kafka:4.3.2"));

  static {
    Startables.deepStart(POSTGRES, KAFKA).join();
    String database = System.getProperty("mw.tc.database");
    if (database != null && !database.isBlank()) {
      POSTGRES.useFreshDatabase(database);
    }
    KAFKA.deleteTopicsAndGroups();
  }

  private static <T extends org.testcontainers.containers.GenericContainer<?>> T reusable(T container) {
    container.withReuse(true);
    container.withLabel("mw.module", MODULE);
    container.withLabel("mw.root", repoRoot());
    return container;
  }

  /** The checkout this run belongs to: the parent of {@code mwContractsDir} (set by Gradle). */
  private static String repoRoot() {
    String contracts = System.getProperty("mwContractsDir");
    if (contracts == null) {
      return System.getProperty("user.dir");
    }
    return java.nio.file.Path.of(contracts).toAbsolutePath().normalize().getParent().toString();
  }

  @Bean(destroyMethod = "")
  @ServiceConnection
  PostgreSQLContainer postgres() {
    return POSTGRES;
  }

  @Bean(destroyMethod = "")
  KafkaContainer kafka() {
    return KAFKA;
  }

  /** Camel's Kafka component does not read Spring Boot's Kafka connection details. */
  @Bean
  DynamicPropertyRegistrar kafkaBrokers(KafkaContainer kafka) {
    return registry -> registry.add("camel.component.kafka.brokers", kafka::getBootstrapServers);
  }
}
