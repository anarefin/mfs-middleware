package mfs.mw.seeder.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import mfs.mw.seeder.client.HttpJson;
import mfs.mw.seeder.scheduler.RunnerSettings;
import mfs.mw.seeder.scheduler.SeedRunner;
import mfs.mw.seeder.service.SeedStep;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** The seeder's beans: JSON, HTTP and the runner over every {@link SeedStep} in {@code @Order}. */
@Configuration(proxyBeanMethods = false)
public class SeederConfig {

  /** Null fields left out of requests (the contracts forbid nulls); unknown answer fields ignored. */
  public static JsonMapper jsonMapper() {
    return JsonMapper.builder()
        .changeDefaultPropertyInclusion(i -> i.withValueInclusion(JsonInclude.Include.NON_NULL))
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();
  }

  @Bean
  public JsonMapper seederJsonMapper() {
    return jsonMapper();
  }

  @Bean
  public HttpJson httpJson(SeederProperties props, JsonMapper json) {
    return new HttpJson(props.http().connectTimeout(), props.http().readTimeout(), json);
  }

  @Bean
  public SeedRunner seedRunner(List<SeedStep> steps, SeederProperties props) {
    return new SeedRunner(
        steps, new RunnerSettings(props.stepTimeout(), props.stepTimeouts(), props.initialBackoff(), props.maxBackoff()));
  }
}
