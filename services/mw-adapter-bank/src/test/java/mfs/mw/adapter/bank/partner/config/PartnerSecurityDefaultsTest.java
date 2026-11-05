package mfs.mw.adapter.bank.partner.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * The packaged {@code application.yml} allows no plain-http partner host (ticket 50): only the
 * dev Compose stack ({@code MW_PARTNER_INSECURE_HTTP_HOSTS} in {@code compose.yml}) and the tests
 * allow the simulator host. It also seeds no partner (D23): the simulator seeds live in the dev
 * profile ({@code application-dev.yml}), so a production start has no partners.
 */
class PartnerSecurityDefaultsTest {

  private static final URI SIMULATOR = URI.create("http://mw-simulators:8080");

  @Test
  void thePackagedDefaultAllowsNoPlainHttpHost() throws IOException {
    PartnerProperties.Security security = packagedSecurity(Map.of());

    assertThat(security.insecureHttpHosts()).isEmpty();
    assertThat(policyFor(security).problems(anyInstance(security), SIMULATOR))
        .contains("baseUrl must use https (plain http is not allowed for this host)");
  }

  @Test
  void theDevStackEnablesTheSimulatorHostThroughTheEnvironment() throws IOException {
    PartnerProperties.Security security =
        packagedSecurity(Map.of("MW_PARTNER_INSECURE_HTTP_HOSTS", "mw-simulators"));

    assertThat(security.insecureHttpHosts()).containsExactly("mw-simulators");
    assertThat(policyFor(security).problems(anyInstance(security), SIMULATOR)).isEmpty();
  }

  @Test
  void thePackagedConfigSeedsNoPartner() throws IOException {
    assertThat(packagedPartners(false).instances()).isEmpty();
  }

  @Test
  void theDevProfileSeedsPartnersOnTheSimulatorOnly() throws IOException {
    Map<String, PartnerConfig> seeds = packagedPartners(true).instances();

    assertThat(seeds).isNotEmpty();
    assertThat(seeds.values())
        .allSatisfy(p -> assertThat(p.baseUrl().getHost()).isEqualTo("mw-simulators"));
  }

  private static PartnerProperties packagedPartners(boolean devProfile) throws IOException {
    StandardEnvironment environment = new StandardEnvironment();
    environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
    if (devProfile) {
      loader.load("dev", new ClassPathResource("application-dev.yml"))
          .forEach(environment.getPropertySources()::addLast);
    }
    loader.load("packaged", new ClassPathResource("application.yml"))
        .forEach(environment.getPropertySources()::addLast);
    return Binder.get(environment).bind("mw.partners", PartnerProperties.class).get();
  }

  private static PartnerProperties.Security packagedSecurity(Map<String, Object> env)
      throws IOException {
    StandardEnvironment environment = new StandardEnvironment();
    environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    environment.getPropertySources().addFirst(new MapPropertySource("env", env));
    new YamlPropertySourceLoader()
        .load("packaged", new ClassPathResource("application.yml"))
        .forEach(environment.getPropertySources()::addLast);
    return Binder.get(environment)
        .bind("mw.partners.security", PartnerProperties.Security.class)
        .get();
  }

  private static PartnerEndpointPolicy policyFor(PartnerProperties.Security security) {
    return new PartnerEndpointPolicy(security);
  }

  /** An instance whose allowlist holds the simulator host, so only the scheme rule can fail. */
  private static String anyInstance(PartnerProperties.Security security) {
    return security.allowedHosts().entrySet().stream()
        .filter(e -> e.getValue().contains("mw-simulators"))
        .map(Map.Entry::getKey)
        .findFirst()
        .orElseThrow();
  }
}
