package mfs.mw.adapter.interop.partner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import mfs.mw.adapter.interop.partner.auth.PartnerCredentials;
import mfs.mw.adapter.interop.partner.auth.PartnerTlsContexts;
import mfs.mw.adapter.interop.partner.config.PartnerConfig;
import mfs.mw.adapter.interop.partner.config.PartnerProperties;
import mfs.mw.adapter.interop.partner.config.PartnerTlsProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** The partner seeds exactly as application.yml and application-dev.yml define them, for unit tests. */
public final class PartnerTestSeeds {

  private PartnerTestSeeds() {}

  /** No mTLS client contexts: nothing configured, so the credentials are never read. */
  public static PartnerTlsContexts noTlsContexts() {
    return new PartnerTlsContexts(new PartnerTlsProperties(Map.of()), org.mockito.Mockito.mock(PartnerCredentials.class));
  }

  /** {@code mw.partners} of the dev profile, with {@code overrides} (e.g. the WireMock base URL). */
  public static PartnerProperties properties(Map<String, Object> overrides) {
    StandardEnvironment environment = new StandardEnvironment();
    environment.getPropertySources().addFirst(new MapPropertySource("overrides", overrides));
    YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
    try {
      loader.load("dev", new ClassPathResource("application-dev.yml")).forEach(environment.getPropertySources()::addLast);
      // ticket 121a: the dev profile seeds no partner until the npsb seed (121b); the engine tests run on the
      // test-only c2wbank record
      loader.load("test-dev", new ClassPathResource("config/application-dev.yml")).forEach(environment.getPropertySources()::addLast);
      loader.load("packaged", new ClassPathResource("application.yml")).forEach(environment.getPropertySources()::addLast);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return Binder.get(environment).bind("mw.partners", PartnerProperties.class).get();
  }

  /**
   * The engine tests' partner: the test-only REST record {@code c2wbank} (ticket 121b: the dev seed
   * {@code npsb} carries an ISO 20022 document the service layer builds, not a REST call to map).
   */
  public static Map.Entry<String, PartnerConfig> devSeed() {
    PartnerConfig c2wbank = properties(Map.of()).instances().get("c2wbank");
    if (c2wbank == null) {
      throw new IllegalStateException("no test-only c2wbank record in config/application-dev.yml");
    }
    return Map.entry("c2wbank", c2wbank);
  }

  /** The dev seed of partner {@code id}. */
  public static PartnerConfig seed(String id) {
    return properties(Map.of()).instances().get(id);
  }
}
