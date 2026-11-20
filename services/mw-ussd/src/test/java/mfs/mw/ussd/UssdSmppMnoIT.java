package mfs.mw.ussd;

import static mfs.mw.ussd.Await.await;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import mfs.mw.ussd.client.UssdGatewayStub;
import mfs.mw.ussd.client.UssdGatewayStub.Screen;
import mfs.mw.ussd.dto.SmppUssdMessage;
import mfs.mw.ussd.transport.BanglalinkTlvAdapter;
import mfs.mw.ussd.transport.GpTlvAdapter;
import mfs.mw.ussd.transport.RobiTlvAdapter;
import mfs.mw.ussd.transport.TeletalkTlvAdapter;
import org.jsmpp.bean.BindType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * All four MNOs over SMPP at once (ticket 109a, CH-USSD-01, CH-USSD-03): Grameenphone and
 * Banglalink bind as transceiver, Robi and Teletalk as a transmitter plus a receiver. The first
 * screen is the Bangla main menu, and a paired MNO sends that screen on the transmitter.
 */
@SpringBootTest(properties = {
    "mw.ussd.sync-budget=5s",
    "mw.ussd.instance-id=it-ussd-mno-tlv",
    "mw.ussd.consumers.enabled=true",
    "mw.ussd.smpp.enabled=true",
    "mw.ussd.mno.gp.transport=SMPP",
    "mw.ussd.mno.robi.transport=SMPP",
    "mw.ussd.mno.bl.transport=SMPP",
    "mw.ussd.mno.teletalk.transport=SMPP",
    "mw.ussd.smpp.mno.gp.host=127.0.0.1",
    "mw.ussd.smpp.mno.robi.host=127.0.0.1",
    "mw.ussd.smpp.mno.bl.host=127.0.0.1",
    "mw.ussd.smpp.mno.teletalk.host=127.0.0.1",
    "mw.ussd.smpp.mno.gp.binds=1",
    "mw.ussd.smpp.mno.robi.binds=1",
    "mw.ussd.smpp.mno.bl.binds=1",
    "mw.ussd.smpp.mno.teletalk.binds=1",
    "mw.ussd.smpp.mno.gp.bind-mode=TRANSCEIVER",
    "mw.ussd.smpp.mno.robi.bind-mode=TRANSMITTER_RECEIVER",
    "mw.ussd.smpp.mno.bl.bind-mode=TRANSCEIVER",
    "mw.ussd.smpp.mno.teletalk.bind-mode=TRANSMITTER_RECEIVER"
})
@Import(TestInfrastructure.class)
@DirtiesContext
class UssdSmppMnoIT {

  static final String CUSTOMER = "8801712345678";
  static final UssdGatewayStub GP = stub(new GpTlvAdapter());
  static final UssdGatewayStub ROBI = stub(new RobiTlvAdapter());
  static final UssdGatewayStub BL = stub(new BanglalinkTlvAdapter());
  static final UssdGatewayStub TELETALK = stub(new TeletalkTlvAdapter());

  @DynamicPropertySource
  static void ports(DynamicPropertyRegistry registry) {
    registry.add("mw.ussd.smpp.mno.gp.port", GP::port);
    registry.add("mw.ussd.smpp.mno.robi.port", ROBI::port);
    registry.add("mw.ussd.smpp.mno.bl.port", BL::port);
    registry.add("mw.ussd.smpp.mno.teletalk.port", TELETALK::port);
  }

  @AfterAll
  static void stop() throws IOException {
    GP.close();
    ROBI.close();
    BL.close();
    TELETALK.close();
  }

  @Test
  void everyMnoOpensOnItsOwnBindAndPacking() {
    assertMenu(GP, 0, "*268#", BindType.BIND_TRX);
    assertMenu(BL, 0, "*270#", BindType.BIND_TRX);
    assertMenu(ROBI, receiver(ROBI), "*269#", BindType.BIND_TX);
    assertMenu(TELETALK, receiver(TELETALK), "*271#", BindType.BIND_TX);
  }

  private static void assertMenu(UssdGatewayStub gateway, int dialOn, String code, BindType screenBind) {
    await().until(() -> gateway.boundSessions() >= (screenBind == BindType.BIND_TX ? 2 : 1));
    List<Screen> screens = gateway.dial(dialOn, CUSTOMER, code, List.of());

    assertThat(screens).hasSize(1);
    Screen screen = screens.getFirst();
    assertThat(screen.text()).contains("সেন্ড মানি");
    assertThat(screen.endOfSession()).isFalse();
    assertThat(screen.serviceOp()).isEqualTo(SmppUssdMessage.USSR_REQUEST);
    assertThat(gateway.bindType(screen.bindIndex())).isEqualTo(screenBind);
  }

  /** The receiver of a transmitter+receiver pair, in accept order. */
  private static int receiver(UssdGatewayStub gateway) {
    await().until(() -> gateway.boundSessions() >= 2);
    for (int i = 0; i < gateway.boundSessions(); i++) {
      if (gateway.bindType(i) == BindType.BIND_RX) {
        return i;
      }
    }
    throw new AssertionError("no receiver bind");
  }

  private static UssdGatewayStub stub(mfs.mw.ussd.transport.MnoTlvAdapter adapter) {
    try {
      return new UssdGatewayStub(adapter);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
