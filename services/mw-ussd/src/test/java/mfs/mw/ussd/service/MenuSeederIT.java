package mfs.mw.ussd.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import mfs.mw.ussd.TestInfrastructure;
import mfs.mw.ussd.config.UssdProperties;
import mfs.mw.ussd.entity.MenuVersion;
import mfs.mw.ussd.repository.MenuRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

/**
 * The dev seed supersedes an older version of itself (a stack started with main v1 gets main v2),
 * but never replaces another menu an MNO was given.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class MenuSeederIT {

  private final MenuRepository menus;
  private final UssdProperties props;
  private final JsonMapper json;

  @Autowired
  MenuSeederIT(MenuRepository menus, UssdProperties props, JsonMapper json) {
    this.menus = menus;
    this.props = props;
    this.json = json;
  }

  MenuSeeder seederFor(String mno) {
    UssdProperties one =
        new UssdProperties(
            props.instanceId(), props.requestTopic(), props.statusTopic(), props.syncBudget(), props.sessionGrace(),
            props.defaultLanguage(), props.maxInvalidAttempts(), props.pin(), Map.of(mno, props.mno().get("robi")),
            props.lists());
    return new MenuSeeder(menus, one, json, Clock.systemUTC(), "menus/main-v2.json");
  }

  @Test
  void anOlderVersionOfTheSeedIsSuperseded() {
    String mno = "t" + UUID.randomUUID().toString().substring(0, 8);
    menus.insertIfAbsent("main", 1, "{\"menuId\":\"main\",\"version\":1}");
    menus.activate(mno, "main", 1, Instant.parse("2026-11-01T00:00:00Z"));

    seederFor(mno).run(null);

    assertThat(menus.active(mno, Instant.now()).map(MenuVersion::version)).contains(2);
  }

  @Test
  void anotherMenuIsLeftAlone() {
    String mno = "t" + UUID.randomUUID().toString().substring(0, 8);
    String other = "custom-" + mno;
    menus.insertIfAbsent(other, 1, "{\"menuId\":\"" + other + "\",\"version\":1}");
    menus.activate(mno, other, 1, Instant.parse("2026-11-01T00:00:00Z"));

    seederFor(mno).run(null);

    assertThat(menus.active(mno, Instant.now()).map(MenuVersion::menuId)).contains(other);
  }
}
