package mfs.mw.adapter.card.partner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import mfs.mw.adapter.card.partner.auth.PartnerCredentials;
import mfs.mw.adapter.card.partner.auth.PartnerTlsContexts;
import mfs.mw.adapter.card.partner.config.PartnerConfig;
import mfs.mw.adapter.card.partner.config.PartnerProperties;
import mfs.mw.adapter.card.partner.config.PartnerTlsProperties;
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
      loader.load("packaged", new ClassPathResource("application.yml")).forEach(environment.getPropertySources()::addLast);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return Binder.get(environment).bind("mw.partners", PartnerProperties.class).get();
  }

  /** The adapter's one dev seed partner (telco {@code ocs}, billpay {@code billagg}, bank {@code c2wbank}). */
  public static Map.Entry<String, PartnerConfig> devSeed() {
    Map<String, PartnerConfig> seeds = properties(Map.of()).instances();
    if (seeds.size() != 1) {
      throw new IllegalStateException("expected one dev seed partner, found " + seeds.keySet());
    }
    return seeds.entrySet().iterator().next();
  }

  /** The dev seed of partner {@code id}. */
  public static PartnerConfig seed(String id) {
    return properties(Map.of()).instances().get(id);
  }
}
