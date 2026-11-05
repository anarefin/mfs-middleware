package mfs.mw.e2e.support;

import java.util.Map;

/**
 * Org portal bulk API through APISIX ({@code /portal/org/v1/*}, realm portals; ticket 33):
 * upload a CSV, read, approve and reject batches.
 */
public final class PortalApi {

  private static final String BATCHES = "portal/org/v1/bulk/batches";

  private PortalApi() {}

  private static Map<String, String> auth(String user) {
    return Map.of("Authorization", "Bearer " + Keycloak.portalToken(user));
  }

  public static Http.Response upload(String user, String csv) {
    return Http.postText(Stack.get().url(BATCHES), auth(user), "text/csv", csv);
  }

  public static Http.Response get(String user, String batchId) {
    return Http.get(Stack.get().url(BATCHES + "/" + batchId), auth(user));
  }

  public static Http.Response approve(String user, String batchId) {
    return Http.postText(Stack.get().url(BATCHES + "/" + batchId + "/approve"), auth(user), "application/json", "");
  }

  public static Http.Response reject(String user, String batchId, String reason) {
    return Http.postJson(
        Stack.get().url(BATCHES + "/" + batchId + "/reject"),
        auth(user),
        Http.JSON.createObjectNode().put("reason", reason));
  }

  public static Http.Response list(String user) {
    return Http.get(Stack.get().url(BATCHES), auth(user));
  }

  /** The org's batches of one status (a contract BatchStatus name). */
  public static Http.Response list(String user, String status) {
    return Http.get(Stack.get().url(BATCHES + "?status=" + status), auth(user));
  }

  public static Http.Response pause(String user, String batchId) {
    return Http.postText(Stack.get().url(BATCHES + "/" + batchId + "/pause"), auth(user), "application/json", "");
  }

  public static Http.Response resume(String user, String batchId) {
    return Http.postText(Stack.get().url(BATCHES + "/" + batchId + "/resume"), auth(user), "application/json", "");
  }

  /** The result file (CSV) of a batch (ticket 34). */
  public static Http.Response result(String user, String batchId) {
    return Http.get(Stack.get().url(BATCHES + "/" + batchId + "/result"), auth(user));
  }

  private static final String TXNS = "portal/org/v1/transactions";

  /** The SP transaction query (ticket 35): the org's own transactions, optionally of one batch. */
  public static Http.Response transactions(String user, String batchId) {
    return Http.get(Stack.get().url(TXNS + (batchId == null ? "" : "?batchId=" + batchId)), auth(user));
  }

  /** One of the org's transactions with its timeline (ticket 35). */
  public static Http.Response transaction(String user, String txnId) {
    return Http.get(Stack.get().url(TXNS + "/" + txnId), auth(user));
  }

  /**
   * A valid file of {@code rows} payouts of {@code taka} each to the core mock's seeded customers
   * {@code 01711000001..01711001000} in turn (row i pays customer ((i - 1) mod 1000) + 1), fresh
   * references (ticket 34).
   */
  public static String payoutFile(int rows, String taka) {
    String run = Long.toString(System.nanoTime(), 36);
    StringBuilder b = new StringBuilder("msisdn,amount,reference\n");
    for (int i = 1; i <= rows; i++) {
      b.append("0171100").append(String.format("%04d", (i - 1) % 1000 + 1)).append(',').append(taka)
          .append(",PAY-").append(run).append('-').append(i).append('\n');
    }
    return b.toString();
  }

  /** A valid file of {@code rows} payees (1.00 taka each), references unique per call. */
  public static String file(int rows) {
    String run = Long.toString(System.nanoTime(), 36);
    StringBuilder b = new StringBuilder("msisdn,amount,reference\n");
    for (int i = 1; i <= rows; i++) {
      b.append("0171").append(String.format("%07d", i)).append(",1.00,E2E-").append(run).append('-').append(i).append('\n');
    }
    return b.toString();
  }
}
