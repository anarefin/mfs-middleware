package mfs.mw.admin.api.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code mw.billers.*} (ticket 113). {@code seeds}: resource locations of biller catalogue CSV files
 * ({@code scheduler/BillerSeedJob}), stored and published at start-up for every code not in the
 * catalogue yet. Set only by the dev profile ({@code application-dev.yml}); the packaged config
 * seeds nothing.
 */
@ConfigurationProperties("mw.billers")
public record BillerSeedProperties(List<String> seeds) {

  public BillerSeedProperties {
    seeds = seeds == null ? List.of() : List.copyOf(seeds);
  }
}
