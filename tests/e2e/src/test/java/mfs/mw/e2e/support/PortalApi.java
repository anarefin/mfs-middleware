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
