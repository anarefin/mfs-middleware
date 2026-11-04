package mfs.mw.orchestrator.config;

import mfs.mw.orchestrator.util.SearchHash;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The keyed search hash of {@code initiator_hash} (ticket 35). */
@Configuration(proxyBeanMethods = false)
public class SearchConfig {

  @Bean
  public SearchHash searchHash(SearchProperties properties) {
    return new SearchHash(properties.hashKey());
  }
}
