package mfs.mw.app.gateway;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;

/**
 * Valkey for tests of sessions and the nonce cache (ticket 29), the platform's image. JVM-scoped
 * like {@link TestInfrastructure}: {@code stop()} is a no-op, Ryuk removes it at JVM exit. Import
 * it next to {@link TestInfrastructure} only where Valkey is needed.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ValkeyInfrastructure {

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

  static final JvmScopedValkey VALKEY = new JvmScopedValkey("valkey/valkey:8.1.11-alpine");

  @Bean(destroyMethod = "")
  @ServiceConnection(name = "redis")
  GenericContainer<?> valkey() {
    return VALKEY;
  }
}
