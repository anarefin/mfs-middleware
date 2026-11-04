package mfs.mw.portal.api.config;

import mfs.mw.portal.api.service.BulkCsvParser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The bulk file parser with the configured row cap (spec 9.4). */
@Configuration(proxyBeanMethods = false)
public class BulkConfig {

  @Bean
  BulkCsvParser bulkCsvParser(PortalApiProperties props) {
    return new BulkCsvParser(props.bulk().maxRows());
  }
}
