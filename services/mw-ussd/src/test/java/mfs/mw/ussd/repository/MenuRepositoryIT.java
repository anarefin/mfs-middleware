package mfs.mw.ussd.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import mfs.mw.ussd.TestInfrastructure;
import mfs.mw.ussd.entity.MenuVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Menu versions and per-MNO go-live against the module's PostgreSQL (spec 9.2). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(TestInfrastructure.class)
class MenuRepositoryIT {

  static final String TREE = "{\"menuId\":\"%s\",\"version\":%d}";

  private final MenuRepository menus;

  @Autowired
  MenuRepositoryIT(MenuRepository menus) {
    this.menus = menus;
  }

  @Test
  void theLiveVersionIsTheLatestActivationWhoseGoLiveHasPassed() {
    String mno = "t" + UUID.randomUUID().toString().substring(0, 8);
    String id = "m-" + mno;
    Instant t0 = Instant.parse("2026-11-01T00:00:00Z");
    menus.insertIfAbsent(id, 1, TREE.formatted(id, 1));
    menus.insertIfAbsent(id, 2, TREE.formatted(id, 2));
    menus.activate(mno, id, 1, t0);
    menus.activate(mno, id, 2, t0.plusSeconds(3600));

    assertThat(menus.active(mno, t0.minusSeconds(1))).isEmpty();
    assertThat(menus.active(mno, t0.plusSeconds(10)).map(MenuVersion::version)).contains(1);
    assertThat(menus.active(mno, t0.plusSeconds(3600)).map(MenuVersion::version)).contains(2);
    // a running session finishes on its own version
    assertThat(menus.find(id, 1).map(MenuVersion::tree)).hasValueSatisfying(t -> assertThat(t).contains("\"version\": 1"));
    assertThat(menus.hasActivation(mno)).isTrue();
  }

  @Test
  void versionsAreImmutable() {
    String id = "m-" + UUID.randomUUID();
    assertThat(menus.insertIfAbsent(id, 1, TREE.formatted(id, 1))).isTrue();
    assertThat(menus.insertIfAbsent(id, 1, "{\"changed\":true}")).isFalse();
    assertThat(menus.find(id, 1).map(MenuVersion::tree)).hasValueSatisfying(t -> assertThat(t).doesNotContain("changed"));
  }

  @Test
  void theSeederMadeTheDefaultMenuLiveForEveryConfiguredMno() {
    for (String mno : new String[] {"gp", "robi", "banglalink", "teletalk"}) {
      assertThat(menus.active(mno, Instant.now()).map(MenuVersion::menuId)).contains("main");
    }
  }
}
