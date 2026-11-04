package mfs.mw.service.template;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Real infrastructure for tests that start the application context (spec 22.1): PostgreSQL 18 and
 * a single-node KRaft Kafka, the same images as the platform stack. This module owns its fixtures
 * (no shared test kit). Import it with {@code @Import(TestInfrastructure.class)}.
 *
 * <p>The containers are JVM-wide singletons: started together (in parallel) the first time this
 * class is loaded, stopped by Testcontainers at JVM exit. They are deliberately not Spring beans.
 * Spring Boot closes a container bean when its context closes (a {@code @DirtiesContext} test),
 * and a restarted container gets new ports, which breaks every other cached context still
 * connected to the old ones. Contexts get the connection details as properties instead; tests
 * that talk to Kafka directly use {@link #kafkaBootstrapServers()}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestInfrastructure {

  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine3.24");
  static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.2");

  static {
    Startables.deepStart(POSTGRES, KAFKA).join();
  }

  /** The Kafka bootstrap servers, for tests that produce, consume or administer directly. */
  public static String kafkaBootstrapServers() {
    return KAFKA.getBootstrapServers();
  }

  @Bean
  DynamicPropertyRegistrar testInfrastructureProperties() {
    return registry -> {
      registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
      registry.add("spring.datasource.username", POSTGRES::getUsername);
      registry.add("spring.datasource.password", POSTGRES::getPassword);
      // Camel's Kafka component does not read Spring Boot's Kafka connection details.
      registry.add("camel.component.kafka.brokers", KAFKA::getBootstrapServers);
    };
  }
}
