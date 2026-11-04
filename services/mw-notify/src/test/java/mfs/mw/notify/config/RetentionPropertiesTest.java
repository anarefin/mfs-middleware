package mfs.mw.notify.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Retention and purge settings live under one prefix, {@code mw.retention} (spec 6.4, 16.3):
 * 7 days for sent outbox rows and for inbox rows by default.
 */
class RetentionPropertiesTest {

  private static RetentionProperties bind(Map<String, Object> overrides) throws IOException {
    StandardEnvironment env = new StandardEnvironment();
    env.getPropertySources().addFirst(new MapPropertySource("overrides", overrides));
    new YamlPropertySourceLoader()
        .load("application.yml", new ClassPathResource("application.yml"))
        .forEach(env.getPropertySources()::addLast);
    return new Binder(ConfigurationPropertySources.from(env.getPropertySources()))
        .bind("mw.retention", RetentionProperties.class)
        .get();
  }

  @Test
  void defaultsKeepProcessedRowsSevenDaysAndPurgeHourlyInBatches() throws IOException {
    RetentionProperties props = bind(Map.of());

    assertThat(props.outbox()).isEqualTo(Duration.ofDays(7));
    assertThat(props.inbox()).isEqualTo(Duration.ofDays(7));
    assertThat(props.batchSize()).isEqualTo(1000);
    assertThat(props.cron()).isEqualTo("0 0 * * * *");
  }

  @Test
  void everySettingIsOverriddenUnderTheOnePrefix() throws IOException {
    RetentionProperties props =
        bind(
            Map.of(
                "mw.retention.outbox", "3d",
                "mw.retention.inbox", "12h",
                "mw.retention.batch-size", "50",
                "mw.retention.cron", "0 30 2 * * *"));

    assertThat(props.outbox()).isEqualTo(Duration.ofDays(3));
    assertThat(props.inbox()).isEqualTo(Duration.ofHours(12));
    assertThat(props.batchSize()).isEqualTo(50);
    assertThat(props.cron()).isEqualTo("0 30 2 * * *");
  }
}
