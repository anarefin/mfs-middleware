package mfs.mw.adapter.billpay.controller;

import static mfs.mw.adapter.billpay.BillpayFixtures.newTxnId;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import mfs.mw.adapter.billpay.TestInfrastructure;
import mfs.mw.adapter.billpay.contracts.ContractSchemas;
import mfs.mw.adapter.billpay.messaging.KafkaTestConsumer;
import mfs.mw.adapter.billpay.partner.auth.HmacSha256AuthScheme;
import mfs.mw.adapter.billpay.service.PartnerReferenceService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Partner callbacks (ticket 22, spec 11.2 inbound path) over HTTP against real PostgreSQL and
 * Kafka: a callback signed with the aggregator's secret for a reference this adapter issued is
 * published once on {@code mw.partner.inbound.v1} (valid against the contract) before it is
 * acknowledged; a duplicate is acknowledged without a second record; a bad signature, an unknown
 * reference or partner, or an unreadable body is refused and publishes nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInfrastructure.class)
class PartnerCallbackIT {

  static final String SECRET = "fake-billagg-hmac-secret-for-tests"; // src/test/resources/config
  static final String PATH = "/partner/billagg/callback/payments";
  static final String TOPIC = "mw.partner.inbound.v1";
  static final String SCHEMA = "events/mw-orchestrator/mw.partner.inbound.v1.schema.json";
  static final JsonMapper JSON = JsonMapper.builder().build();

  private final HttpClient http = HttpClient.newHttpClient();
  private final PartnerReferenceService references;
  private final KafkaContainer kafka;
  private final String base;

  @Autowired
  PartnerCallbackIT(PartnerReferenceService references, KafkaContainer kafka, Environment env) {
    this.references = references;
    this.kafka = kafka;
    this.base = "http://localhost:" + env.getProperty("local.server.port");
  }

  record Answer(int status, JsonNode body) {}

  Answer post(String path, String body, String secret, long epochSeconds) throws Exception {
    String ts = Long.toString(epochSeconds);
    String sig = HmacSha256AuthScheme.sign(secret, "POST", path, ts, body.getBytes(StandardCharsets.UTF_8));
    HttpResponse<String> r =
        http.send(
            HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .header("X-Signature", sig)
                .header("X-Timestamp", ts)
                .header("X-Correlation-Id", "corr-cb-1")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    return new Answer(r.statusCode(), r.body().isBlank() ? JSON.missingNode() : JSON.readTree(r.body()));
  }

  Answer post(String body) throws Exception {
    return post(PATH, body, SECRET, Instant.now().getEpochSecond());
  }

  static String body(String reference, String status, String code, String paymentRef) {
    return "{\"transactionId\":\"" + reference + "\",\"status\":\"" + status + "\",\"code\":\"" + code
        + "\",\"message\":\"from the aggregator\"" + (paymentRef == null ? "" : ",\"paymentRef\":\"" + paymentRef + "\"")
        + "}";
  }

  List<ConsumerRecord<String, String>> published(String txnId, int atLeast, Duration wait) {
    return KafkaTestConsumer.read(kafka.getBootstrapServers(), TOPIC, atLeast, wait, r -> txnId.equals(r.key()));
  }

  /** A reference this adapter issued for a PAY (as BillPaymentService does before the call). */
  String issued(String txnId) {
    return references.referenceFor(txnId, "billagg");
  }

  @Tag("slow-wait")
  @Test
  void aSignedCallbackIsPublishedOnceAndAcknowledged() throws Exception {
    String txnId = newTxnId();
    String ref = issued(txnId);

    Answer first = post(body(ref, "SUCCESS", "000", "AGG-41"));
    Answer again = post(body(ref, "SUCCESS", "000", "AGG-41"));

    assertThat(first.status()).as(first.toString()).isEqualTo(200);
    assertThat(first.body().path("status").asString()).isEqualTo("ACCEPTED");
    assertThat(first.body().path("duplicate").asBoolean()).isFalse();
    assertThat(again.status()).isEqualTo(200);
    assertThat(again.body().path("duplicate").asBoolean()).isTrue();
    assertThat(again.body().path("inboundId").asString()).isEqualTo(first.body().path("inboundId").asString());

    List<ConsumerRecord<String, String>> got = published(txnId, 2, Duration.ofSeconds(5));
    assertThat(got).hasSize(1);
    JsonNode m = JSON.readTree(got.getFirst().value());
    assertThat(ContractSchemas.schema(SCHEMA).validate(m)).as(m.toString()).isEmpty();
    assertThat(m.path("family").asString()).isEqualTo("billpay");
    assertThat(m.path("txnId").asString()).isEqualTo(txnId);
    assertThat(m.path("partnerId").asString()).isEqualTo("billagg");
    assertThat(m.path("partnerRef").asString()).isEqualTo("AGG-41");
    assertThat(m.path("correlationId").asString()).isEqualTo("corr-cb-1");
    assertThat(m.path("inboundId").asString()).isEqualTo(first.body().path("inboundId").asString());
    assertThat(m.path("payload").path("kind").asString()).isEqualTo("CALLBACK");
    assertThat(m.path("payload").path("outcome").asString()).isEqualTo("SUCCESS");
    assertThat(m.path("payload").path("partnerCode").asString()).isEqualTo("000");
    assertThat(KafkaTestConsumer.headers(got.getFirst())).containsEntry("mw-txn-id", txnId);
  }

  @Test
  void aRefusalCallbackCarriesTheMappedErrorCode() throws Exception {
    String txnId = newTxnId();

    Answer a = post(body(issued(txnId), "FAILED", "3003", null));

    assertThat(a.status()).as(a.toString()).isEqualTo(200);
    JsonNode m = JSON.readTree(published(txnId, 1, Duration.ofSeconds(10)).getFirst().value());
    assertThat(ContractSchemas.schema(SCHEMA).validate(m)).as(m.toString()).isEmpty();
    assertThat(m.path("payload").path("outcome").asString()).isEqualTo("FAILED");
    assertThat(m.path("payload").path("errorCode").asString()).isEqualTo("MW-PTR-BILLAGG-PAYMENT_REFUSED");
    assertThat(m.path("partnerRef").asString()).isEqualTo(issued(txnId)); // no aggregator ref: ours
  }

  @Tag("slow-wait")
  @Test
  void aCallbackWithABadSignatureIsRefusedAndNothingIsPublished() throws Exception {
    String txnId = newTxnId();
    String body = body(issued(txnId), "SUCCESS", "000", "AGG-42");

    Answer wrongKey = post(PATH, body, "not-the-secret", Instant.now().getEpochSecond());
    Answer stale = post(PATH, body, SECRET, Instant.now().minusSeconds(3600).getEpochSecond());

    for (Answer a : List.of(wrongKey, stale)) {
      assertThat(a.status()).as(a.toString()).isEqualTo(401);
      assertThat(a.body().path("code").asString()).isEqualTo("MW-AUTH-001");
      assertThat(a.body().path("correlationId").asString()).isEqualTo("corr-cb-1");
    }
    assertThat(published(txnId, 1, Duration.ofSeconds(3))).isEmpty();
  }

  @Test
  void aCallbackForAReferenceNeverIssuedIsNotFound() throws Exception {
    Answer a = post(body("BITS00000000000000000000000000000000", "SUCCESS", "000", "AGG-43"));

    assertThat(a.status()).as(a.toString()).isEqualTo(404);
    assertThat(a.body().path("code").asString()).isEqualTo("MW-VAL-003");
  }

  /** T7: the partner id in the path is checked by Bean Validation (@Pattern), before the service. */
  @Tag("slow-wait")
  @Test
  void aCallbackToAMalformedPartnerIdIsRefusedAsInvalid() throws Exception {
    String txnId = newTxnId();

    Answer a = post("/partner/Bad_Id/callback/payments", body(issued(txnId), "SUCCESS", "000", "REF-45"), SECRET, Instant.now().getEpochSecond());

    assertThat(a.status()).as(a.toString()).isEqualTo(400);
    assertThat(a.body().path("code").asString()).isEqualTo("MW-VAL-001");
    assertThat(a.body().path("details").get(0).path("field").asString()).isEqualTo("partnerId");
    assertThat(published(txnId, 1, Duration.ofSeconds(3))).isEmpty();
  }

  @Tag("slow-wait")
  @Test
  void aCallbackToAPartnerWithoutConfigOfItsOwnIsNotFound() throws Exception {
    String txnId = newTxnId();
    String path = "/partner/nope/callback/payments";

    Answer a = post(path, body(issued(txnId), "SUCCESS", "000", "AGG-44"), SECRET, Instant.now().getEpochSecond());

    assertThat(a.status()).as(a.toString()).isEqualTo(404);
    assertThat(published(txnId, 1, Duration.ofSeconds(3))).isEmpty();
  }

  @Tag("slow-wait")
  @Test
  void anUnreadableSignedCallbackIsABadRequest() throws Exception {
    String txnId = newTxnId();

    Answer a = post(body(issued(txnId), "MAYBE", "000", null));

    assertThat(a.status()).as(a.toString()).isEqualTo(400);
    assertThat(a.body().path("code").asString()).isEqualTo("MW-VAL-002");
    assertThat(published(txnId, 1, Duration.ofSeconds(3))).isEmpty();
  }
}
