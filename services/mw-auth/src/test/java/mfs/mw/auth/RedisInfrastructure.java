package mfs.mw.auth;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;

/**
 * Redis for tests of sessions and the nonce cache (ticket 68), the platform's image. JVM-scoped
 * like {@link TestInfrastructure}: {@code stop()} is a no-op, Ryuk removes it at JVM exit. Import
 * it next to {@link TestInfrastructure} only where Redis is needed.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RedisInfrastructure {

  static final class JvmScopedRedis extends GenericContainer<JvmScopedRedis> {
    JvmScopedRedis(String image) {
      super(image);
      withExposedPorts(6379);
    }

    @Override
    public void stop() {
      // shared by all contexts; removed at JVM exit
    }
  }

  static final JvmScopedRedis REDIS = new JvmScopedRedis("redis:8.10.3-alpine");

  @Bean(destroyMethod = "")
  @ServiceConnection(name = "redis")
  GenericContainer<?> redis() {
    return REDIS;
  }
}
