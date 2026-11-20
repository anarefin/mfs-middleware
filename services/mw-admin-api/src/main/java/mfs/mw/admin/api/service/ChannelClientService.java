package mfs.mw.admin.api.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import mfs.mw.admin.api.dto.AdminRole;
import mfs.mw.admin.api.dto.ChangeRequestView;
import mfs.mw.admin.api.dto.ChannelClientChangeBody;
import mfs.mw.admin.api.dto.ChannelClientSpec;
import mfs.mw.admin.api.dto.ChannelClientView;
import mfs.mw.admin.api.dto.ErrorDetail;
import mfs.mw.admin.api.dto.Operator;
import mfs.mw.admin.api.entity.ChannelClient;
import mfs.mw.admin.api.exception.ErrorCodes;
import mfs.mw.admin.api.exception.ForbiddenException;
import mfs.mw.admin.api.exception.MwException;
import mfs.mw.admin.api.mapper.ChannelClientMapper;
import mfs.mw.admin.api.mapper.PartnerMapper;
import mfs.mw.admin.api.repository.ChannelClientRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The channel client registry (ticket 100a, FRS PLT-003, PLT-004 quota part, ADM-03 API part):
 * reads and the maker side of a {@code channel_client} change request (the APISIX projection of
 * the committed rows is {@link ChannelClientProjection}). A maker
 * ({@link AdminRole#CHANNEL_CLIENT_MAKER}) files UPSERT with the full definition; nothing changes
 * until a different checker approves ({@link ChannelClientChangeHandler}).
 */
@Service
public class ChannelClientService {

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9-]{1,63}$");
  static final Pattern ROUTE_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");
  /** An IPv4 / IPv6 address or CIDR, in the characters APISIX's ip-restriction accepts. */
  static final Pattern ADDRESS = Pattern.compile("^[0-9A-Fa-f.:]{2,39}(/[0-9]{1,3})?$");

  private final ChannelClientRepository clients;
  private final ChangeRequestService changeRequests;
  private final JsonMapper json;

  public ChannelClientService(
      ChannelClientRepository clients, ChangeRequestService changeRequests, JsonMapper json) {
    this.clients = clients;
    this.changeRequests = changeRequests;
    this.json = json;
  }

  // --- reads ----------------------------------------------------------------------------------

  public ChannelClientView.Page list(Operator operator) {
    requireReader(operator);
    return new ChannelClientView.Page(clients.list().stream().map(ChannelClientMapper::view).toList());
  }

  public ChannelClientView get(Operator operator, String clientId) {
    requireReader(operator);
    return clients.find(checkedId(clientId)).map(ChannelClientMapper::view).orElseThrow(ChannelClientService::notFound);
  }

  // --- maker ----------------------------------------------------------------------------------

  public ChangeRequestView request(Operator maker, String clientId, ChannelClientChangeBody body) {
    if (!maker.hasAny(AdminRole.CHANNEL_CLIENT_MAKER)) {
      throw new ForbiddenException("Asking for a channel client change needs the ops-maker or integration-maker role");
    }
    String id = checkedId(clientId);
    ChannelClientSpec next = body.client();
    List<ErrorDetail> problems = new ArrayList<>();
    validate(next, problems);
    Optional<ChannelClient> held = clients.find(id);
    held.ifPresent(h -> {
      if (h.spec().type() != next.type()) {
        problems.add(new ErrorDetail("client.type", "the type of a client cannot change"));
      }
      if (h.spec().authMethod() != next.authMethod()) {
        problems.add(new ErrorDetail("client.authMethod", "the auth method of a client cannot change (register a new client)"));
      }
    });
    rejectAuthRouteClash(id, next, clients.list(), problems);
    if (!problems.isEmpty()) {
      throw new MwException(ErrorCodes.VAL_INVALID, "The channel client is not valid").withDetails(List.copyOf(problems));
    }
    Map<String, Object> before = held.map(h -> asMap(h.spec())).orElse(Map.of());
    Map<String, Object> after = asMap(next);
    Map<String, Object> diff = PartnerMapper.diff(before, after);
    if (held.isPresent() && diff.get("after") instanceof Map<?, ?> a && a.isEmpty()
        && diff.get("before") instanceof Map<?, ?> b && b.isEmpty()) {
      throw new MwException(ErrorCodes.VAL_ACTION_NOT_ALLOWED, "The change would change nothing")
          .withStatus(HttpStatus.CONFLICT);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    held.ifPresent(h -> payload.put("baseVersion", h.version()));
    payload.put("client", after);
    return changeRequests.open(maker, ChannelClient.RESOURCE_TYPE, id, body.action().name(), payload, diff, body.reason());
  }

  // --- rules ----------------------------------------------------------------------------------

  /** What the contract's patterns say (the derived body schema keeps no patterns). */
  static void validate(ChannelClientSpec s, List<ErrorDetail> problems) {
    for (String r : s.allowedRoutes()) {
      if (r == null || !ROUTE_ID.matcher(r).matches()) {
        problems.add(new ErrorDetail("client.allowedRoutes", "route ids are lower-case letters, digits and hyphens"));
        break;
      }
    }
    for (String a : s.ipAllowList()) {
      if (a == null || !ADDRESS.matcher(a).matches()) {
        problems.add(new ErrorDetail("client.ipAllowList", "entries are IP addresses or CIDRs"));
        break;
      }
    }
    if (s.allowedRoutes().stream().distinct().count() != s.allowedRoutes().size()
        || s.ipAllowList().stream().distinct().count() != s.ipAllowList().size()) {
      problems.add(new ErrorDetail("client", "list entries must be unique"));
    }
  }

  /**
   * Two auth methods never share a route (ticket 100b). An OAUTH2_CC client on {@code
   * partner-billagg-callback} would make that HMAC callback require a bearer token.
   */
  static void rejectAuthRouteClash(
      String clientId, ChannelClientSpec next, List<ChannelClient> existing, List<ErrorDetail> problems) {
    for (ChannelClient other : existing) {
      if (other.clientId().equals(clientId) || other.spec().authMethod() == next.authMethod()) {
        continue;
      }
      for (String route : next.allowedRoutes()) {
        if (other.spec().allowedRoutes().contains(route)) {
          problems.add(new ErrorDetail(
              "client.allowedRoutes",
              "route " + route + " is already used by " + other.clientId() + " (" + other.spec().authMethod() + ")"));
          return;
        }
      }
    }
  }

  private Map<String, Object> asMap(ChannelClientSpec spec) {
    return json.convertValue(spec, MAP);
  }

  private static String checkedId(String clientId) {
    if (clientId == null || !ID.matcher(clientId).matches()) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Invalid channel client id")
          .withDetails(List.of(new ErrorDetail("clientId", "lower-case letters, digits and hyphens, 2 to 64")));
    }
    return clientId;
  }

  private static void requireReader(Operator operator) {
    if (!operator.hasAny(AdminRole.READ_CHANNEL_CLIENTS)) {
      throw new ForbiddenException("This operation needs a partner viewing role");
    }
  }

  static MwException notFound() {
    return new MwException(ErrorCodes.VAL_NOT_FOUND, "No such channel client").withStatus(HttpStatus.NOT_FOUND);
  }
}
