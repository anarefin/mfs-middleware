package mfs.mw.e2e.support;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import tools.jackson.databind.node.ObjectNode;

/**
 * The mobile app API v1 through APISIX ({@code /app/v1/*}, contracts/api/mw-app-gateway/
 * app.v1.openapi.yaml) with a customers-realm bearer token, from the user's bound device
 * ({@link Devices}); submits are signed.
 */
public final class AppApi {

  /** Saga states after which a transaction never changes again (spec 7.2). */
  public static final Set<String> FINAL = Set.of("COMPLETED", "FAILED", "REJECTED", "REVERSED");

  private static final String TRANSACTIONS = "app/v1/transactions";

  private AppApi() {}

  /** A fresh Idempotency-Key, unique per call so reruns never collide with earlier runs. */
  public static String newIdempotencyKey() {
    return "e2e-" + UUID.randomUUID();
  }

  /**
   * A transaction request body: {@code txnType}, credit party, amount in BDT minor units and the
   * PIN encrypted with the core public key. Add flow-specific fields to the returned node.
   */
  public static ObjectNode request(String txnType, String creditPartyId, long amountMinor, String pin) {
    ObjectNode body = Http.JSON.createObjectNode();
    body.put("txnType", txnType);
    body.putObject("creditParty").put("id", creditPartyId);
    body.putObject("amount").put("valueMinor", amountMinor).put("currency", "BDT");
    ObjectNode encrypted = body.putObject("encryptedPin");
    CoreMock.encryptPin(pin).forEach(encrypted::put);
    return body;
  }

  /** A submit exactly as sent: the body text and every header, signed by the user's device. */
  public record SignedSubmit(String body, Map<String, String> headers) {}

  /**
   * Builds {@code POST /app/v1/transactions} as {@code user} with the given Idempotency-Key, signed
   * by the user's bound device (ticket 29: binds it on first use); send it with {@link #send}.
   */
  public static SignedSubmit prepare(String user, String idempotencyKey, ObjectNode body) {
    String json = Http.JSON.writeValueAsString(body);
    Map<String, String> headers = new LinkedHashMap<>(Devices.signed(user, "POST", "/" + TRANSACTIONS, json));
    headers.put("Idempotency-Key", idempotencyKey);
    headers.put("X-Correlation-Id", "e2e-" + UUID.randomUUID());
    return new SignedSubmit(json, headers);
  }

  /** Sends a prepared submit (again: the same bytes, nonce and signature, for replay tests). */
  public static Http.Response send(SignedSubmit submit) {
    return Http.postRaw(Stack.get().url(TRANSACTIONS), submit.headers(), submit.body());
  }

  /** {@code POST /app/v1/transactions} as {@code user} with the given Idempotency-Key, signed. */
  public static Http.Response submit(String user, String idempotencyKey, ObjectNode body) {
    return send(prepare(user, idempotencyKey, body));
  }

  /** {@code GET /app/v1/billers} as {@code user}. */
  public static Http.Response billers(String user) {
    return Http.get(Stack.get().url("app/v1/billers"), Devices.headers(user));
  }

  /** {@code POST /app/v1/billers/{code}/fetch-bill} as {@code user}. */
  public static Http.Response fetchBill(String user, String billerCode, String billNumber) {
    return Http.postJson(
        Stack.get().url("app/v1/billers/" + billerCode + "/fetch-bill"),
        withCorrelationId(Devices.headers(user)),
        Http.JSON.createObjectNode().put("billNumber", billNumber));
  }

  /** {@code GET /app/v1/transactions/{txnId}} as {@code user}. */
  public static Http.Response status(String user, String txnId) {
    return Http.get(Stack.get().url(TRANSACTIONS + "/" + txnId), Devices.headers(user));
  }

  private static Map<String, String> withCorrelationId(Map<String, String> headers) {
    Map<String, String> h = new LinkedHashMap<>(headers);
    h.put("X-Correlation-Id", "e2e-" + UUID.randomUUID());
    return h;
  }

  /**
   * Polls the status view until {@code until} holds (the view is projected from Kafka, so it
   * trails the synchronous answer slightly); returns the last response, matching or not.
   */
  public static Http.Response pollStatus(
      String user, String txnId, Predicate<Http.Response> until, Duration timeout) {
    return Waits.until(() -> status(user, txnId), until, timeout);
  }
}
