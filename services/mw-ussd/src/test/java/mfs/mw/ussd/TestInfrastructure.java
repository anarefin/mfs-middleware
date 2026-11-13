package mfs.mw.ussd;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import mfs.mw.ussd.util.TestPinKeys;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Real infrastructure for tests that start the application context (spec 22.1): PostgreSQL 18, a
 * single-node KRaft Kafka and Valkey (USSD sessions), the same images as the platform stack, and a
 * PIN key pair generated per run in place of the core's. This module owns its fixtures (no shared
 * test kit). Import it with {@code @Import(TestInfrastructure.class)}; such a test class is a
 * {@code *IT}, which {@link DockerAvailableCondition} skips when Docker is not available.
 *
 * <p>One PostgreSQL, one Kafka and one Valkey per test JVM: JVM-wide singletons, started together
 * the first time a Spring context asks for them, never reused across JVMs (no {@code withReuse},
 * no labels) and removed by Testcontainers' Ryuk when the JVM exits. Nothing cleans up between
 * runs because every run starts on new containers.
 *
 * <p>Per Spring context a fresh database: {@link #testInfrastructureProperties()} runs {@code
 * CREATE DATABASE ctx_<n>} for every context Spring builds (other {@code @MockitoBean}s, other
 * properties, {@code @DirtiesContext}) and points {@code spring.datasource.*} at it, so Flyway
 * migrates an empty database and no context sees another's rows. Kafka and Valkey are shared by all
 * contexts; tests keep apart by their own topic names, keys, consumer groups and session ids.
 *
 * <p>PostgreSQL and Valkey are deliberately not beans: Spring Boot stops a container bean when its
 * context closes, which would stop the shared container under every other cached context. Kafka
 * stays a bean because tests inject {@link KafkaContainer} for its bootstrap servers; its {@code
 * stop()} is a no-op for the same reason.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestInfrastructure {

  /** Kafka as a bean that survives the close of the context that holds it (see class doc). */
  static final class JvmScopedKafka extends KafkaContainer {
    JvmScopedKafka(String image) {
      super(image);
    }

    @Override
    public void stop() {
      // shared by all contexts; Ryuk removes it at JVM exit
    }
  }

  /** Numbers the Spring test contexts; each gets its own database {@code ctx_<n>}. */
  private static final AtomicInteger CONTEXTS = new AtomicInteger();

  /** Every cached context keeps its own Hikari pool open, so the server allows many clients. */
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:18.6-alpine3.24")
          .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=600");

  public static final KafkaContainer KAFKA = new JvmScopedKafka("apache/kafka:4.3.2");

  @SuppressWarnings("resource") // JVM-scoped; Ryuk removes it at JVM exit
  public static final GenericContainer<?> VALKEY =
      new GenericContainer<>("valkey/valkey:8.1.11-alpine").withExposedPorts(6379);

  /** Starts the containers once (in parallel); later calls are no-ops. */
  static synchronized void start() {
    if (!POSTGRES.isRunning() || !KAFKA.isRunning() || !VALKEY.isRunning()) {
      Startables.deepStart(POSTGRES, KAFKA, VALKEY).join();
    }
  }

  /** Creates the next context's empty database and returns its JDBC URL. */
  static String createContextDatabase() {
    start();
    String database = "ctx_" + CONTEXTS.incrementAndGet();
    try (Connection c =
            DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement s = c.createStatement()) {
      s.execute("CREATE DATABASE " + database);
    } catch (SQLException e) {
      throw new IllegalStateException("cannot create test database " + database, e);
    }
    return POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + database + "$1");
  }

  /**
   * Wires this context to its own database, the shared Kafka and Valkey, and the per-run test PIN
   * key (no key is checked in).
   */
  @Bean
  DynamicPropertyRegistrar testInfrastructureProperties() {
    String url = createContextDatabase();
    return registry -> {
      registry.add("spring.datasource.url", () -> url);
      registry.add("spring.datasource.username", POSTGRES::getUsername);
      registry.add("spring.datasource.password", POSTGRES::getPassword);
      // Camel's Kafka component does not read Spring Boot's Kafka connection details.
      registry.add("camel.component.kafka.brokers", KAFKA::getBootstrapServers);
      registry.add("spring.data.redis.host", VALKEY::getHost);
      registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
      registry.add("mw.ussd.pin.key-id", () -> "core-pin-test");
      registry.add("mw.ussd.pin.public-key", TestPinKeys::pem);
    };
  }

  @Bean(destroyMethod = "")
  KafkaContainer kafka() {
    start();
    return KAFKA;
  }
}
