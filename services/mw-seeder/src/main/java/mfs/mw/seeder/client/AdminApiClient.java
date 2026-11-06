package mfs.mw.seeder.client;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import mfs.mw.seeder.config.SeederProperties;
import mfs.mw.seeder.dto.ChangeRequestView;
import mfs.mw.seeder.dto.ContentVersionView;
import mfs.mw.seeder.dto.MenuProposal;
import mfs.mw.seeder.dto.PartnerChangeRequest;
import mfs.mw.seeder.dto.PartnerView;
import mfs.mw.seeder.dto.TemplateProposal;
import org.springframework.stereotype.Component;

/**
 * mw-admin-api's ops API ({@code contracts/api/mw-admin-api/admin.v1.openapi.yaml}), called inside
 * the stack network (not through APISIX) with a bearer token of the acting ops persona.
 */
@Component
public class AdminApiClient implements AdminConsole {

  private final HttpJson http;
  private final OpsTokenClient tokens;
  private final URI base;

  public AdminApiClient(HttpJson http, OpsTokenClient tokens, SeederProperties props) {
    this.http = http;
    this.tokens = tokens;
    this.base = props.endpoints().adminApi();
  }

  @Override
  public Optional<PartnerView> partner(String asUser, String partnerId) {
    String call = "GET /admin/v1/partners/{partnerId}";
    HttpJson.Response r = as(asUser, () -> http.get(call, uri("/admin/v1/partners/" + enc(partnerId)), auth(asUser)));
    if (r.status() == 404) {
      return Optional.empty();
    }
    return Optional.of(http.read(call, HttpJson.expect(call, r, 200), PartnerView.class));
  }

  @Override
  public ChangeRequestView requestPartnerChange(String asUser, String partnerId, PartnerChangeRequest request) {
    return post(asUser, "POST /admin/v1/partners/{partnerId}/change-requests",
        "/admin/v1/partners/" + enc(partnerId) + "/change-requests", request, 201);
  }

  @Override
  public List<ChangeRequestView> pendingRequests(String asUser, String resourceType, String resourceId) {
    String call = "GET /admin/v1/change-requests";
    URI u =
        uri("/admin/v1/change-requests?status=PENDING&limit=200&resourceType=" + enc(resourceType) + "&resourceId="
            + enc(resourceId));
    HttpJson.Response r = HttpJson.expect(call, as(asUser, () -> http.get(call, u, auth(asUser))), 200);
    return http.read(call, r, ChangeRequestView.Page.class).items();
  }

  @Override
  public ChangeRequestView approve(String asUser, String changeRequestId) {
    return post(asUser, "POST /admin/v1/change-requests/{id}/approve",
        "/admin/v1/change-requests/" + enc(changeRequestId) + "/approve", null, 200);
  }

  @Override
  public List<ContentVersionView> menuVersions(String asUser, String menuId) {
    return versions(asUser, "GET /admin/v1/ussd-menus/{menuId}/versions", "/admin/v1/ussd-menus/" + enc(menuId) + "/versions");
  }

  @Override
  public ChangeRequestView proposeMenu(String asUser, String menuId, MenuProposal proposal) {
    return post(asUser, "POST /admin/v1/ussd-menus/{menuId}/change-requests",
        "/admin/v1/ussd-menus/" + enc(menuId) + "/change-requests", proposal, 201);
  }

  @Override
  public List<ContentVersionView> templateVersions(String asUser, String code, String channel, String locale) {
    return versions(asUser, "GET /admin/v1/notify-templates/{code}/{channel}/{locale}/versions", template(code, channel, locale) + "/versions");
  }

  @Override
  public ChangeRequestView proposeTemplate(
      String asUser, String code, String channel, String locale, TemplateProposal proposal) {
    return post(asUser, "POST /admin/v1/notify-templates/{code}/{channel}/{locale}/change-requests",
        template(code, channel, locale) + "/change-requests", proposal, 201);
  }

  private List<ContentVersionView> versions(String asUser, String call, String path) {
    URI u = uri(path + "?limit=200");
    HttpJson.Response r = HttpJson.expect(call, as(asUser, () -> http.get(call, u, auth(asUser))), 200);
    return http.read(call, r, ContentVersionView.Page.class).items();
  }

  private ChangeRequestView post(String asUser, String call, String path, Object body, int ok) {
    URI u = uri(path);
    HttpJson.Response r = as(asUser, () -> http.sendJson(call, "POST", u, auth(asUser), body));
    return http.read(call, HttpJson.expect(call, r, ok), ChangeRequestView.class);
  }

  /** Runs the call; a 401 drops the persona's token and runs it once more with a fresh one. */
  private HttpJson.Response as(String asUser, Supplier<HttpJson.Response> send) {
    HttpJson.Response r = send.get();
    if (r.status() == 401) {
      tokens.invalidate(asUser);
      r = send.get();
    }
    return r;
  }

  private Map<String, String> auth(String asUser) {
    return Map.of("Authorization", "Bearer " + tokens.token(asUser));
  }

  private static String template(String code, String channel, String locale) {
    return "/admin/v1/notify-templates/" + enc(code) + "/" + enc(channel) + "/" + enc(locale);
  }

  private URI uri(String path) {
    return base.resolve(path);
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
}
