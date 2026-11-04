package mfs.mw.adapter.bank.partner;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import mfs.mw.adapter.bank.partner.config.PartnerConfigStore;
import mfs.mw.adapter.bank.partner.template.PartnerAudit;
import mfs.mw.adapter.bank.partner.template.PartnerCallWrapper;
import mfs.mw.adapter.bank.partner.template.PartnerGateway;
import mfs.mw.adapter.bank.partner.template.PartnerRouteRegistrar;
import mfs.mw.adapter.bank.partner.template.PartnerRouteTemplates;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.spring.boot.CamelContextConfiguration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring wiring of the adapter framework (spec 12; package {@code partner}, copied into each
 * adapter service, not a library). The framework classes are plain constructors so a test can
 * assemble them without Spring.
 */
@Configuration(proxyBeanMethods = false)
public class PartnerFrameworkConfiguration {

  /** Partner calls run here, under the time limiter, never on a Kafka consumer thread. */
  @Bean(name = "partnerCallExecutor", destroyMethod = "close")
  ExecutorService partnerCallExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  @Bean
  PartnerAudit partnerAudit(JsonMapper json) {
    return PartnerAudit.logging(json);
  }

  @Bean
  PartnerCallWrapper partnerCallWrapper(
      PartnerConfigStore store,
      CircuitBreakerRegistry breakers,
      BulkheadRegistry bulkheads,
      @Qualifier("partnerCallExecutor") ExecutorService executor,
      MeterRegistry meters,
      PartnerAudit audit) {
    return new PartnerCallWrapper(store, breakers, bulkheads, executor, meters, audit);
  }

  @Bean
  PartnerRouteTemplates partnerRouteTemplates(PartnerCallWrapper wrapper) {
    return new PartnerRouteTemplates(wrapper);
  }

  @Bean
  PartnerRouteRegistrar partnerRouteRegistrar(CamelContext camel, PartnerConfigStore store) {
    return new PartnerRouteRegistrar(camel, store);
  }

  /** Creates the partner routes once the templates exist (after Camel has started). */
  @Bean
  CamelContextConfiguration partnerRoutesOnStart(PartnerRouteRegistrar registrar) {
    return new CamelContextConfiguration() {
      @Override
      public void beforeApplicationStart(CamelContext camelContext) {
        // templates are added with the other routes
      }

      @Override
      public void afterApplicationStart(CamelContext camelContext) {
        registrar.sync();
      }
    };
  }

  @Bean
  PartnerGateway partnerGateway(PartnerConfigStore store, ProducerTemplate producer) {
    return new PartnerGateway(store, producer);
  }
}
