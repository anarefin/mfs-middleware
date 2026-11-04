package mfs.mw.adapter.telco.partner.template;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import mfs.mw.adapter.telco.partner.CredentialResolver;
import mfs.mw.adapter.telco.partner.config.PartnerConfig;
import mfs.mw.adapter.telco.partner.config.PartnerConfigStore;
import mfs.mw.adapter.telco.partner.config.PartnerEndpointPolicy;
import mfs.mw.adapter.telco.partner.config.PartnerProperties;
import org.apache.camel.CamelContext;
import org.apache.camel.impl.DefaultCamelContext;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The adapter framework assembled from plain constructors, as the Spring wiring does it, on a
 * Camel context of its own: route templates, config store, wrapper (with a meter registry and an
 * audit sink the test can read), registrar and gateway. Close it after the test.
 */
final class PartnerFramework implements AutoCloseable {

  static final JsonMapper JSON = JsonMapper.builder().build();

  /** Test allowlist: the partners used in these tests may call WireMock on localhost over http. */
  static final PartnerProperties.Security LOCAL =
      new PartnerProperties.Security(
          Map.of(
              "ocs", List.of("localhost"), "gp", List.of("localhost"),
              "acme", List.of("localhost"), "other", List.of("localhost")),
          List.of("localhost"));

  final CamelContext camel = new DefaultCamelContext();
  final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  final List<PartnerAudit.Entry> audits = new CopyOnWriteArrayList<>();
  final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  final PartnerConfigStore store;
  final PartnerGateway gateway;
  final CircuitBreakerRegistry breakers;

  PartnerFramework(
      String defaultPartner,
      Map<String, PartnerConfig> instances,
      CircuitBreakerConfig cb,
      BulkheadConfig bulkhead,
      CredentialResolver credentials,
      Clock clock) {
    PartnerInstanceFactory factory =
        new PartnerInstanceFactory(credentials, clock, JSON, new PartnerEndpointPolicy(LOCAL));
    store = new PartnerConfigStore(new PartnerProperties(defaultPartner, instances, LOCAL), factory, JSON);
    breakers = CircuitBreakerRegistry.of(cb);
    PartnerCallWrapper wrapper =
        new PartnerCallWrapper(
            store, breakers, BulkheadRegistry.of(bulkhead), executor, meters,
            new PartnerAudit(JSON, audits::add));
    try {
      camel.addRoutes(new PartnerRouteTemplates(wrapper));
      camel.start();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    new PartnerRouteRegistrar(camel, store).sync();
    gateway = new PartnerGateway(store, camel.createProducerTemplate());
  }

  PartnerFramework(String defaultPartner, Map<String, PartnerConfig> instances) {
    this(
        defaultPartner, instances, CircuitBreakerConfig.ofDefaults(), BulkheadConfig.ofDefaults(),
        ref -> "fake-secret-for-tests", Clock.systemUTC());
  }

  /** The OCS seed exactly as application.yml defines it (mappings, error map, timeouts). */
  static PartnerConfig ocsSeed() {
    return seed().instances().get("ocs");
  }

  static PartnerProperties seed() {
    try {
      List<PropertySource<?>> sources =
          new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
      MutablePropertySources all = new MutablePropertySources();
      sources.forEach(all::addLast);
      return new Binder(
              ConfigurationPropertySources.from(all), new PropertySourcesPlaceholdersResolver(all))
          .bind("mw.partners", PartnerProperties.class)
          .get();
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void close() {
    camel.stop();
    executor.shutdownNow();
  }
}
