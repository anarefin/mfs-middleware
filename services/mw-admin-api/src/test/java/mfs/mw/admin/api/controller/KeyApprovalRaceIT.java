package mfs.mw.admin.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import mfs.mw.admin.api.MutableClock;
import mfs.mw.admin.api.TestInfrastructure;
import mfs.mw.admin.api.service.ChangeRequestService;
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
 * Claim-first approval of key changes and the key ceremony (ticket 75, security reviews of the
 * dual-control race), against a stateful stand-in mw-crypto (key changes applied per id, ceremonies
 * OPEN / COMBINED / COMMITTED / ABORTED with abort terminal per id):
 *
 * <ul>
 *   <li>approve vs reject raced N times: an applied change never belongs to a REJECTED request;
 *   <li>a 4xx for a change mw-crypto already applied finishes APPROVED (asked by id), a 4xx for one
 *       not applied releases to PENDING, a 4xx when mw-crypto cannot tell keeps APPROVING;
 *   <li>a timeout keeps APPROVING; only the claiming checker retries, within the lease; past it the
 *       reconciler resolves APPROVED / PENDING / EXPIRED from mw-crypto's state;
 *   <li>ceremony commit vs reject and component vs reject raced N times: a REJECTED ceremony is
 *       always ABORTED at mw-crypto, never COMMITTED or left OPEN / COMBINED.
 * </ul>
 */
@SpringBootTest(properties = {"mw.outbox.relay.enabled=false", "mw.admin-api.read-timeout=1s"})
@AutoConfigureMockMvc
@Import({TestInfrastructure.class, KeyApprovalRaceIT.TestClock.class})
class KeyApprovalRaceIT {

  static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
  static final String TOKEN = "service-token-race";
  static final String C1 = "11".repeat(32);
  static final String C2 = "22".repeat(32);
  /** Change request ids whose key change mw-crypto applied. */
  static final Set<String> APPLIED = ConcurrentHashMap.newKeySet();
  /** Ceremony id -> OPEN, COMBINED, COMMITTED, ABORTED; and its share count. */
  static final Map<String, String> CEREMONIES = new ConcurrentHashMap<>();
  static final Map<String, Integer> SHARES = new ConcurrentHashMap<>();
  static final AtomicInteger PUTS = new AtomicInteger();
  /** PUT key-changes: "ok", "refuse" (409), "refuse-applied" (applies, then 409), "hang". */
  static final AtomicReference<String> MODE = new AtomicReference<>("ok");
  /** GET key-changes answers 503 (mw-crypto cannot tell). */
  static volatile boolean stateDown;
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

  static String ceremonyView(String id) {
    String state = CEREMONIES.get(id);
    int n = SHARES.getOrDefault(id, 0);
    return "{\"ceremonyId\":\"" + id + "\",\"keyId\":\"pin-hmac\",\"purpose\":\"PIN_HMAC\",\"version\":2,"
        + "\"kid\":\"pin-hmac.v2\",\"state\":\"" + state + "\",\"shares\":["
        + (n >= 1 ? "{\"share\":1,\"custodian\":\"seed-ops-secadmin1\",\"enteredAt\":\"2026-11-15T10:00:00Z\"}" : "")
        + (n >= 2 ? ",{\"share\":2,\"custodian\":\"seed-ops-secadmin2\",\"enteredAt\":\"2026-11-15T10:01:00Z\"}" : "")
        + "],\"checkValue\":" + (n >= 2 ? "\"A1B2C3\"" : "null") + "}";
  }

  static void crypto(HttpExchange ex) throws IOException {
    ex.getRequestBody().readAllBytes();
    String method = ex.getRequestMethod();
    String path = ex.getRequestURI().getPath();
    String[] p = path.split("/");
    if (path.equals("/internal/v1/keys")) {
      respond(ex, 200, KEYS);
    } else if (path.startsWith("/internal/v1/key-changes/") && "GET".equals(method)) {
      if (stateDown) {
        respond(ex, 503, "{\"code\":\"MW-SYS-010\"}");
      } else {
        boolean applied = APPLIED.contains(p[4]);
        respond(ex, 200, "{\"changeRequestId\":\"" + p[4] + "\",\"applied\":" + applied
            + (applied ? ",\"action\":\"ROTATE\",\"keyId\":\"pin-hmac\",\"kid\":\"pin-hmac.v2\"" : "") + "}");
      }
    } else if (path.startsWith("/internal/v1/key-changes/")) {
      PUTS.incrementAndGet();
      switch (MODE.get()) {
        case "refuse" -> respond(ex, 409, "{\"code\":\"MW-VAL-018\",\"message\":\"conflict\"}");
        case "refuse-applied" -> {
          APPLIED.add(p[4]);
          respond(ex, 409, "{\"code\":\"MW-VAL-018\",\"message\":\"applied with other values\"}");
        }
        case "hang" -> {
          try {
            Thread.sleep(2_500);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          ex.close();
        }
        default -> {
          APPLIED.add(p[4]);
          respond(ex, 200, KEY_V2);
        }
      }
    } else if (path.startsWith("/internal/v1/ceremonies/")) {
      ceremony(ex, method, p[4], p.length > 5 ? p[5] : "");
    } else {
      respond(ex, 404, "{\"code\":\"MW-VAL-003\"}");
    }
  }

  /** mw-crypto's ceremony rules: opened by PUT, components only while OPEN, abort terminal per id. */
  static synchronized void ceremony(HttpExchange ex, String method, String id, String op) throws IOException {
    String state = CEREMONIES.get(id);
    switch (method + " " + op) {
      case "PUT " -> {
        if ("ABORTED".equals(state)) {
          respond(ex, 409, "{\"code\":\"MW-VAL-018\",\"message\":\"aborted\"}");
          return;
        }
        CEREMONIES.putIfAbsent(id, "OPEN");
        respond(ex, 200, ceremonyView(id));
      }
      case "GET " -> {
        if (state == null) {
          respond(ex, 404, "{\"code\":\"MW-VAL-003\",\"message\":\"no ceremony\"}");
        } else {
          respond(ex, 200, ceremonyView(id));
        }
      }
      case "POST components" -> {
        if (state == null) {
          respond(ex, 404, "{\"code\":\"MW-VAL-003\",\"message\":\"no ceremony\"}");
        } else if (!"OPEN".equals(state)) {
          respond(ex, 409, "{\"code\":\"MW-VAL-018\",\"message\":\"takes no more components\"}");
        } else {
          int n = SHARES.merge(id, 1, Integer::sum);
          if (n == 2) {
            CEREMONIES.put(id, "COMBINED");
          }
          respond(ex, 200, ceremonyView(id));
        }
      }
      case "POST commit" -> {
        if ("COMBINED".equals(state) || "COMMITTED".equals(state)) {
          CEREMONIES.put(id, "COMMITTED");
          respond(ex, 200, KEY_V2);
        } else {
          respond(ex, 409, "{\"code\":\"MW-VAL-018\",\"message\":\"not combined\"}");
        }
      }
      case "POST abort" -> {
        if ("COMMITTED".equals(state)) {
          respond(ex, 409, "{\"code\":\"MW-VAL-018\",\"message\":\"committed\"}");
        } else {
          CEREMONIES.put(id, "ABORTED");
          ex.sendResponseHeaders(204, -1);
          ex.close();
        }
      }
      default -> respond(ex, 404, "{\"code\":\"MW-VAL-003\"}");
    }
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
  private final MutableClock clock;
  private final ChangeRequestService service;

  @Autowired
  KeyApprovalRaceIT(MockMvcTester mvc, JdbcTemplate jdbc, JsonMapper json, Clock clock, ChangeRequestService service) {
    this.mvc = mvc;
    this.jdbc = jdbc;
    this.json = json;
    this.clock = (MutableClock) clock;
    this.service = service;
  }

  @BeforeEach
  void reset() {
    jdbc.update("delete from outbox");
    jdbc.update("delete from content_version");
    jdbc.update("delete from change_request");
    APPLIED.clear();
    CEREMONIES.clear();
    SHARES.clear();
    PUTS.set(0);
    MODE.set("ok");
    stateDown = false;
    clock.set(NOW);
  }

  /** A security-admin whose MFA is 30 s old at the test clock's now. */
  private RequestPostProcessor op(String username) {
    long authTime = clock.instant().getEpochSecond() - 30;
    return jwt().jwt(j -> j.subject("sub-" + username).issuer("http://keycloak:8080/realms/ops")
        .claim("preferred_username", username).claim("auth_time", authTime)
        .claim("realm_access", Map.of("roles", List.of("security-admin"))));
  }

  private String file(String uri, String body) {
    MvcTestResult r = mvc.post().uri(uri).with(op("seed-ops-secadmin1")).contentType(MediaType.APPLICATION_JSON)
        .content(body).exchange();
    assertThat(r).hasStatus(201);
    return json.readTree(r.getResponse().getContentAsByteArray()).path("id").asString();
  }

  private String rotation() {
    return file("/admin/v1/keys/change-requests", "{\"action\":\"KEY_ROTATE\",\"keyId\":\"pin-hmac\",\"reason\":\"race\"}");
  }

  private String ceremony() {
    return file("/admin/v1/key-ceremonies", "{\"keyId\":\"pin-hmac\",\"purpose\":\"PIN_HMAC\",\"reason\":\"race\"}");
  }

  private int component(String who, String id, String component) {
    return mvc.post().uri("/admin/v1/key-ceremonies/{id}/components", id).with(op(who))
        .contentType(MediaType.APPLICATION_JSON).content("{\"component\":\"" + component + "\",\"checkValue\":\"AAAAAA\"}")
        .exchange().getResponse().getStatus();
  }

  private MvcTestResult approve(String who, String id) {
    return mvc.post().uri("/admin/v1/change-requests/{id}/approve", id).with(op(who)).exchange();
  }

  private MvcTestResult reject(String who, String id) {
    return mvc.post().uri("/admin/v1/change-requests/{id}/reject", id).with(op(who))
        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"no\"}").exchange();
  }

  private String status(String id) {
    return jdbc.queryForObject("select status from change_request where id = ?::uuid", String.class, id);
  }

  /** Runs both at once and returns their HTTP statuses. */
  private static int[] race(ExecutorService pool, Callable<Integer> a, Callable<Integer> b) throws Exception {
    CountDownLatch go = new CountDownLatch(1);
    Future<Integer> fa = pool.submit(() -> {
      go.await();
      return a.call();
    });
    Future<Integer> fb = pool.submit(() -> {
      go.await();
      return b.call();
    });
    go.countDown();
    return new int[] {fa.get(10, TimeUnit.SECONDS), fb.get(10, TimeUnit.SECONDS)};
  }

  @Test
  void rejectRacingApproveNeverLeavesAnAppliedKeyWithARejectedRequest() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int i = 0; i < 10; i++) {
        String id = rotation();
        int[] r = race(pool,
            () -> approve("seed-ops-secadmin2", id).getResponse().getStatus(),
            () -> reject("seed-ops-secadmin3", id).getResponse().getStatus());
        String s = status(id);
        assertThat(s).isIn("APPROVED", "REJECTED");
        if ("REJECTED".equals(s)) {
          assertThat(APPLIED).as("run %d: a rejected request was applied", i).doesNotContain(id);
          assertThat(r[1]).isEqualTo(200);
        } else {
          assertThat(APPLIED).contains(id);
          assertThat(r).containsExactly(200, 409);
        }
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void aRefusalOfAChangeNotAppliedReleasesToPending() {
    String id = rotation();
    MODE.set("refuse");
    assertThat(approve("seed-ops-secadmin2", id)).hasStatus(409);
    assertThat(status(id)).isEqualTo("PENDING");
    assertThat(jdbc.queryForObject("select checker_id from change_request where id = ?::uuid", String.class, id)).isNull();
    MODE.set("ok");
    assertThat(approve("seed-ops-secadmin2", id)).hasStatus(200);
    assertThat(status(id)).isEqualTo("APPROVED");
  }

  @Test
  void aRefusalOfAChangeAlreadyAppliedFinishesApproved() {
    String id = rotation();
    MODE.set("refuse-applied");
    MvcTestResult r = approve("seed-ops-secadmin2", id);
    assertThat(r).hasStatus(200);
    assertThat(status(id)).isEqualTo("APPROVED");
    assertThat(json.readTree(r.getResponse().getContentAsByteArray()).path("payload").path("appliedKid").asString())
        .isEqualTo("pin-hmac.v2");
  }

  @Test
  void aRefusalWhenMwCryptoCannotTellKeepsTheClaim() {
    String id = rotation();
    MODE.set("refuse");
    stateDown = true;
    assertThat(approve("seed-ops-secadmin2", id)).hasStatus(409);
    assertThat(status(id)).isEqualTo("APPROVING");
  }

  @Test
  void aTimeoutKeepsTheClaimForItsCheckerWithinTheLeaseOnly() {
    String id = rotation();
    MODE.set("hang");
    assertThat(approve("seed-ops-secadmin2", id)).hasStatus(503);
    assertThat(status(id)).isEqualTo("APPROVING");
    // reject, a new request of the key, another checker and the maker are refused
    assertThat(reject("seed-ops-secadmin3", id)).hasStatus(409);
    assertThat(mvc.post().uri("/admin/v1/keys/change-requests").with(op("seed-ops-secadmin3"))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"action\":\"KEY_ROTATE\",\"keyId\":\"pin-hmac\",\"reason\":\"again\"}").exchange()).hasStatus(409);
    assertThat(approve("seed-ops-secadmin3", id)).hasStatus(409);
    assertThat(approve("seed-ops-secadmin1", id)).hasStatus(403);

    // the claiming checker retries with the same id
    MODE.set("ok");
    int before = PUTS.get();
    assertThat(approve("seed-ops-secadmin2", id)).hasStatus(200);
    assertThat(PUTS.get()).isEqualTo(before + 1);
    assertThat(APPLIED).containsExactly(id);
    assertThat(status(id)).isEqualTo("APPROVED");
  }

  @Test
  void pastTheLeaseOnlyTheReconcilerResolvesAClaim() {
    MODE.set("hang");
    String applied = rotation();
    assertThat(approve("seed-ops-secadmin2", applied)).hasStatus(503);
    APPLIED.add(applied); // mw-crypto applied it although the answer was lost
    clock.advance(Duration.ofMinutes(6));
    MODE.set("ok");
    assertThat(approve("seed-ops-secadmin2", applied)).hasStatus(409);

    assertThat(service.reconcileApproving()).isEqualTo(1);
    assertThat(status(applied)).isEqualTo("APPROVED");

    // not applied, still before expires_at: back to PENDING
    MODE.set("hang");
    String notApplied = rotation();
    assertThat(approve("seed-ops-secadmin2", notApplied)).hasStatus(503);
    clock.advance(Duration.ofMinutes(6));
    stateDown = true;
    assertThat(service.reconcileApproving()).isZero();
    assertThat(status(notApplied)).isEqualTo("APPROVING");
    stateDown = false;
    assertThat(service.reconcileApproving()).isEqualTo(1);
    assertThat(status(notApplied)).isEqualTo("PENDING");

    // not applied and past expires_at: EXPIRED (expires_at stays in force)
    assertThat(approve("seed-ops-secadmin2", notApplied)).hasStatus(503);
    clock.advance(Duration.ofHours(73));
    assertThat(service.reconcileApproving()).isEqualTo(1);
    assertThat(status(notApplied)).isEqualTo("EXPIRED");
  }

  @Test
  void aCeremonyCommitRacingRejectNeverCommitsARejectedCeremony() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int i = 0; i < 10; i++) {
        String id = ceremony();
        assertThat(CEREMONIES.get(id)).as("opened at filing time").isEqualTo("OPEN");
        assertThat(component("seed-ops-secadmin1", id, C1)).isEqualTo(200);
        assertThat(component("seed-ops-secadmin2", id, C2)).isEqualTo(200);
        race(pool,
            () -> approve("seed-ops-secadmin2", id).getResponse().getStatus(),
            () -> reject("seed-ops-secadmin3", id).getResponse().getStatus());
        String s = status(id);
        if ("REJECTED".equals(s)) {
          assertThat(CEREMONIES.get(id)).as("run %d", i).isEqualTo("ABORTED");
        } else {
          assertThat(s).isEqualTo("APPROVED");
          assertThat(CEREMONIES.get(id)).as("run %d", i).isEqualTo("COMMITTED");
        }
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void aComponentRacingRejectNeverLeavesALiveCeremony() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int i = 0; i < 10; i++) {
        String id = ceremony();
        assertThat(component("seed-ops-secadmin1", id, C1)).isEqualTo(200);
        int[] r = race(pool,
            () -> component("seed-ops-secadmin2", id, C2),
            () -> reject("seed-ops-secadmin3", id).getResponse().getStatus());
        assertThat(r[1]).isEqualTo(200);
        assertThat(status(id)).isEqualTo("REJECTED");
        assertThat(CEREMONIES.get(id)).as("run %d: the ceremony stayed live", i).isEqualTo("ABORTED");
        // a ceremony of the key can be filed again
        jdbc.update("delete from change_request where id = ?::uuid", id);
      }
    } finally {
      pool.shutdownNow();
    }
  }
}
