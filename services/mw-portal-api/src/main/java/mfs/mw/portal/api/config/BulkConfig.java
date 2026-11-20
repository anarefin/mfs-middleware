package mfs.mw.portal.api.config;

import mfs.mw.portal.api.service.BulkFileParser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The bulk file parser with the configured row cap (spec 9.4). */
@Configuration(proxyBeanMethods = false)
public class BulkConfig {

  @Bean
  BulkFileParser bulkFileParser(PortalApiProperties props) {
    return new BulkFileParser(props.bulk().maxRows());
  }
}
