package mfs.mw.adapter.telco;

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

  /** Camel's Kafka component does not read Spring Boot's Kafka connection details. */
  @Bean
  DynamicPropertyRegistrar kafkaBrokers(KafkaContainer kafka) {
    return registry -> registry.add("camel.component.kafka.brokers", kafka::getBootstrapServers);
  }
}
