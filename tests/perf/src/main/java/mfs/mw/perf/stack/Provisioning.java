package mfs.mw.perf.stack;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mfs.mw.perf.app.Device;
import mfs.mw.perf.app.PinCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Gets the running stack ready for a run, before any load: creates the perf users in Keycloak
 * (idempotent partial import through {@code docker compose exec keycloak kcadm.sh}), fetches the
 * core's PIN key and every user's token, and binds one fresh device per user exactly as the app
 * does (binding OTP read from the SMSC simulator, signed {@code POST /app/v1/devices}). Needs the
 * test-only routes ({@code scripts/suite.sh perf} applies them like {@code scripts/api-test.sh}).
 */
public final class Provisioning {

  private static final Logger log = LoggerFactory.getLogger(Provisioning.class);
  /** The code of the English binding OTP SMS (mw-notify template DEVICE_BIND_OTP). */
  private static final Pattern OTP = Pattern.compile("Your Bits code to bind a new device is ([0-9]{6})");
  private static final String KCADM =
      "/opt/keycloak/bin/kcadm.sh config credentials --config /tmp/perf-kcadm.config --server http://localhost:8080"
          + " --realm master --user \"$KC_BOOTSTRAP_ADMIN_USERNAME\" --password \"$KC_BOOTSTRAP_ADMIN_PASSWORD\" >/dev/null"
          + " && /opt/keycloak/bin/kcadm.sh create partialImport --config /tmp/perf-kcadm.config -r customers -f -"
          + " ; rc=$?; rm -f /tmp/perf-kcadm.config; exit $rc";

  /** What the load needs: PIN key, tokens, each app user's bound device. */
  public record Ready(PinCipher pin, Tokens tokens, Map<String, Device> devices) {}

  private Provisioning() {}

  public static Ready prepare(PerfUsers users, int parallelism) {
    Stack stack = Stack.get();
    String password = stack.devCredential("KC_CUSTOMERS_TEST_PASSWORD");
    log.info("event=perf.setup.users customers={} agents={}", users.customers().size(), users.agents().size());
    stack.compose(users.partialImport(password), "exec", "-T", "keycloak", "bash", "-c", KCADM);

    PinCipher pin = pinKey();
    Tokens tokens = new Tokens(password);
    List<String> names = users.appUsers().stream().map(PerfUsers.User::username).toList();
    tokens.start(names);

    log.info("event=perf.setup.devices count={}", names.size());
    Map<String, Device> devices = new ConcurrentHashMap<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(parallelism)) {
      List<Future<?>> jobs =
          users.appUsers().stream()
              .<Future<?>>map(u -> pool.submit(() -> devices.put(u.username(), bind(u, tokens.get(u.username())))))
              .toList();
      for (Future<?> j : jobs) {
        j.get();
      }
    } catch (Exception e) {
      tokens.close();
      throw new IllegalStateException("binding the perf devices failed: " + e.getMessage(), e);
    }
    return new Ready(pin, tokens, Map.copyOf(devices));
  }

  private static PinCipher pinKey() {
    Http.Response r = Http.get(Stack.get().url("test/core-mock/v1/pin-public-key"), apiKey());
    if (r.status() != 200) {
      throw new IllegalStateException("PIN public key (test-only routes applied? scripts/suite.sh perf does it): " + r.status());
    }
    return PinCipher.fromPem(r.body().get("keyId").asString(), r.body().get("publicKeyPem").asString());
  }

  private static Device bind(PerfUsers.User user, String token) {
    Device d = Device.create();
    List<String> before = otpsTo(user.msisdn());
    Http.Response asked = Http.postRaw(Stack.get().url("app/v1/devices/otp"), d.headers(token), "{\"locale\":\"en\"}");
    if (asked.status() != 202) {
      throw new IllegalStateException("binding OTP of " + user.username() + ": " + asked);
    }
    String otp = null;
    Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
    while (otp == null && Instant.now().isBefore(deadline)) {
      otp = otpsTo(user.msisdn()).stream().filter(c -> !before.contains(c)).reduce((a, b) -> b).orElse(null);
      if (otp == null) {
        pause();
      }
    }
    if (otp == null) {
      throw new IllegalStateException("no binding OTP SMS reached the wallet of " + user.username());
    }
    String body = "{\"publicKey\":\"" + d.publicKeyBase64() + "\",\"otp\":\"" + otp + "\"}";
    Http.Response bound =
        Http.postRaw(Stack.get().url("app/v1/devices"), d.signedHeaders(token, "POST", "/app/v1/devices", body), body);
    if (bound.status() != 201) {
      throw new IllegalStateException("binding the device of " + user.username() + ": " + bound);
    }
    return d;
  }

  private static List<String> otpsTo(String wallet) {
    Http.Response r = Http.get(Stack.get().url("test/sim/smsc/messages?msisdn=" + wallet), apiKey());
    if (r.status() != 200) {
      throw new IllegalStateException("SMSC simulator query failed: " + r.status());
    }
    List<String> codes = new ArrayList<>();
    for (JsonNode m : r.body()) {
      Matcher matcher = OTP.matcher(m.path("text").asString(""));
      if (matcher.find()) {
        codes.add(matcher.group(1));
      }
    }
    return codes;
  }

  private static Map<String, String> apiKey() {
    return Map.of("apikey", Stack.get().devCredential("MW_E2E_API_KEY"));
  }

  private static void pause() {
    try {
      Thread.sleep(500);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
