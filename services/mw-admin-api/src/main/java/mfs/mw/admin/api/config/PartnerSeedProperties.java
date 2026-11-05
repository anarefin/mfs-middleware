package mfs.mw.admin.api.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code mw.partners.*} (ticket 38). {@code seeds}: resource locations of JSON files {@code
 * {partnerId, family, config}}, stored at start-up (version 0, never published) when the partner
 * does not exist yet. Set only by the dev profile ({@code application-dev.yml}): they mirror the
 * seed partners the adapters hold in their own dev profile, so the console can change them. The
 * packaged config seeds nothing.
 */
@ConfigurationProperties("mw.partners")
public record PartnerSeedProperties(List<String> seeds) {

  public PartnerSeedProperties {
    seeds = seeds == null ? List.of() : List.copyOf(seeds);
  }
}
