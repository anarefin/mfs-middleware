package mfs.mw.app.gateway;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The shared PostgreSQL of {@link TestInfrastructure} without Kafka, for tests that point the
 * service at a broker that is not there.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresOnlyInfrastructure {

  @Bean(destroyMethod = "")
  @ServiceConnection
  PostgreSQLContainer postgres() {
    return TestInfrastructure.POSTGRES;
  }
}
