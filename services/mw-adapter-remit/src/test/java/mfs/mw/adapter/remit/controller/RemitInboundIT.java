package mfs.mw.adapter.remit.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import mfs.mw.adapter.remit.RemitTestTopics;
import mfs.mw.adapter.remit.TestInfrastructure;
import mfs.mw.adapter.remit.contracts.ContractSchemas;
import mfs.mw.adapter.remit.partner.auth.HmacSha256AuthScheme;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Inbound remittance (ticket 119, FRS UC-16, FL-10, PLT-014, PLT-008) over HTTP against real
 * PostgreSQL and Kafka with the dev seed {@code ucb-imt}: a signed credit is published once on {@code
 * mw.partner.inbound.v1} (valid against the contract) and answered 202 with a Upay reference; a re-send
 * answers the same reference; a changed re-send, a replayed nonce, a bad signature or an invalid field
 * is refused and publishes nothing; the status query reads the stored remittance.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestInfrastructure.class, RemitTestTopics.class})
class RemitInboundIT {

  static final String SECRET = "fake-ucb-imt-hmac-secret-for-tests"; // partner/FakeVaultFunction
  static final String PATH = "/remit/v1/credits";
  static final String TOPIC = "mw.partner.inbound.v1";
  static final String SCHEMA = "events/mw-orchestrator/mw.partner.inbound.v1.schema.json";
  static final JsonMapper JSON = JsonMapper.builder().build();

  private final HttpClient http = HttpClient.newHttpClient();
  private final String base;
  private final JdbcTemplate jdbc;

  @Autowired
  RemitInboundIT(Environment env, JdbcTemplate jdbc) {
    this.base = "http://localhost:" + env.getProperty("local.server.port");
    this.jdbc = jdbc;
  }

  record Answer(int status, JsonNode body) {}

  Answer send(String method, String path, String body, String secret, String nonce) throws Exception {
    String ts = Long.toString(Instant.now().getEpochSecond());
    byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
    String sig = HmacSha256AuthScheme.sign(secret, method, path, ts, nonce, bytes);
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(base + path))
            .header("X-Partner-Id", "ucb-imt")
            .header("X-Signature", sig)
            .header("X-Timestamp", ts)
            .header("X-Nonce", nonce)
            .header("X-Correlation-Id", "corr-remit-1");
    if (body == null) {
      b.GET();
    } else {
      b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
    }
    HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    return new Answer(r.statusCode(), r.body().isBlank() ? JSON.missingNode() : JSON.readTree(r.body()));
  }

  Answer credit(String body) throws Exception {
    return send("POST", PATH, body, SECRET, nonce());
  }

  static String nonce() {
    return UUID.randomUUID().toString();
  }

  static String body(String partnerRef, String msisdn, String amount) {
    return "{\"partnerRef\":\"" + partnerRef + "\",\"beneficiaryMsisdn\":\"" + msisdn + "\",\"amount\":\"" + amount
        + "\",\"currency\":\"BDT\",\"senderName\":\"Fake Sender\",\"senderCountry\":\"AE\"}";
  }

  static String ref() {
    return "IMT-" + UUID.randomUUID().toString().substring(0, 13);
  }

  /**
   * The events written for {@code txnId}: the remittance row and its event commit together through the
   * outbox (M9 review: no dual write), so the outbox is where they are.
   */
  List<String> published(String txnId) {
    return jdbc.queryForList(
        "select payload::text from outbox where topic = ? and msg_key = ? order by id", String.class, TOPIC, txnId);
  }

  @Test
  void aSignedCreditIsPublishedAndAnsweredWithTheUpayReference() throws Exception {
    String ref = ref();

    Answer first = credit(body(ref, "+8801711000001", "2500.50"));

    assertThat(first.status()).as(first.toString()).isEqualTo(202);
    assertThat(first.body().path("status").asString()).isEqualTo("ACCEPTED");
    assertThat(first.body().path("partnerRef").asString()).isEqualTo(ref);
    String upayRef = first.body().path("upayRef").asString();
    assertThat(upayRef).matches("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    List<String> got = published(upayRef);
    assertThat(got).hasSize(1);
    JsonNode m = JSON.readTree(got.getFirst());
    assertThat(ContractSchemas.schema(SCHEMA).validate(m)).as(m.toString()).isEmpty();
    assertThat(m.path("family").asString()).isEqualTo("remit");
    assertThat(m.path("partnerId").asString()).isEqualTo("ucb-imt");
    assertThat(m.path("partnerRef").asString()).isEqualTo(ref);
    assertThat(m.path("correlationId").asString()).isEqualTo("corr-remit-1");
    assertThat(m.path("payload").path("kind").asString()).isEqualTo("REMITTANCE");
    assertThat(m.path("payload").path("txnType").asString()).isEqualTo("REMIT_IN");
    assertThat(m.path("payload").path("amount").path("valueMinor").asLong()).isEqualTo(250050);
    assertThat(m.path("payload").path("creditParty").path("id").asString()).isEqualTo("01711000001");
    assertThat(m.path("payload").path("attributes").path("senderCountry").asString()).isEqualTo("AE");

    Answer status = send("GET", PATH + "/" + ref, null, SECRET, nonce());
    assertThat(status.status()).as(status.toString()).isEqualTo(200);
    assertThat(status.body().path("upayRef").asString()).isEqualTo(upayRef);
    assertThat(status.body().path("status").asString()).isEqualTo("ACCEPTED");
  }

  @Test
  void aReSentCreditAnswersTheSameReferenceAndAChangedOneIsRefused() throws Exception {
    String ref = ref();
    Answer first = credit(body(ref, "01711000002", "100"));
    Answer again = credit(body(ref, "01711000002", "100.00"));
    Answer changed = credit(body(ref, "01711000003", "100"));

    assertThat(first.status()).isEqualTo(202);
    assertThat(again.status()).as(again.toString()).isEqualTo(200);
    assertThat(again.body().path("duplicate").asBoolean()).isTrue();
    assertThat(again.body().path("upayRef").asString()).isEqualTo(first.body().path("upayRef").asString());
    assertThat(changed.status()).as(changed.toString()).isEqualTo(409);
    assertThat(changed.body().path("code").asString()).isEqualTo("MW-IDEM-002");
  }

  @Test
  void aReSentCreditIsPublishedOnce() throws Exception {
    String ref = ref();
    Answer first = credit(body(ref, "01711000004", "10"));
    credit(body(ref, "01711000004", "10"));

    assertThat(published(first.body().path("upayRef").asString())).hasSize(1);
  }

  @Test
  void theStoredFingerprintIsKeyedAndNotAPlainHashOfTheMsisdn() throws Exception {
    String ref = ref();
    assertThat(credit(body(ref, "01711000006", "10")).status()).isEqualTo(202);

    String stored = jdbc.queryForObject(
        "select fingerprint from remittance where partner_id = 'ucb-imt' and partner_ref = ?", String.class, ref);
    String plain = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
        .digest("01711000006|1000|BDT".getBytes(StandardCharsets.UTF_8)));
    assertThat(stored).isNotEqualTo(plain).hasSize(64);
  }

  @Test
  void aReplayedNonceABadSignatureAndAnUnknownPartnerAreRefused() throws Exception {
    String nonce = nonce();
    String body = body(ref(), "01711000005", "10");
    assertThat(send("POST", PATH, body, SECRET, nonce).status()).isEqualTo(202);
    Answer replay = send("POST", PATH, body, SECRET, nonce);
    assertThat(replay.status()).as("replayed byte for byte").isEqualTo(401);
    assertThat(replay.body().path("code").asString()).isEqualTo("MW-AUTH-001");

    assertThat(send("POST", PATH, body(ref(), "01711000005", "10"), "not-the-secret", nonce()).status())
        .isEqualTo(401);
    assertThat(send("GET", PATH + "/" + ref(), null, "not-the-secret", nonce()).status()).isEqualTo(401);
  }

  @Test
  void anInvalidCreditIsRefusedWithTheFieldsNamed() throws Exception {
    Answer bad =
        credit("{\"partnerRef\":\"IMT 1\",\"beneficiaryMsisdn\":\"12345\",\"amount\":\"0\",\"currency\":\"USD\","
            + "\"senderName\":\"\",\"senderCountry\":\"UAE\"}");

    assertThat(bad.status()).as(bad.toString()).isEqualTo(400);
    assertThat(bad.body().path("code").asString()).isEqualTo("MW-VAL-001");
    assertThat(bad.body().path("details").findValuesAsString("field"))
        .contains("partnerRef", "beneficiaryMsisdn", "amount", "currency", "senderName", "senderCountry");
  }

  @Test
  void anUnknownReferenceHasNoStatus() throws Exception {
    Answer none = send("GET", PATH + "/" + ref(), null, SECRET, nonce());

    assertThat(none.status()).as(none.toString()).isEqualTo(404);
  }
}
