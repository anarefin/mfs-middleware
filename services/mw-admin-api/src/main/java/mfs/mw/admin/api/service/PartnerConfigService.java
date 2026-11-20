package mfs.mw.admin.api.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import mfs.mw.admin.api.dto.ChangeRequestView;
import mfs.mw.admin.api.dto.ErrorDetail;
import mfs.mw.admin.api.dto.ErrorMapChangeBody;
import mfs.mw.admin.api.dto.Operator;
import mfs.mw.admin.api.dto.PartnerChangeRequestBody;
import mfs.mw.admin.api.dto.PartnerChangeRequestBody.PartnerAction;
import mfs.mw.admin.api.dto.PartnerConfigValue;
import mfs.mw.admin.api.dto.PartnerView;
import mfs.mw.admin.api.entity.Partner;
import mfs.mw.admin.api.exception.ErrorCodes;
import mfs.mw.admin.api.exception.MwException;
import mfs.mw.admin.api.mapper.PartnerMapper;
import mfs.mw.admin.api.repository.PartnerRepository;
import mfs.mw.admin.api.dto.AdminRole;
import mfs.mw.admin.api.exception.ForbiddenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Partner config (ticket 38, spec 15.2 Partners, 20.4): reads, and the maker side of a partner
 * change request. A maker ({@link AdminRole#PARTNER_MAKER}) asks to ENABLE, DISABLE, SET_TIMEOUTS,
 * SET_MAINTENANCE, SET_ENDPOINT or UPSERT_ROUTE (full config, a partner route of ADR D28); the
 * request stores the full config the approval will publish ({@code payload.config}), the version
 * the maker saw ({@code payload.baseVersion}, null for a new partner) and the changed fields
 * ({@code diff}). Nothing changes until a checker approves ({@link PartnerChangeHandler}). The
 * config is checked here with the adapters' structural and security rules that need no adapter
 * config (ids, the route rules, secret names, suffixes, URL shape, the maintenance window, the
 * endpoint base URL); the adapters check it again (host allowlist, https, JSONata, dry-run) and
 * keep the old version when they refuse it.
 *
 * <p>Only UPSERT_ROUTE adds a dry-run (a new route must prove itself first, ticket 83). ENABLE,
 * DISABLE, SET_TIMEOUTS, SET_MAINTENANCE and SET_ENDPOINT on a proven route (a seed, or a version
 * reported LIVE) publish it without one (ticket 84): the route already runs, so the adapter swaps
 * the new version in at once. On a route that has not passed its dry-run yet (PENDING_DRY_RUN or
 * REJECTED) they keep its dry-run, so the gate still applies to it (review of ticket 84).
 * SET_MAINTENANCE and SET_ENDPOINT (ticket 131b) take no route, mappings or family: they change the
 * window (and the maintenance flag) or the endpoint profile on the live config and republish the rest.
 */
@Service
public class PartnerConfigService {

  private static final Logger log = LoggerFactory.getLogger(PartnerConfigService.class);
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  /** Instance ids and secret names, as the adapters accept them. */
  static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

  static final Pattern SAFE_PATH = Pattern.compile("^/[A-Za-z0-9._~/{}-]*$");
  static final Pattern SUFFIX = Pattern.compile("^[A-Z0-9_]+$");

  /**
   * An endpoint base URL (ticket 131b, the adapters' {@code PartnerConfig.ENDPOINT_URL}): http(s),
   * host, port, plain path segments; nothing that could change the meaning of the route it is
   * written into.
   */
  static final Pattern ENDPOINT_URL =
      Pattern.compile("^https?://[A-Za-z0-9.-]{1,253}(:[0-9]{1,5})?(/[A-Za-z0-9._~-]+)*/?$");

  /** The longest maintenance message per language (the adapters' rule). */
  static final int MAX_MAINTENANCE_MESSAGE = 500;
  static final Set<String> FAMILIES = Set.of("telco", "billpay", "bank", "merchant", "card", "remit", "atm");
  static final Set<String> AUTH_SCHEMES = Set.of("none", "api-key", "hmac-sha256");
  static final Set<String> DRY_RUN_OPERATIONS = Set.of("PAY", "INQUIRY", "STATUS_QUERY");
  static final Pattern MAPPING_NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");

  private final PartnerRepository partners;
  private final ChangeRequestService changeRequests;
  private final JsonMapper json;
  private final Clock clock;
  private final PartnerRouteValidator routes;

  public PartnerConfigService(
      PartnerRepository partners, ChangeRequestService changeRequests, JsonMapper json, Clock clock,
      PartnerRouteValidator routes) {
    this.partners = partners;
    this.changeRequests = changeRequests;
    this.json = json;
    this.clock = clock;
    this.routes = routes;
  }

  // --- reads ----------------------------------------------------------------------------------

  public PartnerView.Page list(Operator operator) {
    requireReader(operator);
    return new PartnerView.Page(partners.list().stream().map(PartnerMapper::view).toList());
  }

  public PartnerView get(Operator operator, String partnerId) {
    requireReader(operator);
    return partners.find(checkedId(partnerId)).map(PartnerMapper::view).orElseThrow(PartnerConfigService::notFound);
  }

  /**
   * Kamelet usage (ticket 83): for each Kamelet of the library, the partners whose newest LIVE
   * route version uses it, from the adapters' status reports.
   */
  public PartnerView.KameletUsage kameletUsage(Operator operator) {
    requireReader(operator);
    List<PartnerRepository.LiveRoute> live = partners.liveRoutes();
    java.util.Set<String> names = new java.util.TreeSet<>(routes.kamelets());
    live.forEach(r -> names.addAll(r.kamelets()));
    return new PartnerView.KameletUsage(names.stream()
        .map(name -> new PartnerView.KameletUsage.Kamelet(name, live.stream()
            .filter(r -> r.kamelets().contains(name))
            .map(r -> new PartnerView.KameletUsage.RouteRef(r.partnerId(), r.version()))
            .toList()))
        .toList());
  }

  // --- maker ----------------------------------------------------------------------------------

  public ChangeRequestView request(Operator maker, String partnerId, PartnerChangeRequestBody body) {
    if (!maker.hasAny(AdminRole.PARTNER_MAKER)) {
      throw new ForbiddenException("Asking for a partner change needs the ops-maker or integration-maker role");
    }
    String id = checkedId(partnerId);
    Optional<Partner> held = partners.find(id);
    PartnerAction action = body.action();
    List<ErrorDetail> problems = new ArrayList<>();
    unexpected(body, action, problems);
    String family;
    PartnerConfigValue next;
    PartnerConfigValue base;
    if (action == PartnerAction.UPSERT_ROUTE) {
      if (body.config() == null) {
        problems.add(new ErrorDetail("config", action + " needs the full config"));
      }
      if (held.isEmpty() && body.family() == null) {
        problems.add(new ErrorDetail("family", "a new partner needs its family"));
      }
      if (held.isPresent() && body.family() != null && !held.get().family().equals(body.family())) {
        problems.add(new ErrorDetail("family", "the family of a partner cannot change"));
      }
      refuseIfAny(problems);
      family = held.map(Partner::family).orElse(body.family());
      PartnerConfigValue c = body.config();
      // a config that leaves enabled out keeps the partner's state (a new one starts enabled)
      next = c.enabled() != null ? c : c.withEnabled(held.map(p -> p.config().isEnabled()).orElse(true));
      base = held.map(Partner::config).orElse(null);
    } else if (action == PartnerAction.SET_MAINTENANCE || action == PartnerAction.SET_ENDPOINT) {
      refuseIfAny(problems);
      Partner p = held.orElseThrow(PartnerConfigService::notFound);
      family = p.family();
      base = proven(p) ? p.config().withoutDryRun() : p.config();
      next = action == PartnerAction.SET_MAINTENANCE ? maintenanceOf(base, body.config()) : endpointOf(base, body.config());
    } else {
      refuseIfAny(problems);
      Partner p = held.orElseThrow(PartnerConfigService::notFound);
      family = p.family();
      base = proven(p) ? p.config().withoutDryRun() : p.config();
      next =
          switch (action) {
            case ENABLE -> base.withEnabled(true);
            case DISABLE -> base.withEnabled(false);
            case SET_TIMEOUTS ->
                base.withTimeouts(body.timeouts().connectTimeoutMs(), body.timeouts().timeoutMs(), body.timeouts().readTimeoutMs());
            case UPSERT_ROUTE, SET_MAINTENANCE, SET_ENDPOINT -> throw new IllegalStateException("handled above");
          };
    }
    List<ErrorDetail> route = new ArrayList<>();
    validateRoute(id, next, action == PartnerAction.UPSERT_ROUTE, problems, route);
    refuseIfAny(problems);
    if (!route.isEmpty()) {
      throw new MwException(ErrorCodes.VAL_ROUTE_INVALID, "The partner route breaks the route rules")
          .withDetails(List.copyOf(route));
    }
    refuseIfAny(problems);
    Map<String, Object> before = base == null ? Map.of() : asMap(base);
    Map<String, Object> after = asMap(next);
    Map<String, Object> diff = PartnerMapper.diff(before, after);
    if (diff.get("after") instanceof Map<?, ?> a && a.isEmpty() && held.isPresent()) {
      throw new MwException(ErrorCodes.VAL_ACTION_NOT_ALLOWED, "The change would change nothing")
          .withStatus(HttpStatus.CONFLICT);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("family", family);
    if (held.isPresent()) {
      payload.put("baseVersion", held.get().version());
    }
    payload.put("config", after);
    return changeRequests.open(maker, Partner.RESOURCE_TYPE, id, action.name(), payload, diff, body.reason());
  }

  /** {@code ChangeRequest.resourceType} of an error-map change (ticket 131, FRS ADM-08). */
  public static final String ERROR_MAP_RESOURCE_TYPE = "partner_error_map";

  /**
   * The partner-code -> canonical-code map of an existing partner (ticket 131, FRS ADM-08): its own
   * change request type ({@link PartnerErrorMapChangeHandler}), with the partner request's payload
   * shape (family, baseVersion, the full config with the new map), so the approval publishes the
   * config as the next version exactly as a SET_TIMEOUTS does (no dry-run on a proven route).
   */
  public ChangeRequestView requestErrorMap(Operator maker, String partnerId, ErrorMapChangeBody body) {
    if (!maker.hasAny(AdminRole.PARTNER_MAKER)) {
      throw new ForbiddenException("Asking for a partner change needs the ops-maker or integration-maker role");
    }
    Partner p = partners.find(checkedId(partnerId)).orElseThrow(PartnerConfigService::notFound);
    PartnerConfigValue base = proven(p) ? p.config().withoutDryRun() : p.config();
    PartnerConfigValue next = base.withErrorMap(body.errorMap(), body.ambiguousCodes());
    List<ErrorDetail> problems = new ArrayList<>();
    common(next, problems);
    if (next.ambiguousCodes() != null && next.ambiguousCodes().stream().anyMatch(c -> c == null || c.isBlank())) {
      problems.add(new ErrorDetail("ambiguousCodes", "codes must not be blank"));
    }
    refuseIfAny(problems);
    Map<String, Object> diff = PartnerMapper.diff(errorMapSide(base), errorMapSide(next));
    if (diff.get("after") instanceof Map<?, ?> a && a.isEmpty() && diff.get("before") instanceof Map<?, ?> b && b.isEmpty()) {
      throw new MwException(ErrorCodes.VAL_ACTION_NOT_ALLOWED, "The change would change nothing")
          .withStatus(HttpStatus.CONFLICT);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("family", p.family());
    payload.put("baseVersion", p.version());
    payload.put("config", asMap(next));
    return changeRequests.open(maker, ERROR_MAP_RESOURCE_TYPE, p.id(), body.action().name(), payload, diff, body.reason());
  }

  private Map<String, Object> errorMapSide(PartnerConfigValue c) {
    Map<String, Object> all = asMap(c);
    Map<String, Object> side = new LinkedHashMap<>();
    for (String k : List.of("errorMap", "ambiguousCodes")) {
      if (all.get(k) != null) {
        side.put(k, all.get(k));
      }
    }
    return side;
  }

  /**
   * True when the partner's current route already runs: a seed (the adapters' own) or a version its
   * adapter reported LIVE. A version still PENDING_DRY_RUN or REJECTED is not proven, so an ENABLE,
   * DISABLE or SET_TIMEOUTS on it keeps its dry-run (review of ticket 84: dropping it would skip the
   * dry-run gate). A pending version without a dry-run (one of these actions on a proven route, or a
   * rollback) has none to keep.
   */
  private static boolean proven(Partner p) {
    return p.route() == null || "LIVE".equals(p.route().status());
  }

  // --- seeds ----------------------------------------------------------------------------------

  /**
   * Inserts a seed partner (version 0, never published) unless one with its id exists: the
   * admin's copy of a config the adapters already hold as their own seed (dev profile).
   *
   * @return true when inserted
   */
  public boolean seed(String partnerId, String family, PartnerConfigValue config) {
    String id = checkedId(partnerId);
    List<ErrorDetail> problems = new ArrayList<>();
    if (!FAMILIES.contains(family)) {
      problems.add(new ErrorDetail("family", "unknown family"));
    }
    List<ErrorDetail> route = new ArrayList<>();
    validateRoute(id, config, false, problems, route);
    problems.addAll(route);
    if (!problems.isEmpty()) {
      throw new IllegalStateException("partner seed " + id + " is invalid: " + problems);
    }
    if (partners.find(id).isPresent()) {
      return false;
    }
    try {
      Partner seed = new Partner(id, family, 0, config, clock.instant(), "seed", null);
      partners.insert(seed);
      partners.insertSeedRoute(seed, clock.instant());
    } catch (DuplicateKeyException e) {
      return false;
    }
    log.atInfo().addKeyValue("event", "partner.seeded").addKeyValue("partnerId", id).log("partner seed stored");
    return true;
  }

  // --- rules ----------------------------------------------------------------------------------

  Map<String, Object> asMap(PartnerConfigValue config) {
    return json.convertValue(config, MAP);
  }

  /** The maintenance flag and window on the live config; a field the body sets replaces that field. */
  private static PartnerConfigValue maintenanceOf(PartnerConfigValue base, PartnerConfigValue patch) {
    return base.withMaintenance(
        patch.maintenance() != null ? patch.maintenance() : base.maintenance(),
        patch.maintenanceWindow() != null ? patch.maintenanceWindow() : base.maintenanceWindow());
  }

  /** The endpoint profile and its URLs on the live config. The body carries both. */
  private static PartnerConfigValue endpointOf(PartnerConfigValue base, PartnerConfigValue patch) {
    return base.withEndpoint(patch.endpoint(), patch.endpoints());
  }

  /** Fields that do not belong to the action are refused rather than ignored. */
  private static void unexpected(PartnerChangeRequestBody body, PartnerAction action, List<ErrorDetail> problems) {
    boolean upsert = action == PartnerAction.UPSERT_ROUTE;
    boolean patch = action == PartnerAction.SET_MAINTENANCE || action == PartnerAction.SET_ENDPOINT;
    if (!upsert && !patch && body.config() != null) {
      problems.add(new ErrorDetail("config", "only UPSERT_ROUTE, SET_MAINTENANCE and SET_ENDPOINT take a config"));
    }
    if (!upsert && body.family() != null) {
      problems.add(new ErrorDetail("family", "only UPSERT_ROUTE takes a family"));
    }
    if (action == PartnerAction.SET_TIMEOUTS && body.timeouts() == null) {
      problems.add(new ErrorDetail("timeouts", "SET_TIMEOUTS needs timeouts"));
    }
    if (action != PartnerAction.SET_TIMEOUTS && body.timeouts() != null) {
      problems.add(new ErrorDetail("timeouts", "only SET_TIMEOUTS takes timeouts"));
    }
    if (!patch) {
      return;
    }
    if (body.config() == null) {
      problems.add(new ErrorDetail("config", action + " needs a config"));
      return;
    }
    ownFieldsOnly(action, body.config(), problems);
  }

  /**
   * SET_MAINTENANCE and SET_ENDPOINT change their own fields of the live config. A route, its
   * mappings, a family (checked by the caller) or any other config field is a route edit.
   */
  private static void ownFieldsOnly(PartnerAction action, PartnerConfigValue c, List<ErrorDetail> problems) {
    if (c.route() != null) {
      problems.add(new ErrorDetail("config.route", action + " does not take a route"));
    }
    if (c.mappings() != null) {
      problems.add(new ErrorDetail("config.mappings", action + " does not take mappings"));
    }
    if (action == PartnerAction.SET_MAINTENANCE) {
      if (c.maintenance() == null && c.maintenanceWindow() == null) {
        problems.add(new ErrorDetail("config.maintenance", "SET_MAINTENANCE needs the flag or the window"));
      }
      if (c.endpoint() != null || c.endpoints() != null) {
        problems.add(new ErrorDetail("config.endpoint", "SET_MAINTENANCE does not take an endpoint"));
      }
    } else {
      if (c.endpoint() == null) {
        problems.add(new ErrorDetail("config.endpoint", "SET_ENDPOINT needs MOCK or REAL"));
      }
      if (c.endpoints() == null) {
        problems.add(new ErrorDetail("config.endpoints", "SET_ENDPOINT needs the endpoints"));
      }
      if (c.maintenance() != null || c.maintenanceWindow() != null) {
        problems.add(new ErrorDetail("config.maintenance", "SET_ENDPOINT does not take a maintenance window"));
      }
    }
    if (c.enabled() != null || c.connectTimeoutMs() != null || c.timeoutMs() != null || c.readTimeoutMs() != null
        || c.headers() != null || c.auth() != null || c.errorMap() != null || c.ambiguousCodes() != null
        || c.sensitiveFields() != null || c.resilience() != null || c.allowedHosts() != null || c.dryRun() != null
        || c.healthCheck() != null) {
      problems.add(new ErrorDetail("config", action + " changes only its own fields"));
    }
  }

  /** The record rules besides the route: error map, auth, timeouts. */
  static void common(PartnerConfigValue c, List<ErrorDetail> problems) {
    if (c.errorMap() != null) {
      c.errorMap().forEach(
          (code, m) -> {
            if (m == null || m.suffix() == null || !SUFFIX.matcher(m.suffix()).matches()) {
              problems.add(new ErrorDetail("config.errorMap", "suffix must be upper case letters, digits, underscore"));
            }
          });
    }
    PartnerConfigValue.Auth auth = c.auth();
    String scheme = auth == null || auth.scheme() == null ? "none" : auth.scheme();
    if (!AUTH_SCHEMES.contains(scheme)) {
      problems.add(new ErrorDetail("config.auth.scheme", "unknown auth scheme"));
    } else if (!"none".equals(scheme) && (auth == null || auth.credentialsRef() == null)) {
      problems.add(new ErrorDetail("config.auth.credentialsRef", "this auth scheme needs a credentialsRef"));
    }
    if (auth != null && auth.credentialsRef() != null && !ID.matcher(auth.credentialsRef()).matches()) {
      problems.add(
          new ErrorDetail("config.auth.credentialsRef", "must be a secret name of this partner (lower-case letters, digits, hyphen)"));
    }
    for (Long t : new Long[] {c.connectTimeoutMs(), c.timeoutMs(), c.readTimeoutMs()}) {
      if (t != null && t <= 0) {
        problems.add(new ErrorDetail("config", "timeouts must be positive"));
        break;
      }
    }
    if (c.readTimeoutMs() != null && c.timeoutMs() != null && c.readTimeoutMs() < c.timeoutMs()) {
      problems.add(new ErrorDetail("config.readTimeoutMs", "must not be below timeoutMs (the time limiter decides first)"));
    }
    maintenanceAndEndpoint(c, problems);
  }

  /** The ticket 131b rules: the maintenance window and the endpoint profile (the adapters' rules). */
  static void maintenanceAndEndpoint(PartnerConfigValue c, List<ErrorDetail> problems) {
    PartnerConfigValue.MaintenanceWindow w = c.maintenanceWindow();
    if (w != null) {
      if (w.start() != null && w.end() != null && !w.end().isAfter(w.start())) {
        problems.add(new ErrorDetail("config.maintenanceWindow.end", "must be after start"));
      }
      for (String m : java.util.Arrays.asList(w.messageEn(), w.messageBn())) {
        if (m != null && (m.isBlank() || m.length() > MAX_MAINTENANCE_MESSAGE)) {
          problems.add(new ErrorDetail("config.maintenanceWindow", "a message is 1 to 500 characters"));
          break;
        }
      }
    }
    if (c.endpoints() != null) {
      c.endpoints().forEach((profile, url) -> {
        if (!PartnerConfigValue.ENDPOINT_PROFILES.contains(profile)) {
          problems.add(new ErrorDetail("config.endpoints", "profiles are MOCK and REAL"));
        } else if (!validEndpointUrl(url)) {
          problems.add(new ErrorDetail("config.endpoints." + profile,
              "must be an http(s) URL of host, port and plain path segments"));
        } else if (c.allowedHosts() != null && c.allowedHosts().stream()
            .noneMatch(h -> h.equalsIgnoreCase(java.net.URI.create(url).getHost()))) {
          problems.add(new ErrorDetail("config.endpoints." + profile, "its host must be in allowedHosts"));
        }
      });
    }
    if (c.endpoint() != null && !PartnerConfigValue.ENDPOINT_PROFILES.contains(c.endpoint())) {
      problems.add(new ErrorDetail("config.endpoint", "must be MOCK or REAL"));
    } else if (c.endpoint() != null && (c.endpoints() == null || c.endpoints().get(c.endpoint()) == null)) {
      problems.add(new ErrorDetail("config.endpoints", "the selected endpoint needs its URL"));
    } else if (c.endpoint() == null && c.route() != null && c.route().contains(PartnerConfigValue.ENDPOINT_PLACEHOLDER)) {
      problems.add(new ErrorDetail("config.endpoint", "the route calls {{endpoint}}: select MOCK or REAL"));
    }
  }

  /** True for an endpoint base URL the adapters accept ({@link #ENDPOINT_URL}, no {@code ..} segment). */
  static boolean validEndpointUrl(String url) {
    return url != null && ENDPOINT_URL.matcher(url).matches() && !url.contains("/../") && !url.endsWith("/..");
  }

  /**
   * A partner config (ADR D28, ticket 83): field problems go to {@code problems} (400 MW-VAL-001),
   * the route's own problems, from the copy of the adapters' loader rules ({@link
   * PartnerRouteValidator}), to {@code route} (400 MW-VAL-041, one per problem with its line).
   *
   * @param needsDryRun true for UPSERT_ROUTE (a new route); ENABLE / DISABLE / SET_TIMEOUTS and the
   *     seeds carry none
   */
  void validateRoute(
      String id, PartnerConfigValue c, boolean needsDryRun, List<ErrorDetail> problems, List<ErrorDetail> route) {
    if (c.route() == null || c.route().isBlank()) {
      problems.add(new ErrorDetail("config.route", "a partner needs its route"));
    }
    if (c.allowedHosts() == null || c.allowedHosts().isEmpty()) {
      problems.add(new ErrorDetail("config.allowedHosts", "a route needs the hosts it may call"));
    }
    PartnerConfigValue.DryRun dryRun = c.dryRun();
    // ticket 86: a batch route (from kamelet:mw-sftp-source) takes no calls, so it has no dry-run
    boolean batch = PartnerRouteValidator.isBatch(c.route());
    if (batch && dryRun != null) {
      problems.add(new ErrorDetail("config.dryRun", "a batch route has no dry-run (nothing to send a sample to)"));
    }
    if (!batch && (needsDryRun || dryRun != null)
        && (dryRun == null || dryRun.operation() == null || !DRY_RUN_OPERATIONS.contains(dryRun.operation()))) {
      problems.add(new ErrorDetail("config.dryRun.operation", "a route needs a dry-run of PAY, INQUIRY or STATUS_QUERY"));
    }
    if (dryRun != null && dryRun.baseUrl() != null) {
      if (!plainBaseUrl(dryRun.baseUrl())) {
        problems.add(new ErrorDetail("config.dryRun.baseUrl",
            "must be an absolute http(s) URL without user info, query, fragment, encoding or .. segments"));
      } else if (c.allowedHosts() == null || c.allowedHosts().stream()
          .noneMatch(h -> h.equalsIgnoreCase(java.net.URI.create(dryRun.baseUrl()).getHost()))) {
        problems.add(new ErrorDetail("config.dryRun.baseUrl", "its host must be in allowedHosts"));
      }
    }
    if (c.mappings() != null) {
      c.mappings().keySet().stream()
          .filter(name -> !MAPPING_NAME.matcher(name).matches())
          .findFirst()
          .ifPresent(name -> problems.add(new ErrorDetail("config.mappings", "mapping names are letters, digits, . _ -")));
    }
    common(c, problems);
    if (c.route() != null && !c.route().isBlank()) {
      // ticket 131b: the route as the adapter loads it, {{endpoint}} filled from the selected profile
      routes.problems(id, c.resolvedRoute(), c.mappings(), c.allowedHosts())
          .forEach(p -> route.add(new ErrorDetail("config.route", p.toString())));
    }
  }

  /** An absolute http(s) URL of scheme, host, port and a safe path only (the adapters' rule). */
  static boolean plainBaseUrl(String url) {
    try {
      java.net.URI u = new java.net.URI(url);
      String path = u.getRawPath() == null ? "" : u.getRawPath();
      return u.getScheme() != null && Set.of("http", "https").contains(u.getScheme().toLowerCase(java.util.Locale.ROOT))
          && u.getHost() != null && u.getRawUserInfo() == null && u.getRawQuery() == null && u.getRawFragment() == null
          && (path.isEmpty() || (safePath(path) && path.indexOf('{') < 0 && path.indexOf('}') < 0));
    } catch (java.net.URISyntaxException e) {
      return false;
    }
  }

  static boolean safePath(String path) {
    return path != null
        && SAFE_PATH.matcher(path).matches()
        && !path.contains("//")
        && java.util.Arrays.stream(path.split("/")).noneMatch(".."::equals);
  }

  private static String checkedId(String partnerId) {
    if (partnerId == null || !ID.matcher(partnerId).matches()) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Invalid partner id")
          .withDetails(List.of(new ErrorDetail("partnerId", "lower-case letters, digits and hyphens, at most 64")));
    }
    return partnerId;
  }

  private static void refuseIfAny(List<ErrorDetail> problems) {
    if (!problems.isEmpty()) {
      throw new MwException(ErrorCodes.VAL_INVALID, "The partner change is not valid").withDetails(List.copyOf(problems));
    }
  }

  private static void requireReader(Operator operator) {
    if (!operator.hasAny(AdminRole.READ_PARTNERS)) {
      throw new ForbiddenException("This operation needs a partner viewing role");
    }
  }

  static MwException notFound() {
    return new MwException(ErrorCodes.VAL_NOT_FOUND, "No such partner").withStatus(HttpStatus.NOT_FOUND);
  }
}
