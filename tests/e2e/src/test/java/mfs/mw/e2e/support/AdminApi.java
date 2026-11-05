package mfs.mw.e2e.support;

import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * The ops admin API through APISIX ({@code /admin/v1/*}, realm ops; tickets 36, 37): transaction
 * detail, the maker-checker change requests on a transaction, USSD menu and notify template versions
 * (ticket 39).
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

  /** A new version of a USSD menu (ticket 39): {@code body} = {menu, activations, reason}. */
  public static Http.Response proposeMenu(String user, String menuId, JsonNode body) {
    return Http.postJson(Stack.get().url("admin/v1/ussd-menus/" + menuId + "/change-requests"), auth(user), body);
  }

  /** A new version of a notify template (ticket 39): {@code body} = {body, reason, ...}. */
  public static Http.Response proposeTemplate(String user, String code, String channel, String locale, JsonNode body) {
    return Http.postJson(
        Stack.get().url("admin/v1/notify-templates/" + code + "/" + channel + "/" + locale + "/change-requests"),
        auth(user), body);
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
