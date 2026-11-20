package mfs.mw.simulators.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import mfs.mw.simulators.client.NpsbInboundClient;
import mfs.mw.simulators.config.SimulatorProperties;
import mfs.mw.simulators.dto.MockBehaviour;
import mfs.mw.simulators.dto.MockScenario;
import mfs.mw.simulators.dto.SimReply;
import mfs.mw.simulators.service.mockcontrol.PartySwitch;
import mfs.mw.simulators.service.mockcontrol.ScenarioEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Ticket 121b part 2: the stateful NPSB switch mock and its data triggers; every document it writes is
 * valid against the official ISO XSD (copies in src/test/resources/iso20022, from mw-adapter-interop).
 */
class NpsbServiceTest {

  static final OffsetDateTime AT = OffsetDateTime.of(2026, 11, 20, 9, 0, 0, 0, ZoneOffset.UTC);

  final AtomicReference<Instant> now = new AtomicReference<>(AT.toInstant());
  final Clock clock = new Clock() {
    @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    @Override public Instant instant() { return now.get(); }
  };
  final List<String> pushed = new CopyOnWriteArrayList<>();
  HttpServer middleware;
  ScenarioEngine engine;
  NpsbService service;

  @BeforeEach
  void setUp() throws Exception {
    middleware = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    middleware.createContext("/partner/v1/npsb/inbound", exchange -> {
      pushed.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] answer = "<ack/>".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, answer.length);
      exchange.getResponseBody().write(answer);
      exchange.close();
    });
    middleware.start();
    engine = new ScenarioEngine(new PartySwitch(), new SimulatorProperties(Duration.ofMillis(10), 50), new SplittableRandom(1));
    service = new NpsbService(engine, new NpsbInboundClient(), new NpsbSigner(""), clock, Duration.ofSeconds(5), Duration.ofMillis(10),
        "http://127.0.0.1:" + middleware.getAddress().getPort() + "/partner/v1/npsb/inbound");
  }

  @AfterEach
  void tearDown() {
    middleware.stop(0);
  }

  @Test
  void aTransferIsSettledAcscOnceAndARepeatAnswersTheSameState() throws Exception {
    String pacs008 = pacs008("E2E0001", "FAKEBDD1", "1234567890123");

    SimReply first = service.transfer(pacs008);
    SimReply again = service.transfer(pacs008);

    assertThat(first.httpStatus()).isEqualTo(200);
    String doc = document(first);
    assertValid("pacs.002.001.10", doc);
    assertThat(doc).contains("<TxSts>ACSC</TxSts>").contains("<OrgnlEndToEndId>E2E0001</OrgnlEndToEndId>")
        .contains("<ClrSysRef>NPSB-CLR-1</ClrSysRef>");
    assertThat(document(again)).contains("<ClrSysRef>NPSB-CLR-1</ClrSysRef>");
  }

  @Test
  void dataTriggersRejectWithTheirIsoReasonCodes() throws Exception {
    assertThat(document(service.transfer(pacs008("E2E0002", "FAKEBDD1", "1234567890991"))))
        .contains("<TxSts>RJCT</TxSts>").contains("<Cd>AC01</Cd>");
    assertThat(document(service.transfer(pacs008("E2E0003", "FAKEBDD1", "1234567890992"))))
        .contains("<Cd>AM04</Cd>");
    assertThat(document(service.transfer(pacs008("E2E0004", "FAKEBDX9", "1234567890123"))))
        .contains("<Cd>RC01</Cd>");
    assertThat(document(service.transfer(pacs008("E2E0005", "NOSUCHXX", "1234567890123"))))
        .contains("<Cd>RC01</Cd>");
  }

  @Test
  void aPendingTransferSettlesAfterTheDelayAndThePacs028AnswersTheStoredState() throws Exception {
    assertThat(document(service.transfer(pacs008("E2E0006", "FAKEBDD1", "1234567890993"))))
        .contains("<TxSts>PDNG</TxSts>");
    assertThat(document(service.status(pacs028("E2E0006")))).contains("<TxSts>PDNG</TxSts>");

    now.set(now.get().plusSeconds(6));

    assertThat(document(service.status(pacs028("E2E0006")))).contains("<TxSts>ACSC</TxSts>");
    assertThat(service.status(pacs028("UNKNOWN0001")).httpStatus()).isEqualTo(404);
  }

  @Test
  void aSettledTransferIsReturnedOnlyOnce() throws Exception {
    service.transfer(pacs008("E2E0007", "FAKEBDD1", "1234567890123"));

    String first = document(service.returnTransfer(camt056("E2E0007")));
    String second = document(service.returnTransfer(camt056("E2E0007")));

    assertValid("camt.029.001.09", first);
    assertThat(first).contains("<Conf>CNCL</Conf>");
    assertThat(second).contains("<Conf>RJCR</Conf>");
    assertThat(document(service.returnTransfer(camt056("E2E9999")))).contains("<Conf>RJCR</Conf>");
  }

  @Test
  void aBusinessErrorScenarioRejectsWithItsCode() throws Exception {
    engine.add(NpsbService.PARTNER, MockScenario.of("transfer", MockBehaviour.BUSINESS_ERROR).withCode("AG01"));

    assertThat(document(service.transfer(pacs008("E2E0008", "FAKEBDD1", "1234567890123"))))
        .contains("<TxSts>RJCT</TxSts>").contains("<Cd>AG01</Cd>");
  }

  @Test
  void theInboundPushPostsAValidPacs008AndADuplicateScenarioSendsItTwice() throws Exception {
    NpsbService.InboundResult once = service.inbound(new NpsbService.InboundPush("01711000001", 250_000L, null, null, null, null));
    assertThat(once.answers()).hasSize(1);
    assertThat(once.answers().getFirst().status()).isEqualTo(200);
    assertThat(pushed).hasSize(1);
    assertValid("pacs.008.001.08", pushed.getFirst());
    assertThat(pushed.getFirst()).contains("<EndToEndId>" + once.endToEndId() + "</EndToEndId>")
        .contains("<BICFI>BITSBDDH</BICFI>");

    engine.add(NpsbService.PARTNER, MockScenario.of("inbound", MockBehaviour.DUPLICATE_CALLBACK));
    NpsbService.InboundResult twice = service.inbound(new NpsbService.InboundPush("01711000001", 1_000L, "E2EDUP1", null, null, null));
    assertThat(twice.answers()).hasSize(2);
    assertThat(pushed).hasSize(3);
  }

  @Test
  void withTheDevSwitchKeyEveryAnswerAndPushIsSigned() throws Exception {
    java.security.KeyPair keys = NpsbSignerTest.rsa();
    NpsbService signing = new NpsbService(engine, new NpsbInboundClient(), new NpsbSigner(NpsbSignerTest.pkcs8Pem(keys)),
        clock, Duration.ofSeconds(5), Duration.ofMillis(10),
        "http://127.0.0.1:" + middleware.getAddress().getPort() + "/partner/v1/npsb/inbound");

    String pacs002 = document(signing.transfer(pacs008("E2E0009", "FAKEBDD1", "1234567890123")));
    String camt029 = document(signing.returnTransfer(camt056("E2E0009")));
    signing.inbound(new NpsbService.InboundPush("01711000001", 1_000L, "E2ESIG1", null, null, null));

    for (String doc : List.of(pacs002, camt029, pushed.getLast())) {
      assertThat(verifies(doc, keys.getPublic())).as(doc).isTrue();
    }
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private static boolean verifies(String xml, java.security.PublicKey key) throws Exception {
    var f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
    f.setNamespaceAware(true);
    var doc = f.newDocumentBuilder().parse(new org.xml.sax.InputSource(new StringReader(xml)));
    var sig = doc.getElementsByTagNameNS(javax.xml.crypto.dsig.XMLSignature.XMLNS, "Signature").item(0);
    if (sig == null) {
      return false;
    }
    var ctx = new javax.xml.crypto.dsig.dom.DOMValidateContext(key, sig);
    return javax.xml.crypto.dsig.XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(ctx).validate(ctx);
  }

  private String pacs008(String e2e, String creditorAgent, String account) {
    return NpsbIsoXml.pacs008("M" + e2e, AT, e2e, "T" + e2e, 150_050, "Rahim Uddin", "01712345678", "BITSBDDH",
        "Karim Ahmed", account, creditorAgent);
  }

  private static String pacs028(String e2e) {
    return """
        <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.028.001.03"><FIToFIPmtStsReq><GrpHdr><MsgId>Q%s</MsgId>
        <CreDtTm>2026-11-20T09:00:00Z</CreDtTm></GrpHdr><TxInf><OrgnlEndToEndId>%s</OrgnlEndToEndId>
        <OrgnlTxId>%s</OrgnlTxId></TxInf></FIToFIPmtStsReq></Document>""".formatted(e2e, e2e, e2e);
  }

  private static String camt056(String e2e) {
    return """
        <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.056.001.08"><FIToFIPmtCxlReq><Assgnmt><Id>R%s</Id>
        </Assgnmt><Undrlyg><TxInf><OrgnlEndToEndId>%s</OrgnlEndToEndId><OrgnlTxId>%s</OrgnlTxId></TxInf>
        </Undrlyg></FIToFIPmtCxlReq></Document>""".formatted(e2e, e2e, e2e);
  }

  private static String document(SimReply reply) {
    return (String) ((Map<?, ?>) reply.body()).get("document");
  }

  private static void assertValid(String message, String xml) throws Exception {
    SchemaFactory f = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
    var schema = f.newSchema(NpsbServiceTest.class.getClassLoader().getResource("iso20022/" + message + ".xsd"));
    schema.newValidator().validate(new StreamSource(new StringReader(xml)));
  }
}
