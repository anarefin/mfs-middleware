package mfs.mw.adapter.billpay.partner;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import mfs.mw.adapter.billpay.partner.config.PartnerConfig;
import mfs.mw.adapter.billpay.partner.config.PartnerProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** The partner seeds exactly as application.yml and application-dev.yml define them, for unit tests. */
public final class PartnerTestSeeds {

  private PartnerTestSeeds() {}

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

  /** The billagg seed. */
  public static PartnerConfig billagg() {
    return properties(Map.of()).instances().get("billagg");
  }
}
