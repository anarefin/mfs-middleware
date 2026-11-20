package mfs.mw.ussd.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import mfs.mw.ussd.config.UssdProperties;
import mfs.mw.ussd.dto.Menu;
import mfs.mw.ussd.dto.UssdEvent;
import mfs.mw.ussd.dto.UssdRequest;
import mfs.mw.ussd.dto.UssdResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * An idle screen ends after the real 30 s per-screen timeout (CH-USSD-05). The session TTL stays
 * the overall timeout plus grace (150 s here), so answering inside each screen still has the 120 s
 * session. Left out of the everyday build ({@code -PwithSlow} runs it).
 */
@Tag("slow-wait")
class ScreenTimeoutIT {

  @Test
  void anIdleScreenEndsAfterThirtySecondsAndTheOverallTimeoutStillApplies() throws Exception {
    UssdEngineTest.InMemorySessions sessions = new UssdEngineTest.InMemorySessions();
    UssdProperties props = UssdEngineTest.props(UssdProperties.Overflow.PAGE, 182);
    Menu menu = UssdEngineTest.MENU;
    MenuCatalog catalog = new MenuCatalog() {
      @Override
      public Menu active(String mno) {
        return menu;
      }

      @Override
      public Menu version(String menuId, int version) {
        return menu;
      }
    };
    UssdEngine engine = new UssdEngine(
        catalog, sessions, new InputValidator(), new ScreenFormatter(), new UssdEngineTest.ScriptedFlow(),
        TestPinTransport.create(), new DynamicLists(props, UssdEngineTest.BILLS),
        new Lookups(UssdEngineTest.BILLS), props, Clock.systemUTC(), new OpenShortCodes(), new MemoryPrefs(),
        new FixedAccounts());

    UssdResponse started = engine.handle(
        new UssdRequest("robi", "slow", UssdEngineTest.CUSTOMER, UssdEvent.BEGIN, "*247#"));
    assertThat(started.end()).isFalse();
    assertThat(sessions.ttls.get("ussd:robi:slow")).isEqualTo(Duration.ofSeconds(150));

    Thread.sleep(30_500);

    UssdResponse next = engine.handle(
        new UssdRequest("robi", "slow", UssdEngineTest.CUSTOMER, UssdEvent.CONTINUE, "1"));
    assertThat(next.end()).isTrue();
    assertThat(next.text()).isEqualTo("সেশন শেষ হয়েছে। আবার ডায়াল করুন।");
    assertThat(sessions.values).doesNotContainKey("ussd:robi:slow");
  }
}
