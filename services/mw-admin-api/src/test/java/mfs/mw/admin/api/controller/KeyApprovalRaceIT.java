package mfs.mw.admin.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import mfs.mw.admin.api.MutableClock;
import mfs.mw.admin.api.TestInfrastructure;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Claim-first approval of key changes (ticket 75, review of the dual-control race): the approval
 * moves the request PENDING -> APPROVING in one conditional update before mw-crypto is called, and
 * reject and expiry refuse an APPROVING request. Raced N times, an applied key change (a PUT that
 * mw-crypto answered) never belongs to a REJECTED request; a definite mw-crypto refusal puts the
 * request back to PENDING; a timeout leaves it APPROVING and a retry PUTs the same id again.
 */
@SpringBootTest(properties = {"mw.outbox.relay.enabled=false", "mw.admin-api.read-timeout=1s"})
@AutoConfigureMockMvc
@Import({TestInfrastructure.class, KeyApprovalRaceIT.TestClock.class})
class KeyApprovalRaceIT {

  static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
  static final String TOKEN = "service-token-race";
  /** Change request ids mw-crypto applied (answered 200 for). */
  static final Set<String> APPLIED = ConcurrentHashMap.newKeySet();
  static final AtomicInteger PUTS = new AtomicInteger();
  /** "ok", "refuse" (409 MW-VAL-018) or "hang" (no answer within the read timeout). */
  static final AtomicReference<String> MODE = new AtomicReference<>("ok");
  static final HttpServer CRYPTO = startStub();

  static final String KEYS = "{\"items\":[{\"keyId\":\"pin-hmac\",\"purpose\":\"PIN_HMAC\",\"algorithm\":\"HMAC_SHA256\","
      + "\"provider\":\"pkcs11\",\"versions\":[{\"version\":1,\"kid\":\"pin-hmac.v1\",\"state\":\"ACTIVE\","
      + "\"createdAt\":\"2026-11-01T00:00:00Z\",\"rotatedAt\":null}]}]}";
  static final String KEY_V2 = "{\"keyId\":\"pin-hmac\",\"purpose\":\"PIN_HMAC\",\"algorithm\":\"HMAC_SHA256\","
      + "\"provider\":\"pkcs11\",\"versions\":[{\"version\":2,\"kid\":\"pin-hmac.v2\",\"state\":\"ACTIVE\","
      + "\"createdAt\":\"2026-11-15T10:00:00Z\",\"rotatedAt\":null}]}";

  @TestConfiguration(proxyBeanMethods = false)
  static class TestClock {
    @Bean
    @Primary
    Clock testClock() {
      return new MutableClock(NOW);
    }
  }

  static HttpServer startStub() {
    try {
      HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      s.setExecutor(Executors.newCachedThreadPool());
      s.createContext("/token", ex -> respond(ex, 200, "{\"access_token\":\"" + TOKEN + "\",\"expires_in\":300}"));
      s.createContext("/internal/v1/", KeyApprovalRaceIT::crypto);
      s.start();
      return s;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  static void crypto(HttpExchange ex) throws IOException {
    ex.getRequestBody().readAllBytes();
    String path = ex.getRequestURI().getPath();
    if (path.equals("/internal/v1/keys")) {
      respond(ex, 200, KEYS);
      return;
    }
    if (path.startsWith("/internal/v1/key-changes/")) {
      PUTS.incrementAndGet();
      switch (MODE.get()) {
        case "refuse" -> respond(ex, 409, "{\"code\":\"MW-VAL-018\",\"message\":\"conflict\"}");
        case "hang" -> {
          try {
            Thread.sleep(2_500);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          ex.close();
        }
        default -> {
          APPLIED.add(path.substring(path.lastIndexOf('/') + 1));
          respond(ex, 200, KEY_V2);
        }
      }
      return;
    }
    respond(ex, 404, "{\"code\":\"MW-VAL-003\"}");
  }

  static void respond(HttpExchange ex, int status, String body) throws IOException {
    byte[] b = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(status, b.length);
    ex.getResponseBody().write(b);
    ex.close();
  }

  @AfterAll
  static void stopStub() {
    CRYPTO.stop(0);
  }

  @DynamicPropertySource
  static void upstreams(DynamicPropertyRegistry registry) {
    String base = "http://127.0.0.1:" + CRYPTO.getAddress().getPort();
    registry.add("mw.admin-api.crypto.base-url", () -> base);
    registry.add("mw.admin-api.internal-auth.token-uri", () -> base + "/token");
  }

  private final MockMvcTester mvc;
  private final JdbcTemplate jdbc;
  private final JsonMapper json;

  @Autowired
  KeyApprovalRaceIT(MockMvcTester mvc, JdbcTemplate jdbc, JsonMapper json) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
  }

  @BeforeEach
  void reset() {
    jdbc.update("delete from outbox");
    jdbc.update("delete from content_version");
    jdbc.update("delete from change_request");
    APPLIED.clear();
    PUTS.set(0);
    MODE.set("ok");
  }

  static RequestPostProcessor op(String username) {
    return jwt().jwt(j -> j.subject("sub-" + username).issuer("http://keycloak:8080/realms/ops")
        .claim("preferred_username", username).claim("auth_time", NOW.getEpochSecond() - 30)
        .claim("realm_access", Map.of("roles", List.of("security-admin"))));
  }

  static final RequestPostProcessor SEC1 = op("seed-ops-secadmin1");
  static final RequestPostProcessor SEC2 = op("seed-ops-secadmin2");
  static final RequestPostProcessor SEC3 = op("seed-ops-secadmin3");

  private String rotation() {
    MvcTestResult r = mvc.post().uri("/admin/v1/keys/change-requests").with(SEC1).contentType(MediaType.APPLICATION_JSON)
        .content("{\"action\":\"KEY_ROTATE\",\"keyId\":\"pin-hmac\",\"reason\":\"race\"}").exchange();
    assertThat(r).hasStatus(201);
    return json.readTree(r.getResponse().getContentAsByteArray()).path("id").asString();
  }

  private MvcTestResult approve(RequestPostProcessor who, String id) {
    return mvc.post().uri("/admin/v1/change-requests/{id}/approve", id).with(who).exchange();
  }

  private MvcTestResult reject(RequestPostProcessor who, String id) {
    return mvc.post().uri("/admin/v1/change-requests/{id}/reject", id).with(who)
        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"no\"}").exchange();
  }

  private String status(String id) {
    return jdbc.queryForObject("select status from change_request where id = ?::uuid", String.class, id);
  }

  @Test
  void rejectRacingApproveNeverLeavesAnAppliedKeyWithARejectedRequest() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int i = 0; i < 10; i++) {
        String id = rotation();
        CountDownLatch go = new CountDownLatch(1);
        Future<Integer> a = pool.submit(() -> {
          go.await();
          return approve(SEC2, id).getResponse().getStatus();
        });
        Future<Integer> r = pool.submit(() -> {
          go.await();
          return reject(SEC3, id).getResponse().getStatus();
        });
        go.countDown();
        int approved = a.get(10, TimeUnit.SECONDS);
        int rejected = r.get(10, TimeUnit.SECONDS);
        String s = status(id);
        assertThat(s).isIn("APPROVED", "REJECTED");
        if ("REJECTED".equals(s)) {
          assertThat(APPLIED).as("run %d: a rejected request was applied", i).doesNotContain(id);
          assertThat(rejected).isEqualTo(200);
        } else {
          assertThat(APPLIED).contains(id);
          assertThat(approved).isEqualTo(200);
          assertThat(rejected).isEqualTo(409);
        }
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void aDefiniteRefusalPutsTheRequestBackToPending() {
    String id = rotation();
    MODE.set("refuse");
    MvcTestResult r = approve(SEC2, id);
    assertThat(r).hasStatus(409);
    assertThat(status(id)).isEqualTo("PENDING");
    assertThat(jdbc.queryForObject("select checker_id from change_request where id = ?::uuid", String.class, id)).isNull();
    MODE.set("ok");
    assertThat(approve(SEC2, id)).hasStatus(200);
    assertThat(status(id)).isEqualTo("APPROVED");
  }

  @Test
  void aTimeoutLeavesApprovingRefusesRejectAndARetryPutsTheSameIdAgain() {
    String id = rotation();
    MODE.set("hang");
    assertThat(approve(SEC2, id)).hasStatus(503);
    assertThat(status(id)).isEqualTo("APPROVING");
    // reject and a new request of the key are refused while it is APPROVING
    MvcTestResult rej = reject(SEC3, id);
    assertThat(rej).hasStatus(409);
    assertThat(mvc.post().uri("/admin/v1/keys/change-requests").with(SEC3).contentType(MediaType.APPLICATION_JSON)
        .content("{\"action\":\"KEY_ROTATE\",\"keyId\":\"pin-hmac\",\"reason\":\"again\"}").exchange()).hasStatus(409);
    // the maker cannot finish it either
    assertThat(approve(SEC1, id)).hasStatus(403);

    MODE.set("ok");
    int before = PUTS.get();
    assertThat(approve(SEC2, id)).hasStatus(200);
    assertThat(PUTS.get()).isEqualTo(before + 1);
    assertThat(APPLIED).containsExactly(id);
    assertThat(status(id)).isEqualTo("APPROVED");
  }
}
