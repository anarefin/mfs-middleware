package mfs.mw.checkout.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import mfs.mw.checkout.MutableClock;
import mfs.mw.checkout.config.AuthClientProperties;
import mfs.mw.checkout.config.ServiceCertificateManager;
import mfs.mw.checkout.dto.EncryptedPin;
import mfs.mw.checkout.dto.OtpSent;
import mfs.mw.checkout.dto.TxnAuthorisationRequest;
import mfs.mw.checkout.exception.MwException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link AuthClient} against a local HTTP server playing mw-auth and the Keycloak token endpoint
 * (plain HTTP, TLS off: the mTLS client is the template's, tested by {@code MtlsClientIT}). mw-auth's
 * refusals pass through; only its own-token 401 is retried; everything else fails closed.
 */
class AuthClientTest {

  static final JsonMapper JSON = JsonMapper.builder().build();
  static final String ASSERTION = "eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiIwMTcxMTAwMDAwMSJ9.c2lnbmF0dXJl";

  HttpServer server;
  final Map<String, String[]> answers = new ConcurrentHashMap<>();
  final List<String> calls = new CopyOnWriteArrayList<>();
  final List<String> bodies = new CopyOnWriteArrayList<>();
  final AtomicInteger tokens = new AtomicInteger();
  MutableClock clock;
  AuthClient client;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/token", ex -> {
      int n = tokens.incrementAndGet();
      reply(ex, 200, "{\"access_token\":\"tok-" + n + "\",\"expires_in\":300}");
    });
    server.createContext("/", ex -> {
      String path = ex.getRequestURI().getPath();
      calls.add(ex.getRequestMethod() + " " + path + " " + ex.getRequestHeaders().getFirst("Authorization"));
      bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      String[] a = answers.getOrDefault(path, new String[] {"404", "{}"});
      if (a.length > 2 && calls.stream().filter(c -> c.contains(path)).count() > 1) {
        reply(ex, Integer.parseInt(a[2]), a[3]);
      } else {
        reply(ex, Integer.parseInt(a[0]), a[1]);
      }
    });
    server.start();
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    clock = new MutableClock(Instant.parse("2026-11-15T10:00:00Z"));
    AuthClientProperties props = new AuthClientProperties(URI.create(base), URI.create(base + "/token"), "mw-checkout",
        "fake-secret-not-real", Duration.ofSeconds(2), Duration.ofSeconds(1), Duration.ofMinutes(5));
    client = new AuthClient(props, new ServiceTokenClient(props, JSON, clock), CircuitBreakerRegistry.ofDefaults(),
        new StaticListableBeanFactory().getBeanProvider(ServiceCertificateManager.class), JSON, clock);
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  static void reply(HttpExchange ex, int status, String body) throws IOException {
    byte[] b = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    if (status == 204) {
      ex.sendResponseHeaders(204, -1);
    } else {
      ex.sendResponseHeaders(status, b.length);
      ex.getResponseBody().write(b);
    }
    ex.close();
  }

  static String envelope(String code, String details) {
    return "{\"code\":\"" + code + "\",\"message\":\"m\",\"messageBn\":\"বাং\",\"correlationId\":\"c\","
        + "\"retryable\":false,\"details\":" + details + "}";
  }

  static TxnAuthorisationRequest authorisation() {
    return new TxnAuthorisationRequest("01711000001", "WEB_CHECKOUT", new EncryptedPin("pin-transport.v1", "QUJD"),
        new TxnAuthorisationRequest.Txn("MERCHANT_PAY", 2550, "BDT",
            new TxnAuthorisationRequest.TxnParty("WALLET", "01711000001"),
            new TxnAuthorisationRequest.TxnParty("WALLET", "01911000001"), "checkout:s1"));
  }

  @Test
  void theAssertionComesWithTheServiceTokenAndTheExactTxn() throws Exception {
    answers.put("/internal/v1/txn-authorisations",
        new String[] {"200", "{\"assertion\":\"" + ASSERTION + "\",\"expiresAt\":\"2026-11-15T10:01:00Z\",\"newDevice\":false}"});
    assertThat(client.authorise(authorisation())).isEqualTo(ASSERTION);
    assertThat(calls).containsExactly("POST /internal/v1/txn-authorisations Bearer tok-1");
    var sent = JSON.readTree(bodies.getFirst());
    assertThat(sent.path("msisdn").asString()).isEqualTo("01711000001");
    assertThat(sent.path("txn").path("amount").asLong()).isEqualTo(2550);
    assertThat(sent.path("txn").path("payee").path("id").asString()).isEqualTo("01911000001");
    assertThat(sent.path("encryptedPin").path("keyId").asString()).isEqualTo("pin-transport.v1");
  }

  @Test
  void aWrongPinPassesThroughWithItsDetailsAndIsNeverSentTwice() {
    answers.put("/internal/v1/txn-authorisations",
        new String[] {"401", envelope("MW-AUTH-040", "[{\"field\":\"triesLeft\",\"issue\":\"2\"}]")});
    MwException e = catchThrowableOfType(MwException.class, () -> client.authorise(authorisation()));
    assertThat(e.code()).isEqualTo("MW-AUTH-040");
    assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(e.details()).extracting(d -> d.field() + "=" + d.issue()).containsExactly("triesLeft=2");
    assertThat(e.messageBn()).isEqualTo("বাং");
    assertThat(calls).hasSize(1);
    assertThat(tokens).hasValue(1);
  }

  @Test
  void aLockPassesThroughAs423() {
    answers.put("/internal/v1/txn-authorisations",
        new String[] {"423", envelope("MW-AUTH-041", "[{\"field\":\"retryAfter\",\"issue\":\"60\"}]")});
    MwException e = catchThrowableOfType(MwException.class, () -> client.authorise(authorisation()));
    assertThat(e.code()).isEqualTo("MW-AUTH-041");
    assertThat(e.status()).isEqualTo(HttpStatus.LOCKED);
  }

  @Test
  void aRefusedServiceTokenIsRenewedOnce() {
    answers.put("/internal/v1/otp/verifications",
        new String[] {"401", envelope("MW-AUTH-001", "[]"), "204", ""});
    client.verifyOtp("01711000001", UUID.randomUUID(), "123456");
    assertThat(calls).hasSize(2);
    assertThat(calls.get(1)).endsWith("Bearer tok-2");
  }

  @Test
  void otpRefusalsPassThrough() {
    answers.put("/internal/v1/otp/verifications", new String[] {"401", envelope("MW-AUTH-010", "[]")});
    MwException e = catchThrowableOfType(MwException.class,
        () -> client.verifyOtp("01711000001", UUID.randomUUID(), "123456"));
    assertThat(e.code()).isEqualTo("MW-AUTH-010");
    assertThat(calls).hasSize(1);
  }

  @Test
  void theOtpIsAskedForTheSession() throws Exception {
    UUID session = UUID.randomUUID();
    answers.put("/internal/v1/otp",
        new String[] {"202", "{\"wallet\":\"0171****001\",\"otpExpiresAt\":\"2026-11-15T10:02:00Z\",\"sendsLeft\":4}"});
    OtpSent sent = client.sendOtp("01711000001", session, "bn", "25.50", "Karim Traders");
    assertThat(sent.sendsLeft()).isEqualTo(4);
    var body = JSON.readTree(bodies.getFirst());
    assertThat(body.path("purpose").asString()).isEqualTo("CHECKOUT");
    assertThat(body.path("bindingId").asString()).isEqualTo(session.toString());
    assertThat(body.path("amount").asString()).isEqualTo("25.50");
    assertThat(body.path("merchant").asString()).isEqualTo("Karim Traders");
  }

  @Test
  void aServerErrorOrNoServerFailsClosedAsRetryable503() {
    answers.put("/internal/v1/txn-authorisations", new String[] {"503", envelope("MW-SYS-050", "[]")});
    MwException e = catchThrowableOfType(MwException.class, () -> client.authorise(authorisation()));
    assertThat(e.code()).isEqualTo("MW-SYS-012");
    assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(e.retryable()).isTrue();

    server.stop(0);
    MwException down = catchThrowableOfType(MwException.class, () -> client.authorise(authorisation()));
    assertThat(down.code()).isEqualTo("MW-SYS-012");
  }

  static String pinKey(String purpose) {
    return "{\"keyId\":\"pin-transport.v1\",\"algorithm\":\"RSA-OAEP-SHA-256\",\"publicKeyPem\":\"-----BEGIN PUBLIC KEY-----\""
        + (purpose == null ? "" : ",\"purpose\":\"" + purpose + "\"") + "}";
  }

  /** Security review of ticket 72: the transport key on the internal port with the service token. */
  @Test
  void thePinKeyComesFromTheInternalPortWithTheTokenAndIsCachedAndKeptWhileMwAuthIsDown() {
    answers.put("/internal/v1/pin-key", new String[] {"200", pinKey("PIN_TRANSPORT")});
    assertThat(client.pinKey()).get().extracting(k -> k.keyId()).isEqualTo("pin-transport.v1");
    assertThat(client.pinKey()).isPresent();
    assertThat(calls).as("cached").containsExactly("GET /internal/v1/pin-key Bearer tok-1");

    clock.advance(Duration.ofMinutes(6));
    answers.put("/internal/v1/pin-key", new String[] {"503", envelope("MW-SYS-050", "[]")});
    assertThat(client.pinKey()).get().extracting(k -> k.keyId()).isEqualTo("pin-transport.v1");
    assertThat(calls).hasSize(2);
  }

  @Test
  void noPinKeyWithoutAnyAnswer() {
    answers.put("/internal/v1/pin-key", new String[] {"503", envelope("MW-SYS-050", "[]")});
    assertThat(client.pinKey()).isEmpty();
  }

  @Test
  void aKeyOfAnotherPurposeOrWithoutOneIsRefusedAndNeverCached() {
    answers.put("/internal/v1/pin-key", new String[] {"200", pinKey("JWT_SIGN")});
    assertThat(client.pinKey()).isEmpty();
    answers.put("/internal/v1/pin-key", new String[] {"200", pinKey(null)});
    assertThat(client.pinKey()).isEmpty();
    answers.put("/internal/v1/pin-key", new String[] {"200", pinKey("PIN_TRANSPORT")});
    assertThat(client.pinKey()).isPresent();
  }
}
