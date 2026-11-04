package mfs.mw.adapter.telco.partner.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Seed partner config from application config ({@code mw.partners}), in force until a {@code
 * mw.config.changed.v1} record replaces it (seeds have version 0, so any event wins).
 *
 * @param defaultPartner instance that serves a partnerId without config of its own (none: such a
 *     call is refused unsent)
 * @param instances partner instance id to config
 */
@ConfigurationProperties("mw.partners")
public record PartnerProperties(String defaultPartner, Map<String, PartnerConfig> instances) {

  public PartnerProperties {
    instances = instances == null ? Map.of() : Map.copyOf(instances);
  }
}
