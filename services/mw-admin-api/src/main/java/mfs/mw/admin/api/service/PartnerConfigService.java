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
import mfs.mw.admin.api.security.AdminRole;
import mfs.mw.admin.api.security.OperatorResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Partner config (ticket 38, spec 15.2 Partners, 20.4): reads, and the maker side of a partner
 * change request. A maker ({@link AdminRole#PARTNER_MAKER}) asks to ENABLE, DISABLE, SET_TIMEOUTS
 * or UPSERT (full config); the request stores the full config the approval will publish ({@code
 * payload.config}), the version the maker saw ({@code payload.baseVersion}, null for a new
 * partner) and the changed fields ({@code diff}). Nothing changes until a checker approves ({@link
 * PartnerChangeHandler}). The config is checked here with the adapters' structural and security
 * rules that need no adapter config (ids, paths, secret names, suffixes, URL shape); the adapters
 * check it again (host allowlist, https, JSONata) and keep the old config when they refuse it.
 */
@Service
public class PartnerConfigService {

  private static final Logger log = LoggerFactory.getLogger(PartnerConfigService.class);
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  /** Instance ids and secret names, as the adapters accept them. */
  static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

  static final Pattern SAFE_PATH = Pattern.compile("^/[A-Za-z0-9._~/{}-]*$");
  static final Pattern SUFFIX = Pattern.compile("^[A-Z0-9_]+$");
  static final Set<String> FAMILIES = Set.of("telco", "billpay", "bank");
  static final Set<String> AUTH_SCHEMES = Set.of("none", "api-key", "hmac-sha256");

  private final PartnerRepository partners;
  private final ChangeRequestService changeRequests;
  private final JsonMapper json;
  private final Clock clock;

  public PartnerConfigService(
      PartnerRepository partners, ChangeRequestService changeRequests, JsonMapper json, Clock clock) {
    this.partners = partners;
    this.changeRequests = changeRequests;
    this.json = json;
    this.clock = clock;
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

  // --- maker ----------------------------------------------------------------------------------

  public ChangeRequestView request(Operator maker, String partnerId, PartnerChangeRequestBody body) {
    if (!maker.hasAny(AdminRole.PARTNER_MAKER)) {
      throw OperatorResolver.forbidden("Asking for a partner change needs the ops-maker or integration-maker role");
    }
    String id = checkedId(partnerId);
    Optional<Partner> held = partners.find(id);
    PartnerAction action = body.action();
    List<ErrorDetail> problems = new ArrayList<>();
    unexpected(body, action, problems);
    String family;
    PartnerConfigValue next;
    if (action == PartnerAction.UPSERT) {
      if (body.config() == null) {
        problems.add(new ErrorDetail("config", "UPSERT needs the full config"));
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
      // an UPSERT that leaves enabled out keeps the partner's state (a new one starts enabled)
      next = c.enabled() != null ? c : c.withEnabled(held.map(p -> p.config().isEnabled()).orElse(true));
    } else {
      refuseIfAny(problems);
      Partner p = held.orElseThrow(PartnerConfigService::notFound);
      family = p.family();
      next =
          switch (action) {
            case ENABLE -> p.config().withEnabled(true);
            case DISABLE -> p.config().withEnabled(false);
            case SET_TIMEOUTS ->
                p.config()
                    .withTimeouts(body.timeouts().connectTimeoutMs(), body.timeouts().timeoutMs(), body.timeouts().readTimeoutMs());
            case UPSERT -> throw new IllegalStateException("handled above");
          };
    }
    validate(next, problems);
    refuseIfAny(problems);
    Map<String, Object> before = held.map(p -> asMap(p.config())).orElse(Map.of());
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
    validate(config, problems);
    if (!problems.isEmpty()) {
      throw new IllegalStateException("partner seed " + id + " is invalid: " + problems);
    }
    if (partners.find(id).isPresent()) {
      return false;
    }
    try {
      partners.insert(new Partner(id, family, 0, config, clock.instant(), "seed", null));
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

  /** Fields that do not belong to the action are refused rather than ignored. */
  private static void unexpected(PartnerChangeRequestBody body, PartnerAction action, List<ErrorDetail> problems) {
    if (action != PartnerAction.UPSERT && body.config() != null) {
      problems.add(new ErrorDetail("config", "only UPSERT takes a config"));
    }
    if (action != PartnerAction.UPSERT && body.family() != null) {
      problems.add(new ErrorDetail("family", "only UPSERT takes a family"));
    }
    if (action == PartnerAction.SET_TIMEOUTS && body.timeouts() == null) {
      problems.add(new ErrorDetail("timeouts", "SET_TIMEOUTS needs timeouts"));
    }
    if (action != PartnerAction.SET_TIMEOUTS && body.timeouts() != null) {
      problems.add(new ErrorDetail("timeouts", "only SET_TIMEOUTS takes timeouts"));
    }
  }

  /**
   * The adapters' rules that need no adapter config. Messages never repeat a value (a refused
   * path or secret name may be an attempt to reach something else).
   */
  static void validate(PartnerConfigValue c, List<ErrorDetail> problems) {
    if (c.baseUrl() == null || !absoluteHttpUrl(c.baseUrl())) {
      problems.add(new ErrorDetail("config.baseUrl", "must be an absolute http(s) URL without user info, query or fragment"));
    }
    if (c.operations() == null || c.operations().isEmpty()) {
      problems.add(new ErrorDetail("config.operations", "at least one operation is required"));
    } else {
      c.operations().forEach(
          (name, op) -> {
            String at = "config.operations." + name;
            if (op == null || op.method() == null || op.method().isBlank()) {
              problems.add(new ErrorDetail(at + ".method", "is required"));
            }
            if (op == null || !safePath(op.path())) {
              problems.add(
                  new ErrorDetail(at + ".path", "must be an absolute path of letters, digits, . _ ~ - / and {reference}"
                      + ", without .. segments or //"));
            }
            if (op == null || op.response() == null || op.response().isBlank()) {
              problems.add(new ErrorDetail(at + ".response", "mapping is required"));
            }
          });
    }
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
  }

  static boolean safePath(String path) {
    return path != null
        && SAFE_PATH.matcher(path).matches()
        && !path.contains("//")
        && java.util.Arrays.stream(path.split("/")).noneMatch(".."::equals);
  }

  private static boolean absoluteHttpUrl(String url) {
    try {
      java.net.URI u = new java.net.URI(url);
      return ("https".equals(u.getScheme()) || "http".equals(u.getScheme()))
          && u.getHost() != null
          && u.getRawUserInfo() == null
          && u.getRawQuery() == null
          && u.getRawFragment() == null;
    } catch (java.net.URISyntaxException e) {
      return false;
    }
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
      throw OperatorResolver.forbidden("This operation needs a partner viewing role");
    }
  }

  static MwException notFound() {
    return new MwException(ErrorCodes.VAL_NOT_FOUND, "No such partner").withStatus(HttpStatus.NOT_FOUND);
  }
}
