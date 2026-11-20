package mfs.mw.adapter.card.controller;

import static mfs.mw.adapter.card.CardFixtures.newTxnId;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import mfs.mw.adapter.card.TestInfrastructure;
import mfs.mw.adapter.card.contracts.ContractSchemas;
import mfs.mw.adapter.card.messaging.KafkaTestConsumer;
import mfs.mw.adapter.card.partner.auth.HmacSha256AuthScheme;
import mfs.mw.adapter.card.service.PartnerReferenceService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The 3-D Secure webhook of the acquirer (ticket 118b, FRS UC-13, PLT-014) over HTTP against real
 * PostgreSQL and Kafka: a webhook signed with cybersource's secret for a reference this adapter issued
 * is published once on {@code mw.partner.inbound.v1} (valid against the contract, carrying the session id
 * and transient token the capture needs, never card data) before it is acknowledged; a duplicate (T7) is
 * acknowledged without a second record; a bad signature, an unknown reference or partner, or an
 * unreadable body is refused and publishes nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestInfrastructure.class, mfs.mw.adapter.card.CardTestTopics.class})
class PartnerCallbackIT {

  /** The fake vault's value for secret/mw-adapter-card/cybersource#hmac-secret (partner/FakeVaultFunction). */
  static final String SECRET = "fake-cybersource-hmac-secret-for-tests";
  static final String PATH = "/partner/cybersource/callback/3ds";
  static final String TOPIC = "mw.partner.inbound.v1";
  static final String SCHEMA = "events/mw-orchestrator/mw.partner.inbound.v1.schema.json";
  static final JsonMapper JSON = JsonMapper.builder().build();

  private final HttpClient http = HttpClient.newHttpClient();
  private final PartnerReferenceService references;
  private final String base;

  @Autowired
  PartnerCallbackIT(PartnerReferenceService references, Environment env) {
    this.references = references;
    this.base = "http://localhost:" + env.getProperty("local.server.port");
  }

  record Answer(int status, JsonNode body) {}

  Answer post(String path, String body, String secret, long epochSeconds) throws Exception {
    String ts = Long.toString(epochSeconds);
    String nonce = java.util.UUID.randomUUID().toString();
    String sig = HmacSha256AuthScheme.sign(secret, "POST", path, ts, nonce, body.getBytes(StandardCharsets.UTF_8));
    HttpResponse<String> r =
        http.send(
            HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .header("X-Signature", sig)
                .header("X-Timestamp", ts)
                .header("X-Nonce", nonce)
                .header("X-Correlation-Id", "corr-cb-1")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    return new Answer(r.statusCode(), r.body().isBlank() ? JSON.missingNode() : JSON.readTree(r.body()));
  }

  Answer post(String body) throws Exception {
    return post(PATH, body, SECRET, Instant.now().getEpochSecond());
  }

  /** The webhook as mw-simulators' CyberSourceMock sends it after the hosted page. */
  static String webhook(String reference, String sessionId, String status, String code) {
    return "{\"clientReference\":\"" + reference + "\",\"sessionId\":\"" + sessionId
        + "\",\"transientToken\":\"tok-fake-" + sessionId + "\",\"status\":\"" + status + "\",\"code\":\"" + code + "\"}";
  }

  List<ConsumerRecord<String, String>> published(String txnId, int atLeast, Duration wait) {
    return KafkaTestConsumer.read(TestInfrastructure.kafkaBootstrapServers(), TOPIC, atLeast, wait, r -> txnId.equals(r.key()));
  }

  /** A reference this adapter issued for the session (as CardPartnerService does before CREATE_SESSION). */
  String issued(String txnId) {
    return references.referenceFor(txnId, "cybersource");
  }

  @Tag("slow-wait")
  @Test
  void anAuthenticatedWebhookIsPublishedOnceAndADuplicateOnlyAcknowledged() throws Exception {
    String txnId = newTxnId();
    String ref = issued(txnId);

    Answer first = post(webhook(ref, "sess-41", "AUTHENTICATED", "100"));
    Answer again = post(webhook(ref, "sess-41", "AUTHENTICATED", "100"));

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
    assertThat(m.path("family").asString()).isEqualTo("card");
    assertThat(m.path("txnId").asString()).isEqualTo(txnId);
    assertThat(m.path("partnerId").asString()).isEqualTo("cybersource");
    assertThat(m.path("partnerRef").asString()).isEqualTo("sess-41");
    assertThat(m.path("correlationId").asString()).isEqualTo("corr-cb-1");
    assertThat(m.path("payload").path("kind").asString()).isEqualTo("CALLBACK");
    assertThat(m.path("payload").path("outcome").asString()).isEqualTo("SUCCESS");
    assertThat(m.path("payload").path("attributes").path("sessionId").asString()).isEqualTo("sess-41");
    assertThat(m.path("payload").path("attributes").path("transientToken").asString()).isEqualTo("tok-fake-sess-41");
    assertThat(KafkaTestConsumer.headers(got.getFirst())).containsEntry("mw-txn-id", txnId);
  }

  @Test
  void aThreeDsFailureCarriesTheMappedErrorCodeAndNoToken() throws Exception {
    String txnId = newTxnId();

    Answer a = post(webhook(issued(txnId), "sess-42", "FAILED", "4002"));

    assertThat(a.status()).as(a.toString()).isEqualTo(200);
    JsonNode m = JSON.readTree(published(txnId, 1, Duration.ofSeconds(10)).getFirst().value());
    assertThat(ContractSchemas.schema(SCHEMA).validate(m)).as(m.toString()).isEmpty();
    assertThat(m.path("payload").path("outcome").asString()).isEqualTo("FAILED");
    assertThat(m.path("payload").path("errorCode").asString()).isEqualTo("MW-PTR-CYBERSOURCE-THREE_DS_FAILED");
    assertThat(m.path("payload").path("attributes").has("transientToken")).isFalse();
  }

  @Tag("slow-wait")
  @Test
  void aWebhookWithABadSignatureIsRefusedAndNothingIsPublished() throws Exception {
    String txnId = newTxnId();
    String body = webhook(issued(txnId), "sess-43", "AUTHENTICATED", "100");

    Answer wrongKey = post(PATH, body, "not-the-secret", Instant.now().getEpochSecond());
    Answer stale = post(PATH, body, SECRET, Instant.now().minusSeconds(3600).getEpochSecond());

    for (Answer a : List.of(wrongKey, stale)) {
      assertThat(a.status()).as(a.toString()).isEqualTo(401);
      assertThat(a.body().path("code").asString()).isEqualTo("MW-AUTH-001");
    }
    assertThat(published(txnId, 1, Duration.ofSeconds(3))).isEmpty();
  }

  @Test
  void aWebhookForAReferenceNeverIssuedIsNotFound() throws Exception {
    Answer a = post(webhook("BITS00000000000000000000000000000000", "sess-44", "AUTHENTICATED", "100"));

    assertThat(a.status()).as(a.toString()).isEqualTo(404);
    assertThat(a.body().path("code").asString()).isEqualTo("MW-VAL-003");
  }

  @Tag("slow-wait")
  @Test
  void anUnreadableSignedWebhookIsABadRequest() throws Exception {
    String txnId = newTxnId();

    Answer authenticatedWithoutToken =
        post("{\"clientReference\":\"" + issued(txnId) + "\",\"sessionId\":\"sess-45\",\"status\":\"AUTHENTICATED\"}");
    Answer unknownStatus = post(webhook(issued(txnId), "sess-45", "MAYBE", "100"));

    for (Answer a : List.of(authenticatedWithoutToken, unknownStatus)) {
      assertThat(a.status()).as(a.toString()).isEqualTo(400);
      assertThat(a.body().path("code").asString()).isEqualTo("MW-VAL-002");
    }
    assertThat(published(txnId, 1, Duration.ofSeconds(3))).isEmpty();
  }
}
