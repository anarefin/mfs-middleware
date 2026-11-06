package mfs.mw.simulators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jsmpp.bean.BindType;
import org.jsmpp.bean.DataCodings;
import org.jsmpp.bean.DeliveryReceipt;
import org.jsmpp.bean.ESMClass;
import org.jsmpp.bean.GSMSpecificFeature;
import org.jsmpp.bean.MessageMode;
import org.jsmpp.bean.MessageType;
import org.jsmpp.bean.NumberingPlanIndicator;
import org.jsmpp.bean.RegisteredDelivery;
import org.jsmpp.bean.SMSCDeliveryReceipt;
import org.jsmpp.bean.TypeOfNumber;
import org.jsmpp.extra.NegativeResponseException;
import org.jsmpp.session.BindParameter;
import org.jsmpp.session.MessageReceiverListener;
import org.jsmpp.session.SMPPSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The SMSC simulator over real SMPP 3.4 (ticket 24): bind, submit_sm, DLRs, control API. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {"sim.smsc.enabled=true",
    "sim.smsc.dlr-delay=50ms"})
@Import(TestInfrastructure.class)
class SmscSimulatorIT {

  static final int SMPP_PORT = freePort();

  @DynamicPropertySource
  static void smpp(DynamicPropertyRegistry r) {
    r.add("sim.smsc.port", () -> SMPP_PORT);
  }

  static int freePort() {
    try (ServerSocket s = new ServerSocket(0)) {
      return s.getLocalPort();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @LocalServerPort int port;

  private final HttpClient http = HttpClient.newHttpClient();
  private final JsonMapper json = JsonMapper.builder().build();
  private final List<DeliveryReceipt> receipts = new CopyOnWriteArrayList<>();
  private SMPPSession session;

  @BeforeEach
  void bind() throws Exception {
    http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/sim/smsc/messages")).DELETE().build(),
        HttpResponse.BodyHandlers.discarding());
    session = new SMPPSession();
    session.setMessageReceiverListener(new ReceiptCollector(receipts));
    session.connectAndBind("127.0.0.1", SMPP_PORT, new BindParameter(BindType.BIND_TRX, "it", "it-pw", "",
        TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, null), 5000);
  }

  @AfterEach
  void unbind() {
    session.unbindAndClose();
  }

  String submit(String dest, byte dataCoding, boolean udhi, byte[] sm) throws Exception {
    return session.submitShortMessage("", TypeOfNumber.ALPHANUMERIC, NumberingPlanIndicator.UNKNOWN, "Bits",
            TypeOfNumber.UNKNOWN, NumberingPlanIndicator.ISDN, dest,
            new ESMClass(MessageMode.DEFAULT, MessageType.DEFAULT, udhi ? GSMSpecificFeature.UDHI : GSMSpecificFeature.DEFAULT),
            (byte) 0, (byte) 0, null, null, new RegisteredDelivery(SMSCDeliveryReceipt.SUCCESS_FAILURE), (byte) 0,
            DataCodings.newInstance(dataCoding), (byte) 0, sm)
        .getMessageId();
  }

  JsonNode messages(String msisdn) throws Exception {
    return json.readTree(http.send(HttpRequest.newBuilder(
            URI.create("http://localhost:" + port + "/sim/smsc/messages?msisdn=" + msisdn)).build(),
        HttpResponse.BodyHandlers.ofString()).body());
  }

  DeliveryReceipt awaitReceipt(String id) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      for (DeliveryReceipt r : receipts) {
        if (r.getId().equals(id)) {
          return r;
        }
      }
      Thread.sleep(50);
    }
    throw new AssertionError("no DLR for " + id);
  }

  @Test
  void aGsm7SubmitIsRecordedAndDeliveredByDlr() throws Exception {
    String id = submit("01711000001", (byte) 0, false, "You have sent Tk 10.00".getBytes(StandardCharsets.US_ASCII));

    assertThat(awaitReceipt(id).getFinalStatus().name()).isEqualTo("DELIVRD");
    JsonNode m = messages("01711000001").get(0);
    assertThat(m.get("text").asString()).isEqualTo("You have sent Tk 10.00");
    assertThat(m.get("encoding").asString()).isEqualTo("GSM7");
    assertThat(m.get("source").asString()).isEqualTo("Bits");
    assertThat(m.get("dlrStatus").asString()).isEqualTo("DELIVRD");
  }

  @Test
  void ucs2PartsAreReassembledAndEachPartGetsAReceipt() throws Exception {
    byte[] a = "আপনি ".getBytes(StandardCharsets.UTF_16BE);
    byte[] b = "পেয়েছেন".getBytes(StandardCharsets.UTF_16BE);
    String id1 = submit("01711000002", (byte) 8, true, concat(new byte[] {5, 0, 3, 9, 2, 1}, a));
    String id2 = submit("01711000002", (byte) 8, true, concat(new byte[] {5, 0, 3, 9, 2, 2}, b));

    awaitReceipt(id1);
    awaitReceipt(id2);
    JsonNode m = messages("01711000002").get(0);
    assertThat(m.get("text").asString()).isEqualTo("আপনি পেয়েছেন");
    assertThat(m.get("segments").asInt()).isEqualTo(2);
  }

  @Test
  void magicNumbersGiveUndeliverableAndThrottling() throws Exception {
    String id = submit("01711000999", (byte) 0, false, new byte[] {'x'});
    assertThat(awaitReceipt(id).getFinalStatus().name()).isEqualTo("UNDELIV");

    assertThatThrownBy(() -> submit("01711000997", (byte) 0, false, new byte[] {'x'}))
        .isInstanceOfSatisfying(NegativeResponseException.class, e -> assertThat(e.getCommandStatus()).isEqualTo(0x58));
  }

  static byte[] concat(byte[] a, byte[] b) {
    byte[] out = new byte[a.length + b.length];
    System.arraycopy(a, 0, out, 0, a.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }

  /** Collects DLRs from deliver_sm. */
  record ReceiptCollector(List<DeliveryReceipt> receipts) implements MessageReceiverListener {
    @Override
    public void onAcceptDeliverSm(org.jsmpp.bean.DeliverSm d) {
      try {
        receipts.add(d.getShortMessageAsDeliveryReceipt());
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }

    @Override
    public void onAcceptAlertNotification(org.jsmpp.bean.AlertNotification a) {
      // not used
    }

    @Override
    public org.jsmpp.session.DataSmResult onAcceptDataSm(org.jsmpp.bean.DataSm d, org.jsmpp.session.Session s) {
      return null;
    }
  }
}
