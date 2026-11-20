package mfs.mw.adapter.card.controller;

import static mfs.mw.adapter.card.CardFixtures.newTxnId;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import mfs.mw.adapter.card.TestInfrastructure;
import mfs.mw.adapter.card.partner.auth.HmacSha256AuthScheme;
import mfs.mw.adapter.card.service.PartnerReferenceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/**
 * Callback replay protection (ticket 99, FRS PLT-008; the 3-D Secure webhook, ticket 118b) over HTTP against real PostgreSQL and Kafka:
 * a signed callback carries a timestamp and a nonce; a stale timestamp and a reused nonce are
 * refused 401 like a forged signature, a fresh one is accepted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestInfrastructure.class, mfs.mw.adapter.card.CardTestTopics.class})
class CallbackReplayIT {

  static final String SECRET = PartnerCallbackIT.SECRET;
  static final String PATH = PartnerCallbackIT.PATH;

  private final HttpClient http = HttpClient.newHttpClient();
  private final PartnerReferenceService references;
  private final String base;

  @Autowired
  CallbackReplayIT(PartnerReferenceService references, Environment env) {
    this.references = references;
    this.base = "http://localhost:" + env.getProperty("local.server.port");
  }

  int post(String body, long epochSeconds, String nonce) throws Exception {
    String ts = Long.toString(epochSeconds);
    String sig = HmacSha256AuthScheme.sign(SECRET, "POST", PATH, ts, nonce, body.getBytes(StandardCharsets.UTF_8));
    return http.send(
            HttpRequest.newBuilder(URI.create(base + PATH))
                .header("Content-Type", "application/json")
                .header("X-Signature", sig)
                .header("X-Timestamp", ts)
                .header("X-Nonce", nonce)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString())
        .statusCode();
  }

  String callback() {
    return PartnerCallbackIT.webhook(references.referenceFor(newTxnId(), "cybersource"), "sess-r1", "FAILED", "4002");
  }

  @Test
  void aFreshCallbackIsAcceptedAndTheSameNonceAgainIsRefused() throws Exception {
    String body = callback();
    String nonce = UUID.randomUUID().toString();
    long now = Instant.now().getEpochSecond();

    assertThat(post(body, now, nonce)).isEqualTo(200);
    assertThat(post(body, now, nonce)).as("replayed byte for byte").isEqualTo(401);
    assertThat(post(body, now, UUID.randomUUID().toString())).as("a fresh nonce: the partner's resend").isEqualTo(200);
  }

  @Test
  void aStaleTimestampIsRefusedEvenWithAFreshNonce() throws Exception {
    long sixMinutesAgo = Instant.now().minusSeconds(360).getEpochSecond();

    assertThat(post(callback(), sixMinutesAgo, UUID.randomUUID().toString())).isEqualTo(401);
  }

  @Test
  void aCallbackWithoutANonceIsRefused() throws Exception {
    String body = callback();
    String ts = Long.toString(Instant.now().getEpochSecond());
    String sig = HmacSha256AuthScheme.sign(SECRET, "POST", PATH, ts, null, body.getBytes(StandardCharsets.UTF_8));
    int status =
        http.send(
                HttpRequest.newBuilder(URI.create(base + PATH))
                    .header("Content-Type", "application/json")
                    .header("X-Signature", sig)
                    .header("X-Timestamp", ts)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode();

    assertThat(status).isEqualTo(401);
  }
}
