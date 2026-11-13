package mfs.mw.app.gateway;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * The shared PostgreSQL of {@link TestInfrastructure} without Kafka, for tests that point the
 * service at a broker that is not there. The context still gets its own empty database {@code
 * ctx_<n>}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresOnlyInfrastructure {

  @Bean
  DynamicPropertyRegistrar postgresOnlyProperties() {
    return TestInfrastructure.contextDatabase();
  }
}
