package mfs.mw.e2e.support;

import java.util.Map;

/**
 * The ops admin API through APISIX ({@code /admin/v1/*}, realm ops; tickets 36, 37): transaction
 * detail and the maker-checker change requests on a transaction.
 */
public final class AdminApi {

  private AdminApi() {}

  private static Map<String, String> auth(String user) {
    return Map.of("Authorization", "Bearer " + Keycloak.opsToken(user));
  }

  public static Http.Response transaction(String user, String txnId) {
    return Http.get(Stack.get().url("admin/v1/transactions/" + txnId), auth(user));
  }

  public static Http.Response ask(String user, String txnId, String action, String reason) {
    return Http.postJson(
        Stack.get().url("admin/v1/transactions/" + txnId + "/change-requests"),
        auth(user),
        Http.JSON.createObjectNode().put("action", action).put("reason", reason));
  }

  public static Http.Response changeRequest(String user, String id) {
    return Http.get(Stack.get().url("admin/v1/change-requests/" + id), auth(user));
  }

  public static Http.Response approve(String user, String id) {
    return Http.postText(Stack.get().url("admin/v1/change-requests/" + id + "/approve"), auth(user), "application/json", "");
  }

  public static Http.Response reject(String user, String id, String reason) {
    return Http.postJson(
        Stack.get().url("admin/v1/change-requests/" + id + "/reject"),
        auth(user),
        Http.JSON.createObjectNode().put("reason", reason));
  }
}
