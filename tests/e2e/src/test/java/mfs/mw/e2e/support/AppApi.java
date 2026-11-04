package mfs.mw.e2e.support;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import tools.jackson.databind.node.ObjectNode;

/**
 * The mobile app API v1 through APISIX ({@code /app/v1/*}, contracts/api/mw-app-gateway/
 * app.v1.openapi.yaml) with a customers-realm bearer token.
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

  /** {@code POST /app/v1/transactions} as {@code user} with the given Idempotency-Key. */
  public static Http.Response submit(String user, String idempotencyKey, ObjectNode body) {
    return Http.postJson(
        Stack.get().url(TRANSACTIONS),
        Map.of(
            "Authorization", "Bearer " + Keycloak.customerToken(user),
            "Idempotency-Key", idempotencyKey,
            "X-Correlation-Id", "e2e-" + UUID.randomUUID()),
        body);
  }

  /** {@code GET /app/v1/transactions/{txnId}} as {@code user}. */
  public static Http.Response status(String user, String txnId) {
    return Http.get(
        Stack.get().url(TRANSACTIONS + "/" + txnId),
        Map.of("Authorization", "Bearer " + Keycloak.customerToken(user)));
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
