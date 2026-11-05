package mfs.mw.admin.api.scheduler;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import mfs.mw.admin.api.config.PartnerSeedProperties;
import mfs.mw.admin.api.dto.PartnerConfigValue;
import mfs.mw.admin.api.service.PartnerConfigService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores the configured partner seeds once at start-up ({@link PartnerSeedProperties}, dev
 * profile only); an existing partner is never touched. A broken seed fails start-up.
 */
@Component
@EnableConfigurationProperties(PartnerSeedProperties.class)
public class PartnerSeedJob implements ApplicationRunner {

  /** One seed file. */
  record Seed(String partnerId, String family, PartnerConfigValue config) {}

  private final PartnerSeedProperties props;
  private final PartnerConfigService partners;
  private final ResourceLoader resources;
  private final JsonMapper json;

  public PartnerSeedJob(
      PartnerSeedProperties props, PartnerConfigService partners, ResourceLoader resources, JsonMapper json) {
    this.props = props;
    this.partners = partners;
    this.resources = resources;
    this.json = json;
  }

  @Override
  public void run(ApplicationArguments args) {
    seedAll();
  }

  /** Stores every configured seed that is missing; returns how many were stored. */
  public int seedAll() {
    int stored = 0;
    for (String location : props.seeds()) {
      if (location == null || location.isBlank()) {
        continue;
      }
      try (InputStream in = resources.getResource(location).getInputStream()) {
        Seed seed = json.readValue(in, Seed.class);
        if (partners.seed(seed.partnerId(), seed.family(), seed.config())) {
          stored++;
        }
      } catch (IOException e) {
        throw new UncheckedIOException("partner seed " + location + " cannot be read", e);
      }
    }
    return stored;
  }
}
