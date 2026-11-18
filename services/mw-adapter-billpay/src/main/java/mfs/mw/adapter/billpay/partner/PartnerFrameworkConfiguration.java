package mfs.mw.adapter.billpay.partner;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mfs.mw.adapter.billpay.messaging.PartnerAuditPublisher;
import mfs.mw.adapter.billpay.partner.config.PartnerConfigStore;
import mfs.mw.adapter.billpay.partner.route.PartnerAudit;
import mfs.mw.adapter.billpay.partner.route.PartnerHeaderFilterStrategy;
import org.apache.camel.CamelContext;
import mfs.mw.adapter.billpay.partner.auth.SpringVaultPropertiesFunction;
import mfs.mw.adapter.billpay.partner.auth.PartnerTlsContexts;
import org.apache.camel.spring.boot.CamelContextConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.vault.core.VaultTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring wiring of the partner engine (ADR D26; package {@code partner}): the audit sink, the
 * header filter of partner HTTP calls, the vault properties function for partner secrets, the
 * {@code partnerId} tag on Camel's route metrics, and the start of the partner routes once Camel
 * runs.
 */
@Configuration(proxyBeanMethods = false)
public class PartnerFrameworkConfiguration {

  private static final Pattern PARTNER_ROUTE = Pattern.compile("^partner-([a-z0-9][a-z0-9-]*)-v\\d+$");

  @Bean
  PartnerAudit partnerAudit(JsonMapper json, PartnerAuditPublisher publisher) {
    return PartnerAudit.publishing(json, publisher);
  }

  /**
   * Camel's {@code http} and {@code https} components with the checked resolver: partner calls
   * connect only to addresses {@link mfs.mw.adapter.billpay.partner.route.PartnerDnsResolver}
   * resolved and checked (DNS rebinding, security review of ticket 82).
   */
  @Bean("http")
  mfs.mw.adapter.billpay.partner.route.PartnerHttpComponent http(
      mfs.mw.adapter.billpay.partner.config.PartnerEndpointPolicy policy) {
    return new mfs.mw.adapter.billpay.partner.route.PartnerHttpComponent(
        new mfs.mw.adapter.billpay.partner.route.PartnerDnsResolver(policy));
  }

  @Bean("https")
  mfs.mw.adapter.billpay.partner.route.PartnerHttpComponent https(
      mfs.mw.adapter.billpay.partner.config.PartnerEndpointPolicy policy) {
    return new mfs.mw.adapter.billpay.partner.route.PartnerHttpComponent(
        new mfs.mw.adapter.billpay.partner.route.PartnerDnsResolver(policy));
  }

  /**
   * {@code idempotentRepository=#mwSftpFiles} of {@code mw-sftp-source} (ticket 86): the files the
   * batch routes processed, table {@code partner_file_processed} of this service's own schema
   * (Flyway; never created by Camel), key {@code <partnerId>/<name>-<size>-<modified>}.
   */
  @Bean("mwSftpFiles")
  org.apache.camel.processor.idempotent.jdbc.JdbcMessageIdRepository mwSftpFiles(javax.sql.DataSource dataSource) {
    var repository = new org.apache.camel.processor.idempotent.jdbc.JdbcMessageIdRepository(dataSource, "mw-sftp-source");
    repository.setTableName("partner_file_processed");
    repository.setCreateTableIfNotExists(false);
    return repository;
  }

  /** {@code headerFilterStrategy=#mwPartnerHeaderFilter} of {@code mw-http-call}. */
  @Bean("mwPartnerHeaderFilter")
  PartnerHeaderFilterStrategy mwPartnerHeaderFilter() {
    return new PartnerHeaderFilterStrategy();
  }

  /** {@code headerFilterStrategy=#mwSoapHeaderFilter} of {@code mw-soap-call} (ticket 85). */
  @Bean("mwSoapHeaderFilter")
  mfs.mw.adapter.billpay.partner.route.PartnerSoapHeaderFilterStrategy mwSoapHeaderFilter() {
    return new mfs.mw.adapter.billpay.partner.route.PartnerSoapHeaderFilterStrategy();
  }

  /**
   * Partner secrets: Camel's {@code hashicorp} properties function on Spring Cloud Vault's {@code
   * VaultTemplate} (the AppRole login of {@code VAULT_ENABLED=true}), as the function alone only
   * knows a static token. Without Vault the function stays Camel's default and a route that needs
   * a secret is refused. Then the mTLS client contexts are read (ticket 87) and the partner routes
   * start: seeds, then the records that came early.
   */
  @Bean
  CamelContextConfiguration partnerRoutesOnStart(
      ObjectProvider<VaultTemplate> vault, PartnerConfigStore store, PartnerTlsContexts tls) {
    return new CamelContextConfiguration() {
      @Override
      public void beforeApplicationStart(CamelContext camelContext) {
        VaultTemplate template = vault.getIfAvailable();
        if (template != null) {
          SpringVaultPropertiesFunction function = new SpringVaultPropertiesFunction(template);
          function.setCamelContext(camelContext);
          camelContext.getPropertiesComponent().addPropertiesFunction(function);
        }
      }

      @Override
      public void afterApplicationStart(CamelContext camelContext) {
        tls.register(camelContext);
        store.start();
      }
    };
  }

  /** {@code partnerId} on Camel's route metrics of a partner route ({@code partner-<id>-v<n>}). */
  @Bean
  MeterFilter partnerRouteTag() {
    return new MeterFilter() {
      @Override
      public Meter.Id map(Meter.Id id) {
        String routeId = id.getTag("routeId");
        if (routeId == null || id.getTag("partnerId") != null) {
          return id;
        }
        Matcher m = PARTNER_ROUTE.matcher(routeId);
        if (!m.matches()) {
          return id;
        }
        List<Tag> tags = new ArrayList<>(id.getTags());
        tags.add(Tag.of("partnerId", m.group(1)));
        return id.replaceTags(tags);
      }
    };
  }
}
