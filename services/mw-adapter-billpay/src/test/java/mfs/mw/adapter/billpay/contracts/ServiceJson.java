package mfs.mw.adapter.billpay.contracts;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@link JsonMapper} the running service injects into {@code OutboxWriter}, {@code
 * EventPublisher} and the listeners, built by hand: Spring Boot 4.1's {@code JacksonAutoConfiguration}
 * defaults (dates and durations as ISO strings, unknown properties ignored, no default view
 * inclusion, modules found on the classpath). {@code application.yml} sets no {@code
 * spring.jackson.*} property, so these are all of it.
 */
public final class ServiceJson {

  public static final JsonMapper MAPPER =
      JsonMapper.builder()
          .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS, DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .disable(MapperFeature.DEFAULT_VIEW_INCLUSION)
          .findAndAddModules()
          .build();

  private ServiceJson() {}
}
