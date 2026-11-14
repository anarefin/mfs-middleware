package mfs.mw.perf.stack;

import java.net.URI;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Gets the running stack ready for a run, before any load. The app users log in at mw-auth exactly as
 * an app does (ticket 73: no Keycloak customers realm; the same flow as {@code
 * scripts/lib/mw-auth-token.mjs}): every perf wallet gets the dev app PIN ({@code MW_SEED_APP_PIN})
 * through mw-auth's dev-only seed API (idempotent: a wallet with a PIN is left alone), then each user
 * binds one fresh device (binding OTP {@code POST /auth/v1/otp}, read from the SMSC simulator, signed
 * {@code POST /auth/v1/devices} with the PIN) and logs in (signed {@code POST /auth/v1/sessions}).
 * Also fetches the core's PIN key (transactions carry the core PIN until ticket 72). Needs the
 * test-only routes ({@code scripts/suite.sh perf} applies them like {@code scripts/api-test.sh}).
 */
public final class Provisioning {

  private static final Logger log = LoggerFactory.getLogger(Provisioning.class);
  /** The code of the English binding OTP SMS (mw-notify template DEVICE_BIND_OTP). */
  private static final Pattern OTP = Pattern.compile("Your Bits code to bind a new device is ([0-9]{6})");

  /** What the load needs: the core's PIN key, the users' mw-auth tokens, each app user's bound device. */
  public record Ready(PinCipher pin, Tokens tokens, Map<String, Device> devices) {}

  private Provisioning() {}

  public static Ready prepare(PerfUsers users, int parallelism) {
    Stack stack = Stack.get();
    String appPin = stack.devCredential("MW_SEED_APP_PIN");
    PinCipher pin = pinKey();
    PinCipher transport = transportKey();
    log.info("event=perf.setup.users customers={} agents={}", users.customers().size(), users.agents().size());
    seedAppPins(users.appUsers(), transport, appPin);

    log.info("event=perf.setup.devices count={}", users.appUsers().size());
    Tokens tokens = new Tokens();
    Map<String, Device> devices = new ConcurrentHashMap<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(parallelism)) {
      List<Future<?>> jobs =
          users.appUsers().stream()
              .<Future<?>>map(u -> pool.submit(() -> {
                Device d = bind(u, transport, appPin);
                tokens.put(u.username(), d, login(u, d, transport, appPin));
                devices.put(u.username(), d);
              }))
              .toList();
      for (Future<?> j : jobs) {
        j.get();
      }
    } catch (Exception e) {
      tokens.close();
      throw new IllegalStateException("binding the perf devices failed: " + e.getMessage(), e);
    }
    tokens.start();
    return new Ready(pin, tokens, Map.copyOf(devices));
  }

  private static PinCipher pinKey() {
    Http.Response r = Http.get(Stack.get().url("test/core-mock/v1/pin-public-key"), apiKey());
    if (r.status() != 200) {
      throw new IllegalStateException("PIN public key (test-only routes applied? scripts/suite.sh perf does it): " + r.status());
    }
    return PinCipher.fromPem(r.body().get("keyId").asString(), r.body().get("publicKeyPem").asString());
  }

  /** mw-auth's PIN transport key ({@code GET /auth/v1/pin-key}): the app PIN is encrypted under it. */
  private static PinCipher transportKey() {
    Http.Response r = Http.get(Stack.get().url("auth/v1/pin-key"), Map.of());
    if (r.status() != 200) {
      throw new IllegalStateException("mw-auth PIN transport key: " + r);
    }
    return PinCipher.fromPem(r.body().get("keyId").asString(), r.body().get("publicKeyPem").asString());
  }

  /**
   * The dev app PIN on every perf wallet, as mw-seeder sets it: mw-auth's dev-only {@code POST
   * /internal/v1/seed/pins} on its plain public port (dev host port {@code MW_PORT_AUTH}, default
   * 8095; never through APISIX), {@code MW_AUTH_SEED_API_ENABLED=true} in {@code deploy/.env}.
   */
  private static void seedAppPins(List<PerfUsers.User> users, PinCipher transport, String appPin) {
    ObjectNode req = Http.JSON.createObjectNode();
    ArrayNode pins = req.putArray("pins");
    for (PerfUsers.User u : users) {
      ObjectNode p = pins.addObject();
      p.put("walletId", u.msisdn());
      p.set("encryptedPin", encryptedPin(transport, appPin));
    }
    String port = Stack.get().devSetting("MW_PORT_AUTH", "8095");
    URI seed = URI.create(System.getProperty("mw.perf.auth-seed-url", "http://127.0.0.1:" + port + "/internal/v1/seed/pins"));
    Http.Response r = Http.postRaw(seed, Map.of(), req.toString());
    if (r.status() != 200) {
      throw new IllegalStateException("mw-auth seed API (MW_AUTH_SEED_API_ENABLED=true?): " + r.status());
    }
    for (JsonNode o : r.body().path("results")) {
      String outcome = o.path("outcome").asString("");
      if (!outcome.equals("CREATED") && !outcome.equals("UNCHANGED")) {
        throw new IllegalStateException("mw-auth refused the app PIN of a perf wallet: " + outcome);
      }
    }
  }

  private static ObjectNode encryptedPin(PinCipher transport, String pin) {
    return Http.JSON.createObjectNode().put("keyId", transport.keyId()).put("ciphertext", transport.encrypt(pin));
  }

  private static Device bind(PerfUsers.User user, PinCipher transport, String appPin) {
    Device d = Device.create();
    List<String> before = otpsTo(user.msisdn());
    String ask = Http.JSON.createObjectNode().put("msisdn", user.msisdn()).put("purpose", "BIND").put("locale", "en").toString();
    Http.Response asked = Http.postRaw(Stack.get().url("auth/v1/otp"), Map.of("X-Device-Id", d.id()), ask);
    if (asked.status() != 202) {
      throw new IllegalStateException("binding OTP of " + user.username() + ": " + asked.status());
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
    ObjectNode b = Http.JSON.createObjectNode().put("msisdn", user.msisdn()).put("publicKey", d.publicKeyBase64()).put("otp", otp);
    b.set("encryptedPin", encryptedPin(transport, appPin));
    String body = b.toString();
    Http.Response bound =
        Http.postRaw(Stack.get().url("auth/v1/devices"), d.signedHeaders("POST", "/auth/v1/devices", body), body);
    if (bound.status() != 201) {
      throw new IllegalStateException("binding the device of " + user.username() + ": " + bound.status());
    }
    return d;
  }

  /** {@code POST /auth/v1/sessions} on the bound device: the mw-auth {@code TokenResponse}. */
  private static JsonNode login(PerfUsers.User user, Device d, PinCipher transport, String appPin) {
    ObjectNode b = Http.JSON.createObjectNode().put("msisdn", user.msisdn());
    b.set("encryptedPin", encryptedPin(transport, appPin));
    String body = b.toString();
    Http.Response r =
        Http.postRaw(Stack.get().url("auth/v1/sessions"), d.signedHeaders("POST", "/auth/v1/sessions", body), body);
    if (r.status() != 200) {
      throw new IllegalStateException("mw-auth login of " + user.username() + ": " + r.status());
    }
    return r.body();
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
