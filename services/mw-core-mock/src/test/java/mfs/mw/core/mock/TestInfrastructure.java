package mfs.mw.core.mock;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Real infrastructure for tests that start the application context (spec 22.1): PostgreSQL 18 and
 * a single-node KRaft Kafka, the same images as the platform stack. This module owns its fixtures
 * (no shared test kit). Import it with {@code @Import(TestInfrastructure.class)}. The containers
 * are shared by every test context in the run and stopped by Testcontainers at JVM exit.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestInfrastructure {

  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine3.24");
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.2");

  @Bean(destroyMethod = "")
  @ServiceConnection
  PostgreSQLContainer postgres() {
    return POSTGRES;
  }

  @Bean(destroyMethod = "")
  KafkaContainer kafka() {
    return KAFKA;
  }

  /** Throwaway PIN key pair for this JVM: no key material is committed. */
  public static final java.security.KeyPair PIN_KEYS = generate();

  /** The service's key id in tests ({@code mw.coremock.pin.key-id}, set in test application.yml). */
  public static final String PIN_KEY_ID = "core-pin-test-1";

  private static java.security.KeyPair generate() {
    try {
      java.security.KeyPairGenerator g = java.security.KeyPairGenerator.getInstance("RSA");
      g.initialize(2048);
      return g.generateKeyPair();
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  @Bean
  DynamicPropertyRegistrar pinKey() {
    return registry ->
        registry.add(
            "mw.coremock.pin.private-key",
            () -> java.util.Base64.getEncoder().encodeToString(PIN_KEYS.getPrivate().getEncoded()));
  }

  /** Camel's Kafka component does not read Spring Boot's Kafka connection details. */
  @Bean
  DynamicPropertyRegistrar kafkaBrokers(KafkaContainer kafka) {
    return registry -> registry.add("camel.component.kafka.brokers", kafka::getBootstrapServers);
  }
}
