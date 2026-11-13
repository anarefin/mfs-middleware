package mfs.mw.simulators;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jsmpp.GenericNackResponseException;
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
import org.jsmpp.extra.NegativeResponseException;
import org.jsmpp.session.BindParameter;
import org.jsmpp.session.DataSmResult;
import org.jsmpp.session.MessageReceiverListener;
import org.jsmpp.session.SMPPSession;
import org.jsmpp.session.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The USSD gateway simulator in SMPP mode (ticket 27): a test ESME binds as transceiver (playing
 * mw-ussd) and answers each deliver_sm with a screen; the control API plays a whole session and
 * makes the gateway refuse submits (0x58, 0x14, generic_nack) or drop its binds.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {"sim.ussd-gateway.smpp.enabled=true",
    "sim.ussd-gateway.smpp.mnos=gp"})
@Import(TestInfrastructure.class)
class UssdSmppGatewayIT {

  static final int SMPP_PORT = freePort();

  @DynamicPropertySource
  static void smpp(DynamicPropertyRegistry r) {
    r.add("sim.ussd-gateway.smpp.port", () -> SMPP_PORT);
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
  private final List<DeliverSm> received = new CopyOnWriteArrayList<>();
  private final List<Exception> submitErrors = new CopyOnWriteArrayList<>();
  private SMPPSession session;

  @BeforeEach
  void bind() throws Exception {
    post("/sim/ussdgw/smpp/scenario", "{}");
    session = new SMPPSession();
    session.setMessageReceiverListener(new Esme());
    session.connectAndBind("127.0.0.1", SMPP_PORT, new BindParameter(BindType.BIND_TRX, "bits-ussd", "", "",
        TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, null), 5000);
  }

  @AfterEach
  void unbind() {
    session.close();
  }

  static byte[] tlv(OptionalParameter[] params, OptionalParameter.Tag tag) {
    for (OptionalParameter p : params) {
      if (p.tag == tag.code()) {
        byte[] all = p.serialize();
        return Arrays.copyOfRange(all, 4, all.length);
      }
    }
    return null;
  }

  /** Plays mw-ussd: a menu on dial-in, then echoes each answer; "0" ends the session. */
  private final class Esme implements MessageReceiverListener {

    @Override
    public void onAcceptDeliverSm(DeliverSm sm) {
      received.add(sm);
      byte[] info = tlv(sm.getOptionalParameters(), OptionalParameter.Tag.ITS_SESSION_INFO);
      String in = new String(sm.getShortMessage(), StandardCharsets.US_ASCII);
      boolean end = "0".equals(in);
      String screen = in.startsWith("*") ? "Menu\n1 Send" : (end ? "Bye" : "You typed " + in);
      Thread.ofVirtual().start(() -> {
        try {
          session.submitShortMessage("USSD", TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN,
              sm.getDestAddress(), TypeOfNumber.UNKNOWN, NumberingPlanIndicator.ISDN, sm.getSourceAddr(),
              new ESMClass(), (byte) 0, (byte) 0, null, null, new RegisteredDelivery(0), (byte) 0,
              DataCodings.ZERO, (byte) 0, screen.getBytes(StandardCharsets.US_ASCII),
              new OptionalParameter.OctetString(OptionalParameter.Tag.USSD_SERVICE_OP.code(),
                  new byte[] {(byte) (end ? 17 : 2)}),
              new OptionalParameter.OctetString(OptionalParameter.Tag.ITS_SESSION_INFO.code(),
                  new byte[] {info[0], (byte) (end ? 1 : 0)}));
        } catch (Exception e) {
          submitErrors.add(e);
        }
      });
    }

    @Override
    public void onAcceptAlertNotification(AlertNotification a) {}

    @Override
    public DataSmResult onAcceptDataSm(DataSm d, Session s) {
      return null;
    }
  }

  HttpResponse<String> post(String path, String body) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  JsonNode dial(String inputs) throws Exception {
    HttpResponse<String> r = post("/sim/ussdgw/sessions",
        "{\"mno\":\"gp\",\"msisdn\":\"01711000001\",\"serviceCode\":\"*268#\",\"inputs\":[" + inputs + "]}");
    assertThat(r.statusCode()).isEqualTo(200);
    return json.readTree(r.body());
  }

  @Test
  void playsASessionOverSmppWithTheUssdTlvs() throws Exception {
    JsonNode r = dial("\"1\",\"0\"");

    assertThat(r.path("ended").asBoolean()).isTrue();
    JsonNode screens = r.path("screens");
    assertThat(screens).hasSize(3);
    assertThat(screens.get(0).path("message").asString()).isEqualTo("Menu\n1 Send");
    assertThat(screens.get(0).path("flag").asString()).isEqualTo("CON");
    assertThat(screens.get(0).path("status").asInt()).isEqualTo(200);
    assertThat(screens.get(1).path("message").asString()).isEqualTo("You typed 1");
    assertThat(screens.get(2).path("message").asString()).isEqualTo("Bye");
    assertThat(screens.get(2).path("flag").asString()).isEqualTo("END");

    DeliverSm begin = received.getFirst();
    assertThat(begin.getSourceAddr()).isEqualTo("01711000001");
    assertThat(begin.getDestAddress()).isEqualTo("*268#");
    assertThat(tlv(begin.getOptionalParameters(), OptionalParameter.Tag.USSD_SERVICE_OP)).containsExactly(1);
    DeliverSm next = received.get(1);
    assertThat(tlv(next.getOptionalParameters(), OptionalParameter.Tag.USSD_SERVICE_OP)).containsExactly(18);
    assertThat(tlv(next.getOptionalParameters(), OptionalParameter.Tag.ITS_SESSION_INFO)[0])
        .isEqualTo(tlv(begin.getOptionalParameters(), OptionalParameter.Tag.ITS_SESSION_INFO)[0]);
  }

  @Tag("slow-wait")
  @Test
  void refusesTheNextSubmitsAsAsked() throws Exception {
    post("/sim/ussdgw/smpp/scenario", "{\"throttleNext\":1,\"queueFullNext\":1,\"genericNackNext\":1}");

    for (int i = 0; i < 3; i++) {
      dial("");
    }

    assertThat(submitErrors).hasSize(3);
    assertThat(submitErrors).filteredOn(e -> e instanceof GenericNackResponseException).hasSize(1);
    assertThat(submitErrors).filteredOn(e -> e instanceof NegativeResponseException)
        .extracting(e -> ((NegativeResponseException) e).getCommandStatus())
        .containsExactlyInAnyOrder(0x58, 0x14);
    JsonNode binds = json.readTree(http.send(HttpRequest.newBuilder(
        URI.create("http://localhost:" + port + "/sim/ussdgw/smpp/binds")).build(),
        HttpResponse.BodyHandlers.ofString()).body());
    assertThat(binds.path("refused").asInt()).isGreaterThanOrEqualTo(3);
    assertThat(binds.path("bound").asInt()).isEqualTo(1);
  }

  @Test
  void anUnansweredScreenTimesOutAsAnOpenSession() throws Exception {
    post("/sim/ussdgw/smpp/scenario", "{\"throttleNext\":1,\"screenTimeoutMs\":500}");

    JsonNode r = dial("");

    assertThat(r.path("ended").asBoolean()).isFalse();
    assertThat(r.path("screens").get(0).path("status").asInt()).isEqualTo(-1);
  }

  @Test
  void dropClosesTheBinds() throws Exception {
    assertThat(post("/sim/ussdgw/smpp/drop", "").statusCode()).isEqualTo(204);

    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (session.getSessionState().isBound() && System.nanoTime() < deadline) {
      Thread.sleep(50);
    }
    assertThat(session.getSessionState().isBound()).isFalse();
    post("/sim/ussdgw/smpp/scenario", "{\"screenTimeoutMs\":500}");
    JsonNode r = dial("");
    assertThat(r.path("ended").asBoolean()).isFalse();
    assertThat(r.path("screens").get(0).path("status").asInt()).isEqualTo(-1);
  }
}
