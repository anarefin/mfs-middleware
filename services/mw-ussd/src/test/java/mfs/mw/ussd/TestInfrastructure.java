package mfs.mw.ussd;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import mfs.mw.ussd.util.TestPinKeys;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Real infrastructure for tests that start the application context (spec 22.1): PostgreSQL 18,
 * a single-node KRaft Kafka and Valkey (USSD sessions), the same images as the platform stack, and
 * a PIN key pair generated per run in place of the core's. This module owns its fixtures
 * (no shared test kit). Import it with {@code @Import(TestInfrastructure.class)}. The containers
 * are shared by every test context in the run and stopped by Testcontainers at JVM exit.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestInfrastructure {

  /**
   * The containers live as long as the JVM, not as long as one Spring context: Spring Boot's
   * Testcontainers support stops a container bean when its context closes (whatever the bean's
   * destroyMethod says), and every context in the run shares these two. So {@code stop()} is a
   * no-op here; Testcontainers' Ryuk removes them when the JVM exits.
   */
  static final class JvmScopedPostgres extends PostgreSQLContainer {
    JvmScopedPostgres(String image) {
      super(image);
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

    @Override
    public void stop() {
      // shared by all contexts; removed at JVM exit
    }
  }

  static final class JvmScopedValkey extends GenericContainer<JvmScopedValkey> {
    JvmScopedValkey(String image) {
      super(image);
      withExposedPorts(6379);
    }

    @Override
    public void stop() {
      // shared by all contexts; removed at JVM exit
    }
  }

  public static final JvmScopedValkey VALKEY = new JvmScopedValkey("valkey/valkey:8.1.11-alpine");

  static final PostgreSQLContainer POSTGRES = new JvmScopedPostgres("postgres:18.6-alpine3.24");
  public static final KafkaContainer KAFKA = new JvmScopedKafka("apache/kafka:4.3.2");

  @Bean(destroyMethod = "")
  @ServiceConnection
  PostgreSQLContainer postgres() {
    return POSTGRES;
  }

  @Bean(destroyMethod = "")
  KafkaContainer kafka() {
    return KAFKA;
  }

  /** Valkey for the sessions, and the per-run test PIN key (no key is checked in). */
  @Bean
  DynamicPropertyRegistrar valkeyAndPinKey() {
    return registry -> {
      if (!VALKEY.isRunning()) {
        VALKEY.start();
      }
      registry.add("spring.data.redis.host", VALKEY::getHost);
      registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
      registry.add("mw.ussd.pin.key-id", () -> "core-pin-test");
      registry.add("mw.ussd.pin.public-key", TestPinKeys::pem);
    };
  }

  /** Camel's Kafka component does not read Spring Boot's Kafka connection details. */
  @Bean
  DynamicPropertyRegistrar kafkaBrokers(KafkaContainer kafka) {
    return registry -> registry.add("camel.component.kafka.brokers", kafka::getBootstrapServers);
  }
}
