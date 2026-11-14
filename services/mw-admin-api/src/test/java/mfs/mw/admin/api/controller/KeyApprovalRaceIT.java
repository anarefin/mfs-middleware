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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * dual-control race) against a stateful stand-in mw-crypto with its rules: a key change per attempt
 * id (the claim token), applied or CANCELLED (terminal; a PUT after the cancel is refused), the
 * cancel atomic with the PUT; ceremonies opened by PUT, components only while OPEN, abort terminal.
 *
 * <ul>
 *   <li>approve vs reject, ceremony commit vs reject, component vs reject, each raced N times;
 *   <li>a 4xx is settled by a cancel: applied -> APPROVED, cancelled -> PENDING (a new attempt id
 *       next time; a ceremony EXPIRED), unknown -> APPROVING;
 *   <li>a timeout keeps APPROVING; only the claimer retries, within the lease, refreshing the
 *       attempt; the reconciler waits lease + read timeout after the last attempt, then cancels, and
 *       the PUT still in flight is refused when it arrives;
 *   <li>orphan ceremonies (filing failed, rejected while mw-crypto was down) are aborted later;
 *   <li>a component whose request turned APPROVING aborts nothing; REJECTED aborts.
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
  /** Attempt ids whose key change was applied / cancelled; every PUT's attempt id in order. */
  static final Set<String> APPLIED = ConcurrentHashMap.newKeySet();
  static final Set<String> CANCELLED = ConcurrentHashMap.newKeySet();
  static final List<String> PUTS = new CopyOnWriteArrayList<>();
  /** PUTs refused because their attempt was cancelled first. */
  static final List<String> LATE_REFUSED = new CopyOnWriteArrayList<>();
  /** Ceremony id -> OPEN, COMBINED, COMMITTED, ABORTED; share count; when opened. */
  static final Map<String, String> CEREMONIES = new ConcurrentHashMap<>();
  static final Map<String, Integer> SHARES = new ConcurrentHashMap<>();
  static final Map<String, Instant> OPENED = new ConcurrentHashMap<>();
  /** PUT key-changes: "ok", "refuse" (409), "refuse-applied" (applies, then 409), "hang", "hang-apply". */
  static final AtomicReference<String> MODE = new AtomicReference<>("ok");
  static volatile boolean cancelDown;
  static volatile boolean abortDown;
  static volatile boolean openGarbled;
  /** The stand-in's idea of now (the test clock), for the ceremonies' createdAt. */
  static volatile Instant stubNow = NOW;
  static volatile Runnable onComponent = () -> {};
  static volatile CountDownLatch lateDone = new CountDownLatch(0);
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
    String method = ex.getRequestMethod();
    String path = ex.getRequestURI().getPath();
    String[] p = path.split("/");
    if (path.equals("/internal/v1/keys")) {
      respond(ex, 200, KEYS);
    } else if (path.startsWith("/internal/v1/key-changes/") && path.endsWith("/cancel")) {
      if (cancelDown) {
        respond(ex, 503, "{\"code\":\"MW-SYS-010\"}");
      } else {
        respond(ex, 200, cancel(p[4]));
      }
    } else if (path.startsWith("/internal/v1/key-changes/")) {
      put(ex, p[4]);
    } else if (path.equals("/internal/v1/ceremonies") && "GET".equals(method)) {
      respond(ex, 200, live());
    } else if (path.startsWith("/internal/v1/ceremonies/")) {
      if (p.length > 5 && "components".equals(p[5])) {
        onComponent.run();
      }
      ceremony(ex, method, p[4], p.length > 5 ? p[5] : "");
    } else {
      respond(ex, 404, "{\"code\":\"MW-VAL-003\"}");
    }
  }

  /** mw-crypto: the attempt's row is applied or cancelled, whichever comes first (atomic). */
  static synchronized String cancel(String id) {
    if (APPLIED.contains(id)) {
      return "{\"changeId\":\"" + id + "\",\"applied\":true,\"cancelled\":false,\"kid\":\"pin-hmac.v2\"}";
    }
    CANCELLED.add(id);
    return "{\"changeId\":\"" + id + "\",\"applied\":false,\"cancelled\":true}";
  }

  /** Applies attempt {@code id} unless cancelled; false when it was cancelled. */
  static synchronized boolean apply(String id) {
    if (CANCELLED.contains(id)) {
      LATE_REFUSED.add(id);
      return false;
    }
    APPLIED.add(id);
    return true;
  }

  static void put(HttpExchange ex, String id) throws IOException {
    PUTS.add(id);
    switch (MODE.get()) {
      case "refuse" -> respond(ex, 409, "{\"code\":\"MW-VAL-018\",\"message\":\"conflict\"}");
      case "refuse-applied" -> {
        apply(id);
        respond(ex, 409, "{\"code\":\"MW-VAL-018\",\"message\":\"applied with other values\"}");
      }
      case "hang" -> {
        sleep(2_500);
        ex.close();
      }
      case "hang-apply" -> {
        // the answer is lost, but the PUT reaches mw-crypto's apply late
        sleep(5_000);
        apply(id);
        ex.close();
        lateDone.countDown();
      }
      default -> {
        if (apply(id)) {
          respond(ex, 200, KEY_V2);
        } else {
          respond(ex, 409, "{\"code\":\"MW-VAL-018\",\"message\":\"This key change was cancelled\"}");
        }
      }
    }
  }

  static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  static synchronized String live() {
    StringBuilder b = new StringBuilder("{\"items\":[");
    String sep = "";
    for (Map.Entry<String, String> e : CEREMONIES.entrySet()) {
      if ("OPEN".equals(e.getValue()) || "COMBINED".equals(e.getValue())) {
        b.append(sep).append("{\"ceremonyId\":\"").append(e.getKey()).append("\",\"keyId\":\"pin-hmac\",\"state\":\"")
            .append(e.getValue()).append("\",\"createdAt\":\"").append(OPENED.get(e.getKey())).append("\"}");
        sep = ",";
      }
    }
    return b.append("]}").toString();
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

  static synchronized void ceremony(HttpExchange ex, String method, String id, String op) throws IOException {
    String state = CEREMONIES.get(id);
    switch (method + " " + op) {
      case "PUT " -> {
        if ("ABORTED".equals(state)) {
          respond(ex, 409, "{\"code\":\"MW-VAL-018\",\"message\":\"aborted\"}");
          return;
        }
        if (CEREMONIES.putIfAbsent(id, "OPEN") == null) {
          OPENED.put(id, stubNow);
        }
        respond(ex, 200, openGarbled ? "{not json" : ceremonyView(id));
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
        if (abortDown) {
          respond(ex, 503, "{\"code\":\"MW-SYS-010\"}");
        } else if ("COMMITTED".equals(state)) {
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
    CANCELLED.clear();
    PUTS.clear();
    LATE_REFUSED.clear();
    CEREMONIES.clear();
    SHARES.clear();
    OPENED.clear();
    MODE.set("ok");
    cancelDown = false;
    abortDown = false;
    openGarbled = false;
    onComponent = () -> {};
    clock.set(NOW);
    stubNow = NOW;
  }

  private void advance(Duration d) {
    clock.advance(d);
    stubNow = clock.instant();
  }

  /** A security-admin whose MFA is 30 s old at the test clock's now. */
  private RequestPostProcessor op(String username) {
    long authTime = clock.instant().getEpochSecond() - 30;
    return jwt().jwt(j -> j.subject("sub-" + username).issuer("http://keycloak:8080/realms/ops")
        .claim("preferred_username", username).claim("auth_time", authTime)
        .claim("realm_access", Map.of("roles", List.of("security-admin"))));
  }

  private MvcTestResult post(String who, String uri, String body) {
    return mvc.post().uri(uri).with(op(who)).contentType(MediaType.APPLICATION_JSON).content(body).exchange();
  }

  private String file(String uri, String body) {
    MvcTestResult r = post("seed-ops-secadmin1", uri, body);
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
    return post(who, "/admin/v1/key-ceremonies/" + id + "/components",
        "{\"component\":\"" + component + "\",\"checkValue\":\"AAAAAA\"}").getResponse().getStatus();
  }

  private MvcTestResult approve(String who, String id) {
    return mvc.post().uri("/admin/v1/change-requests/{id}/approve", id).with(op(who)).exchange();
  }

  private MvcTestResult reject(String who, String id) {
    return post(who, "/admin/v1/change-requests/" + id + "/reject", "{\"reason\":\"no\"}");
  }

  private String status(String id) {
    return jdbc.queryForObject("select status from change_request where id = ?::uuid", String.class, id);
  }

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
        int before = APPLIED.size();
        int[] r = race(pool,
            () -> approve("seed-ops-secadmin2", id).getResponse().getStatus(),
            () -> reject("seed-ops-secadmin3", id).getResponse().getStatus());
        String s = status(id);
        assertThat(s).isIn("APPROVED", "REJECTED");
        if ("REJECTED".equals(s)) {
          assertThat(APPLIED).as("run %d: a rejected request was applied", i).hasSize(before);
          assertThat(r[1]).isEqualTo(200);
        } else {
          assertThat(APPLIED).hasSize(before + 1);
          assertThat(r).containsExactly(200, 409);
        }
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void aRefusalIsSettledByACancelAndTheNextApprovalUsesANewAttempt() {
    String id = rotation();
    MODE.set("refuse");
    assertThat(approve("seed-ops-secadmin2", id)).hasStatus(409);
    assertThat(status(id)).isEqualTo("PENDING");
    assertThat(CANCELLED).containsExactly(PUTS.getLast());
    MODE.set("ok");
    assertThat(approve("seed-ops-secadmin2", id)).hasStatus(200);
    assertThat(status(id)).isEqualTo("APPROVED");
    assertThat(PUTS).hasSize(2).doesNotHaveDuplicates();
  }

  @Test
  void aRefusalOfAChangeAlreadyAppliedFinishesApproved() {
    String id = rotation();
    MODE.set("refuse-applied");
    MvcTestResult r = approve("seed-ops-secadmin2", id);
    assertThat(r).hasStatus(200);
    assertThat(status(id)).isEqualTo("APPROVED");
    assertThat(CANCELLED).isEmpty();
    assertThat(json.readTree(r.getResponse().getContentAsByteArray()).path("payload").path("appliedKid").asString())
        .isEqualTo("pin-hmac.v2");
  }

  @Test
  void aRefusalWhenTheCancelFailsKeepsTheClaim() {
    String id = rotation();
    MODE.set("refuse");
    cancelDown = true;
    assertThat(approve("seed-ops-secadmin2", id)).hasStatus(409);
    assertThat(status(id)).isEqualTo("APPROVING");
  }

  @Test
  void aTimeoutKeepsTheClaimForItsCheckerWhoRetriesTheSameAttempt() {
    String id = rotation();
    MODE.set("hang");
    assertThat(approve("seed-ops-secadmin2", id)).hasStatus(503);
    assertThat(status(id)).isEqualTo("APPROVING");
    assertThat(reject("seed-ops-secadmin3", id)).hasStatus(409);
    assertThat(post("seed-ops-secadmin3", "/admin/v1/keys/change-requests",
        "{\"action\":\"KEY_ROTATE\",\"keyId\":\"pin-hmac\",\"reason\":\"again\"}")).hasStatus(409);
    assertThat(approve("seed-ops-secadmin3", id)).hasStatus(409);
    assertThat(approve("seed-ops-secadmin1", id)).hasStatus(403);

    MODE.set("ok");
    assertThat(approve("seed-ops-secadmin2", id)).hasStatus(200);
    assertThat(PUTS).hasSize(2);
    assertThat(PUTS.get(0)).as("the retry is the same attempt").isEqualTo(PUTS.get(1));
    assertThat(status(id)).isEqualTo("APPROVED");
  }

  @Test
  void theReconcilerCancelsOnlyAfterTheLastAttemptAndALatePutIsRefused() throws Exception {
    String id = rotation();
    lateDone = new CountDownLatch(1);
    MODE.set("hang-apply");
    assertThat(approve("seed-ops-secadmin2", id)).hasStatus(503);
    // a retry inside the lease refreshes the attempt: the reconciler leaves the claim alone
    advance(Duration.ofMinutes(4));
    MODE.set("hang");
    assertThat(approve("seed-ops-secadmin2", id)).hasStatus(503);
    advance(Duration.ofMinutes(2)); // claim 6 min old, last attempt 2 min
    assertThat(service.reconcileApproving()).isZero();
    assertThat(status(id)).isEqualTo("APPROVING");
    // the lease is over for retries
    assertThat(approve("seed-ops-secadmin2", id)).hasStatus(409);

    // lease + read timeout after the last attempt: cancel ("not applied" binding), then PENDING
    advance(Duration.ofMinutes(4));
    assertThat(service.reconcileApproving()).isEqualTo(1);
    assertThat(status(id)).isEqualTo("PENDING");
    String attempt = PUTS.getFirst();
    assertThat(CANCELLED).contains(attempt);
    // the first PUT, still in flight, reaches mw-crypto after the cancel: refused, nothing applied
    assertThat(lateDone.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(LATE_REFUSED).contains(attempt);
    assertThat(APPLIED).isEmpty();
  }

  @Test
  void theReconcilerFinishesAnAppliedClaimAndExpiresAnOldOne() {
    MODE.set("hang");
    String applied = rotation();
    assertThat(approve("seed-ops-secadmin2", applied)).hasStatus(503);
    APPLIED.add(PUTS.getLast()); // mw-crypto applied it although the answer was lost
    advance(Duration.ofMinutes(6));
    assertThat(service.reconcileApproving()).isEqualTo(1);
    assertThat(status(applied)).isEqualTo("APPROVED");

    String old = rotation();
    assertThat(approve("seed-ops-secadmin2", old)).hasStatus(503);
    advance(Duration.ofMinutes(6));
    cancelDown = true;
    assertThat(service.reconcileApproving()).isZero();
    assertThat(status(old)).isEqualTo("APPROVING");
    cancelDown = false;
    advance(Duration.ofHours(73)); // expires_at stays in force
    assertThat(service.reconcileApproving()).isEqualTo(1);
    assertThat(status(old)).isEqualTo("EXPIRED");
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
        if ("REJECTED".equals(status(id))) {
          assertThat(CEREMONIES.get(id)).as("run %d", i).isEqualTo("ABORTED");
        } else {
          assertThat(status(id)).isEqualTo("APPROVED");
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
        jdbc.update("delete from change_request where id = ?::uuid", id);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void aCeremonyCommitRefusedByMwCryptoAbortsItAndExpiresTheRequest() {
    String id = ceremony();
    component("seed-ops-secadmin1", id, C1);
    component("seed-ops-secadmin2", id, C2);
    CEREMONIES.put(id, "OPEN"); // the key changed meanwhile: mw-crypto refuses the commit
    assertThat(approve("seed-ops-secadmin2", id)).hasStatus(409);
    assertThat(CEREMONIES.get(id)).isEqualTo("ABORTED");
    assertThat(status(id)).isEqualTo("EXPIRED");
  }

  @Test
  void aComponentWhoseRequestTurnedApprovingAbortsNothingButARejectedOneAborts() {
    String id = ceremony();
    component("seed-ops-secadmin1", id, C1);
    onComponent = () -> jdbc.update("update change_request set status = 'APPROVING' where id = ?::uuid", id);
    assertThat(component("seed-ops-secadmin2", id, C2)).isEqualTo(409);
    assertThat(CEREMONIES.get(id)).as("a commit may be under way").isEqualTo("COMBINED");

    String other = file("/admin/v1/key-ceremonies", "{\"keyId\":\"hmac-other\",\"purpose\":\"HMAC_OTHER\",\"reason\":\"r\"}");
    onComponent = () -> jdbc.update("update change_request set status = 'REJECTED' where id = ?::uuid", other);
    assertThat(component("seed-ops-secadmin1", other, C1)).isEqualTo(409);
    assertThat(CEREMONIES.get(other)).isEqualTo("ABORTED");
  }

  @Test
  void aFilingThatFailsAfterTheOpenAbortsTheCeremony() {
    openGarbled = true; // the open reached mw-crypto, its answer is unreadable: the filing rolls back
    MvcTestResult r = post("seed-ops-secadmin1", "/admin/v1/key-ceremonies",
        "{\"keyId\":\"pin-hmac\",\"purpose\":\"PIN_HMAC\",\"reason\":\"r\"}");
    assertThat(r).hasStatus(503);
    assertThat(jdbc.queryForObject("select count(*) from change_request", Integer.class)).isZero();
    assertThat(CEREMONIES.values()).containsExactly("ABORTED");
  }

  @Test
  void theReconcilerAbortsOrphanCeremoniesOnly() {
    // rejected while mw-crypto was down: the abort failed
    String rejected = ceremony();
    abortDown = true;
    assertThat(reject("seed-ops-secadmin2", rejected)).hasStatus(200);
    assertThat(CEREMONIES.get(rejected)).isEqualTo("OPEN");
    abortDown = false;
    // one with no request at all (its filing never committed), one live and pending, one too young
    String missing = java.util.UUID.randomUUID().toString();
    CEREMONIES.put(missing, "OPEN");
    OPENED.put(missing, stubNow);
    String pending = file("/admin/v1/key-ceremonies", "{\"keyId\":\"hmac-pend\",\"purpose\":\"HMAC_PEND\",\"reason\":\"r\"}");
    advance(Duration.ofMinutes(6));
    String young = java.util.UUID.randomUUID().toString();
    CEREMONIES.put(young, "OPEN");
    OPENED.put(young, stubNow);

    service.reconcileApproving();
    assertThat(CEREMONIES.get(rejected)).isEqualTo("ABORTED");
    assertThat(CEREMONIES.get(missing)).isEqualTo("ABORTED");
    assertThat(CEREMONIES.get(pending)).isEqualTo("OPEN");
    assertThat(CEREMONIES.get(young)).isEqualTo("OPEN");
  }
}
