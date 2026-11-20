package mfs.mw.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import mfs.mw.auth.config.AppTypePolicy.Settings;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * The shipped per-app-type session policy (ticket 106, FRS CH-APP-02, CH-APP-03, CH-APP-04,
 * CH-APP-07): access token 15 min, one device and one session, idle 5 min. The customer keeps
 * today's PIN threshold (3 tries, then 1 h). Distributor, DSO, partner, agent and merchant use the
 * default (5 tries, then 24 h).
 */
class AppTypePolicyTest {

  static AppTypePolicy shipped() throws IOException {
    List<PropertySource<?>> sources =
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
    Binder binder =
        new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));
    return binder.bind("mw.auth.app-types", Bindable.of(AppTypePolicy.class)).get();
  }

  @Test
  void theCustomerKeepsTodaysPinThresholdAndTheFifteenMinuteToken() throws IOException {
    Settings c = shipped().of("customer");

    assertThat(c.accessTtl()).isEqualTo(Duration.ofMinutes(15));
    assertThat(c.maxDevices()).isEqualTo(1);
    assertThat(c.maxSessions()).isEqualTo(1);
    assertThat(c.idleTimeout()).isEqualTo(Duration.ofMinutes(5));
    assertThat(c.pinTries()).isEqualTo(3);
    assertThat(c.pinLock()).isEqualTo(Duration.ofHours(1));
  }

  @Test
  void distributorDsoAndPartnerUseTheDefaultLockOfFiveTriesAndADay() throws IOException {
    AppTypePolicy policy = shipped();

    for (String type : List.of("DISTRIBUTOR", "dso", "Partner", "AGENT", "MERCHANT")) {
      Settings s = policy.of(type);
      assertThat(s.accessTtl()).as(type).isEqualTo(Duration.ofMinutes(15));
      assertThat(s.maxDevices()).as(type).isEqualTo(1);
      assertThat(s.maxSessions()).as(type).isEqualTo(1);
      assertThat(s.idleTimeout()).as(type).isEqualTo(Duration.ofMinutes(5));
      assertThat(s.pinTries()).as(type).isEqualTo(5);
      assertThat(s.pinLock()).as(type).isEqualTo(Duration.ofHours(24));
    }
  }
}
