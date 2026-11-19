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
import org.jsmpp.bean.AlertNotification;
import org.jsmpp.bean.BindType;
import org.jsmpp.bean.DataCodings;
import org.jsmpp.bean.DataSm;
import org.jsmpp.bean.DeliverSm;
import org.jsmpp.bean.DeliveryReceipt;
import org.jsmpp.bean.ESMClass;
import org.jsmpp.bean.NumberingPlanIndicator;
import org.jsmpp.bean.OptionalParameter;
import org.jsmpp.bean.RegisteredDelivery;
import org.jsmpp.bean.SMSCDeliveryReceipt;
import org.jsmpp.bean.TypeOfNumber;
import org.jsmpp.extra.NegativeResponseException;
import org.jsmpp.extra.ResponseTimeoutException;
import org.jsmpp.session.BindParameter;
import org.jsmpp.session.DataSmResult;
import org.jsmpp.session.MessageReceiverListener;
import org.jsmpp.session.SMPPSession;
import org.jsmpp.session.Session;
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

/**
 * The SMPP mock parties on the mock-control engine (ticket 102b, ADR D33, FRS MCT-04): the SMSC ({@code smsc})
 * and the USSD gateway ({@code ussdgw}) refuse a bind, answer ESME_RTHROTTLED, leave out or delay the DLR
 * and drop enquire_link, as {@code /mock-control/{party}} scenarios and switches say.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {"sim.smsc.enabled=true",
    "sim.smsc.dlr-delay=50ms", "sim.ussd-gateway.smpp.enabled=true", "sim.ussd-gateway.smpp.mnos=gp"})
@Import(TestInfrastructure.class)
class SmppMockControlIT {

  static final int SMSC_PORT = freePort();
  static final int USSD_PORT = freePort();

  @DynamicPropertySource
  static void smpp(DynamicPropertyRegistry r) {
    r.add("sim.smsc.port", () -> SMSC_PORT);
    r.add("sim.ussd-gateway.smpp.port", () -> USSD_PORT);
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
  private final List<SMPPSession> sessions = new CopyOnWriteArrayList<>();

  @BeforeEach
  @AfterEach
  void reset() throws Exception {
    for (String party : List.of("smsc", "ussdgw")) {
      assertThat(send("DELETE", "/mock-control/" + party + "/journal", null).statusCode()).isEqualTo(204);
    }
  }

  @AfterEach
  void close() {
    sessions.forEach(SMPPSession::close);
  }

  HttpResponse<String> send(String method, String path, String body) throws Exception {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
    if (body == null) {
      b.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
    }
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  void scenario(String party, String body) throws Exception {
    HttpResponse<String> r = send("POST", "/mock-control/" + party + "/scenarios", body);
    assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
  }

  /** An ESME that can send enquire_link on demand (jSMPP keeps it protected). */
  static final class Esme extends SMPPSession {
    void enquireLink() throws Exception {
      sendEnquireLink();
    }
  }

  Esme bind(int smppPort, String systemId, MessageReceiverListener listener) throws IOException {
    Esme s = new Esme();
    sessions.add(s);
    s.setMessageReceiverListener(listener);
    s.connectAndBind("127.0.0.1", smppPort, new BindParameter(BindType.BIND_TRX, systemId, "pw", "",
        TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN, null), 5000);
    return s;
  }

  String submit(SMPPSession s, String dest) throws Exception {
    return s.submitShortMessage("", TypeOfNumber.ALPHANUMERIC, NumberingPlanIndicator.UNKNOWN, "Bits",
            TypeOfNumber.UNKNOWN, NumberingPlanIndicator.ISDN, dest, new ESMClass(), (byte) 0, (byte) 0, null, null,
            new RegisteredDelivery(SMSCDeliveryReceipt.SUCCESS_FAILURE), (byte) 0, DataCodings.ZERO, (byte) 0,
            "hello".getBytes(StandardCharsets.US_ASCII))
        .getMessageId();
  }

  List<DeliveryReceipt> receiptsFor(String id, Duration within) throws InterruptedException {
    long deadline = System.nanoTime() + within.toNanos();
    while (System.nanoTime() < deadline) {
      Thread.sleep(50);
    }
    return receipts.stream().filter(r -> r.getId().equals(id)).toList();
  }

  // ---------- SMSC ----------

  @Test
  void theSmscRefusesABindAsTheScenarioSays() throws Exception {
    scenario("smsc", "{\"operation\":\"bind\",\"behaviour\":\"BUSINESS_ERROR\",\"key\":\"bad-esme\"}");

    assertThatThrownBy(() -> bind(SMSC_PORT, "bad-esme", new Receipts()))
        .hasRootCauseInstanceOf(NegativeResponseException.class)
        .rootCause().satisfies(e -> assertThat(((NegativeResponseException) e).getCommandStatus()).isEqualTo(0x0D));
    assertThat(bind(SMSC_PORT, "good-esme", new Receipts()).getSessionState().isBound()).isTrue();
  }

  @Test
  void aDownSmscClosesTheConnectionUnanswered() throws Exception {
    assertThat(send("PUT", "/mock-control/smsc/switch", "{\"state\":\"DOWN\"}").statusCode()).isEqualTo(200);

    assertThatThrownBy(() -> bind(SMSC_PORT, "esme", new Receipts())).isInstanceOf(IOException.class);
  }

  @Test
  void theSmscThrottlesTheNextSubmitsWithEsmeRthrottled() throws Exception {
    SMPPSession s = bind(SMSC_PORT, "esme", new Receipts());
    scenario("smsc", "{\"operation\":\"submit\",\"behaviour\":\"BUSINESS_ERROR\",\"code\":\"0x58\",\"nextN\":1}");

    assertThatThrownBy(() -> submit(s, "01711000001"))
        .isInstanceOfSatisfying(NegativeResponseException.class, e -> assertThat(e.getCommandStatus()).isEqualTo(0x58));
    assertThat(submit(s, "01711000001")).startsWith("SMSC-");
  }

  @Test
  void theSmscLeavesOutTheDlrOrSendsItLateOrTwice() throws Exception {
    SMPPSession s = bind(SMSC_PORT, "esme", new Receipts());
    scenario("smsc", "{\"operation\":\"submit\",\"behaviour\":\"NO_CALLBACK\",\"key\":\"01711000011\"}");
    scenario("smsc", "{\"operation\":\"submit\",\"behaviour\":\"LATE_CALLBACK\",\"delayMs\":1000,\"key\":\"01711000012\"}");
    scenario("smsc", "{\"operation\":\"submit\",\"behaviour\":\"DUPLICATE_CALLBACK\",\"key\":\"01711000013\"}");

    String none = submit(s, "01711000011");
    String late = submit(s, "01711000012");
    String twice = submit(s, "01711000013");
    String normal = submit(s, "01711000014");

    assertThat(receiptsFor(late, Duration.ofMillis(600))).isEmpty();
    assertThat(receiptsFor(none, Duration.ofMillis(800))).isEmpty();
    assertThat(receipts.stream().filter(r -> r.getId().equals(late))).hasSize(1);
    assertThat(receipts.stream().filter(r -> r.getId().equals(twice))).hasSize(2);
    assertThat(receipts.stream().filter(r -> r.getId().equals(normal))).hasSize(1);
  }

  @Test
  void theSmscJournalRecordsBindsAndSubmitsWithoutTheText() throws Exception {
    submit(bind(SMSC_PORT, "esme", new Receipts()), "01711000021");

    JsonNode j = json.readTree(send("GET", "/mock-control/smsc/journal", null).body());
    assertThat(j.findValuesAsString("path")).containsExactly("bind", "submit_sm");
    assertThat(j.get(1).path("headers").path("destination").asString()).isEqualTo("01711000021");
    assertThat(j.toString()).doesNotContain("hello");
  }

  // ---------- USSD gateway ----------

  @Test
  void theUssdGatewayRefusesABindAsTheScenarioSays() throws Exception {
    scenario("ussdgw", "{\"operation\":\"bind\",\"behaviour\":\"BUSINESS_ERROR\",\"code\":\"0x0F\"}");

    assertThatThrownBy(() -> bind(USSD_PORT, "bits-ussd", new Screens()))
        .hasRootCauseInstanceOf(NegativeResponseException.class)
        .rootCause().satisfies(e -> assertThat(((NegativeResponseException) e).getCommandStatus()).isEqualTo(0x0F));
  }

  @Test
  void theUssdGatewayThrottlesAScreenWithEsmeRthrottled() throws Exception {
    Screens esme = new Screens();
    esme.session = bind(USSD_PORT, "bits-ussd", esme);
    scenario("ussdgw", "{\"operation\":\"submit\",\"behaviour\":\"BUSINESS_ERROR\",\"code\":\"0x58\",\"nextN\":1}");
    assertThat(send("PUT", "/mock-control/ussdgw/settings", "{\"timeoutDelayMs\":2000}").statusCode()).isEqualTo(200);

    JsonNode first = dial();
    JsonNode second = dial();

    assertThat(esme.errors).singleElement()
        .isInstanceOfSatisfying(NegativeResponseException.class, e -> assertThat(e.getCommandStatus()).isEqualTo(0x58));
    assertThat(first.path("screens").get(0).path("status").asInt()).isEqualTo(-1);
    assertThat(second.path("screens").get(0).path("message").asString()).isEqualTo("Menu");
    assertThat(json.readTree(send("GET", "/sim/ussdgw/smpp/binds", null).body()).path("refused").asInt()).isPositive();
  }

  @Test
  void theUssdGatewayDropsEnquireLinkAsTheScenarioSays() throws Exception {
    Screens esme = new Screens();
    esme.session = bind(USSD_PORT, "bits-ussd", esme);
    esme.session.setTransactionTimer(500);
    scenario("ussdgw", "{\"operation\":\"enquire_link\",\"behaviour\":\"TIMEOUT\",\"nextN\":1}");

    assertThatThrownBy(() -> esme.session.enquireLink()).isInstanceOf(ResponseTimeoutException.class);
    esme.session.enquireLink();
  }

  @Test
  void aDownUssdGatewayPlaysNothing() throws Exception {
    assertThat(send("PUT", "/mock-control/ussdgw/switch", "{\"state\":\"DOWN\"}").statusCode()).isEqualTo(200);

    JsonNode r = dial();

    assertThat(r.path("screens").get(0).path("status").asInt()).isEqualTo(-1);
    assertThat(r.path("ended").asBoolean()).isFalse();
  }

  JsonNode dial() throws Exception {
    HttpResponse<String> r = send("POST", "/sim/ussdgw/sessions",
        "{\"mno\":\"gp\",\"msisdn\":\"01711000001\",\"serviceCode\":\"*268#\",\"inputs\":[]}");
    assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
    return json.readTree(r.body());
  }

  /** Collects DLRs. */
  final class Receipts implements MessageReceiverListener {
    @Override
    public void onAcceptDeliverSm(DeliverSm d) {
      try {
        receipts.add(d.getShortMessageAsDeliveryReceipt());
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }

    @Override
    public void onAcceptAlertNotification(AlertNotification a) {}

    @Override
    public DataSmResult onAcceptDataSm(DataSm d, Session s) {
      return null;
    }
  }

  /** Plays mw-ussd: answers every dial-in with the screen "Menu" (END), recording refused submits. */
  static final class Screens implements MessageReceiverListener {
    volatile Esme session;
    final List<Exception> errors = new CopyOnWriteArrayList<>();

    @Override
    public void onAcceptDeliverSm(DeliverSm sm) {
      byte[] info = null;
      for (OptionalParameter p : sm.getOptionalParameters()) {
        if (p.tag == OptionalParameter.Tag.ITS_SESSION_INFO.code()) {
          byte[] all = p.serialize();
          info = new byte[] {all[4], all[5]};
        }
      }
      byte number = info == null ? 0 : info[0];
      Thread.ofVirtual().start(() -> {
        try {
          session.submitShortMessage("USSD", TypeOfNumber.UNKNOWN, NumberingPlanIndicator.UNKNOWN,
              sm.getDestAddress(), TypeOfNumber.UNKNOWN, NumberingPlanIndicator.ISDN, sm.getSourceAddr(),
              new ESMClass(), (byte) 0, (byte) 0, null, null, new RegisteredDelivery(0), (byte) 0,
              DataCodings.ZERO, (byte) 0, "Menu".getBytes(StandardCharsets.US_ASCII),
              new OptionalParameter.OctetString(OptionalParameter.Tag.USSD_SERVICE_OP.code(), new byte[] {17}),
              new OptionalParameter.OctetString(OptionalParameter.Tag.ITS_SESSION_INFO.code(), new byte[] {number, 1}));
        } catch (Exception e) {
          errors.add(e);
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
}
