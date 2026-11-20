package mfs.mw.simulators.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.CopyOnWriteArrayList;
import mfs.mw.simulators.config.SimulatorProperties;
import mfs.mw.simulators.config.UssdSmppGatewayProperties;
import mfs.mw.simulators.config.UssdSmppGatewayProperties.Peer;
import mfs.mw.simulators.dto.MockBehaviour;
import mfs.mw.simulators.dto.MockScenario;
import mfs.mw.simulators.dto.UssdDialRequest;
import mfs.mw.simulators.dto.UssdDialResult;
import mfs.mw.simulators.service.mockcontrol.Journal;
import mfs.mw.simulators.service.mockcontrol.PartySwitch;
import mfs.mw.simulators.service.mockcontrol.ScenarioEngine;
import org.jsmpp.bean.AlertNotification;
import org.jsmpp.bean.BindType;
import org.jsmpp.bean.DataCodings;
import org.jsmpp.bean.DataSm;
import org.jsmpp.bean.DeliverSm;
import org.jsmpp.bean.ESMClass;
import org.jsmpp.bean.NumberingPlanIndicator;
import org.jsmpp.bean.OptionalParameter;
import org.jsmpp.bean.RegisteredDelivery;
import org.jsmpp.bean.TypeOfNumber;
import org.jsmpp.session.BindParameter;
import org.jsmpp.session.DataSmResult;
import org.jsmpp.session.MessageReceiverListener;
import org.jsmpp.session.SMPPSession;
import org.jsmpp.session.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * One SMPP port per MNO (ticket 109a): Grameenphone and Banglalink pack {@code its_session_info}
 * with the end bit in bit 0, Robi and Teletalk in bit 7. A bind fault on one party does not
 * refuse the others. Grameenphone still honours a scenario posted on the legacy party {@code ussdgw}.
 */
class UssdSmppPortsTest {

  final int gpPort = freePort();
  final int robiPort = freePort();
  final int blPort = freePort();
  final int teletalkPort = freePort();
  final ScenarioEngine engine =
      new ScenarioEngine(new PartySwitch(), new SimulatorProperties(Duration.ofMillis(700), 10), new SplittableRandom(5));
  final UssdSmppGateway gateway = new UssdSmppGateway(
      new UssdSmppGatewayProperties(true, gpPort, List.of("gp", "robi", "bl", "teletalk"), Duration.ofSeconds(3),
          "ussd-gp", "EOS_BIT0", true, Map.of(
              "robi", new Peer(robiPort, "ussd-robi", "EOS_BIT7", true),
              "bl", new Peer(blPort, "ussd-bl", "EOS_BIT0", true),
              "teletalk", new Peer(teletalkPort, "ussd-teletalk", "EOS_BIT7", true))),
      engine, new StaticListableBeanFactory().getBeanProvider(Journal.class), Clock.systemUTC());

  @AfterEach
  void stop() {
    gateway.stop();
  }

  @Test
  void eachPortPacksItsOwnSessionInfoAndFaultsStayOnTheirParty() throws Exception {
    gateway.start();
    try (Handset bl = Handset.transceiver(blPort); Handset teletalk = Handset.pair(teletalkPort);
        Handset gp = Handset.transceiver(gpPort)) {
      UssdDialResult blPlay = gateway.play(dial("bl", "*270#"));
      UssdDialResult teletalkPlay = gateway.play(dial("teletalk", "*271#"));

      assertThat(blPlay.screens()).allSatisfy(s -> assertThat(s.status()).isEqualTo(200));
      assertThat(teletalkPlay.screens()).allSatisfy(s -> assertThat(s.status()).isEqualTo(200));
      // Sequence 0 then 2. Bit 0 shifts the sequence up; bit 7 leaves it in the low seven bits.
      assertThat(bl.flags()).containsExactly((byte) 0x00, (byte) 0x04);
      assertThat(teletalk.flags()).containsExactly((byte) 0x00, (byte) 0x02);
      assertThat(gp.session.getSessionState().isBound()).isTrue();
      assertThat(gateway.binds().bound()).isEqualTo(1);

      engine.add("ussdgw", MockScenario.of("bind", MockBehaviour.BUSINESS_ERROR).withCode("0x0F"));
      engine.add("ussd-robi", MockScenario.of("bind", MockBehaviour.BUSINESS_ERROR).withCode("0x0D"));
      assertThatThrownBy(() -> Handset.transceiver(gpPort)).isInstanceOf(IOException.class)
          .hasMessageContaining("0000000f");
      assertThatThrownBy(() -> Handset.transceiver(robiPort)).isInstanceOf(IOException.class)
          .hasMessageContaining("0000000d");
      try (Handset again = Handset.transceiver(blPort)) {
        assertThat(again.session.getSessionState().isBound()).isTrue();
      }
    }
  }

  private static UssdDialRequest dial(String mno, String code) {
    return new UssdDialRequest(mno, "01711000001", code, null, List.of("1"), null);
  }

  private static int freePort() {
    try (ServerSocket s = new ServerSocket(0)) {
      return s.getLocalPort();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** An ESME that answers each deliver_sm by echoing its session info. */
  static final class Handset implements MessageReceiverListener, AutoCloseable {

    final SMPPSession session = new SMPPSession();
    SMPPSession transmitter;
    final List<Byte> flags = new CopyOnWriteArrayList<>();

    static Handset transceiver(int port) throws Exception {
      Handset h = new Handset();
      try {
        h.session.setMessageReceiverListener(h);
        h.session.connectAndBind("127.0.0.1", port, bind(BindType.BIND_TRX), 5000);
        return h;
      } catch (Exception e) {
        h.close();
        throw e;
      }
    }

    static Handset pair(int port) throws Exception {
      Handset h = new Handset();
      h.session.setMessageReceiverListener(h);
      h.session.connectAndBind("127.0.0.1", port, bind(BindType.BIND_RX), 5000);
      h.transmitter = new SMPPSession();
      h.transmitter.connectAndBind("127.0.0.1", port, bind(BindType.BIND_TX), 5000);
      return h;
    }

    List<Byte> flags() {
      return flags;
    }

    private static BindParameter bind(BindType type) {
      return new BindParameter(type, "bits-ussd", "", "", TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, null);
    }

    @Override
    public void onAcceptDeliverSm(DeliverSm sm) {
      byte[] info = tlv(sm.getOptionalParameters());
      flags.add(info[1]);
      byte[] echo = Arrays.copyOf(info, info.length);
      SMPPSession sender = transmitter != null ? transmitter : session;
      Thread.ofVirtual().start(() -> {
        try {
          sender.submitShortMessage("USSD", TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, sm.getDestAddress(),
              TypeOfNumber.UNKNOWN, NumberingPlanIndicator.ISDN, sm.getSourceAddr(), new ESMClass(), (byte) 0, (byte) 0,
              null, null, new RegisteredDelivery(0), (byte) 0, DataCodings.ZERO, (byte) 0,
              "ok".getBytes(StandardCharsets.US_ASCII),
              new OptionalParameter.OctetString(OptionalParameter.Tag.USSD_SERVICE_OP.code(), new byte[] {2}),
              new OptionalParameter.OctetString(OptionalParameter.Tag.ITS_SESSION_INFO.code(), echo));
        } catch (Exception e) {
          flags.add((byte) -1);
        }
      });
    }

    private static byte[] tlv(OptionalParameter[] params) {
      for (OptionalParameter p : params) {
        if (p.tag == OptionalParameter.Tag.ITS_SESSION_INFO.code()) {
          byte[] all = p.serialize();
          return Arrays.copyOfRange(all, 4, all.length);
        }
      }
      return new byte[] {0, 0};
    }

    @Override
    public void onAcceptAlertNotification(AlertNotification a) {}

    @Override
    public DataSmResult onAcceptDataSm(DataSm d, Session s) {
      return null;
    }

    @Override
    public void close() {
      session.close();
      if (transmitter != null) {
        transmitter.close();
      }
    }
  }
}
