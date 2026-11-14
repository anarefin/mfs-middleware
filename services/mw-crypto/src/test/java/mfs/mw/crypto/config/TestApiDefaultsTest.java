package mfs.mw.crypto.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * The image's {@code application.yml} (ticket 76): the payShield test API is off and the test-only
 * client {@code mw-e2e-crypto} is bound to nothing unless the environment says so (only {@code
 * deploy/dev.env.defaults} does: {@code MW_CRYPTO_TEST_API_ENABLED}, {@code
 * MW_CRYPTO_AUTHZ_PURPOSES_TPK}, {@code MW_CRYPTO_AUTHZ_PURPOSES_PVK}).
 */
class TestApiDefaultsTest {

  static Binder binder(Map<String, Object> env) throws Exception {
    MutablePropertySources sources = new MutablePropertySources();
    sources.addFirst(new MapPropertySource("env", env));
    List<PropertySource<?>> yaml = new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yml"));
    yaml.forEach(sources::addLast);
    return new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));
  }

  @Test
  void offAndUnboundByDefault() throws Exception {
    Binder b = binder(Map.of());
    TestApiProperties api = b.bindOrCreate("mw.crypto.test-api", TestApiProperties.class);
    assertThat(api.enabled()).isFalse();
    assertThat(api.port()).isEqualTo(8082);
    assertThat(api.client()).isEqualTo("mw-e2e-crypto");
    OperationAuthzProperties authz = b.bindOrCreate("mw.crypto.authz", OperationAuthzProperties.class);
    assertThat(authz.purposes().getOrDefault("TPK", java.util.Set.of())).isEmpty();
    assertThat(authz.purposes().getOrDefault("PVK", java.util.Set.of())).isEmpty();
    assertThat(authz.purposes().getOrDefault("ZPK", java.util.Set.of())).isEmpty();
  }

  @Test
  void theDevProfileBindsTheTestClientToTpkAndPvkOnly() throws Exception {
    Binder b = binder(Map.of("MW_CRYPTO_TEST_API_ENABLED", "true", "MW_CRYPTO_AUTHZ_PURPOSES_TPK", "mw-e2e-crypto",
        "MW_CRYPTO_AUTHZ_PURPOSES_PVK", "mw-e2e-crypto"));
    assertThat(b.bindOrCreate("mw.crypto.test-api", TestApiProperties.class).enabled()).isTrue();
    OperationAuthzProperties authz = b.bindOrCreate("mw.crypto.authz", OperationAuthzProperties.class);
    assertThat(authz.purposes().get("TPK")).containsExactly("mw-e2e-crypto");
    assertThat(authz.purposes().get("PVK")).containsExactly("mw-e2e-crypto");
    assertThat(authz.purposes().getOrDefault("ZPK", java.util.Set.of())).isEmpty();
    assertThat(authz.purposes().get("JWT_SIGN")).containsExactly("mw-auth");
  }
}
